# Facturación electrónica

Implementación de `Fluxy_Facturacion_Segura.docx`: fases V1 (núcleo) y V2 (automatización) completas, y de V3 las
notas de crédito y la impresión del ticket. Quedan para después el POS propio, el POS Agent (impresión térmica local),
otros proveedores y la API para ERP.

Código en `com.fluxyBackend.invoicing` (`controller`, `dto`, `service`, `provider`, `entity`, `repository`, `event`,
`validation`, `enums`). Panel en `fluxy-store-react/src/modules/invoicing`. `billing` sigue siendo la suscripción que
el negocio le paga a Fluxy; `invoicing`, los comprobantes que el negocio emite a sus clientes. Por eso la entidad del
documento `BillingConfiguration` se llama acá `InvoicingConfiguration`.

> Antes de emitir en producción: validar con el proveedor elegido (hoy Nubefact) el mapeo de campos y los requisitos
> vigentes de SUNAT con una cuenta de pruebas, y configurar el padrón de RUC.

## Reglas de seguridad

- **Tenant**: todo acceso a un comprobante es `findByIdAndCompanyId`; la empresa sale de la sesión. Descargas, reenvíos,
  notas y reintentos de otra empresa responden 404.
- **Habilitación solo en el servidor**: el panel manda datos (RUC, dirección, preferencias); `verificationStatus`,
  `canIssueReceipt`, `canIssueInvoice`, `electronicIssuer` y el estado `ACTIVE` los calcula
  `InvoicingConfigurationService`. Campos así en un JSON se ignoran. Antes de crear **y** antes de enviar cada comprobante
  se vuelve a autorizar con lo guardado (plan, configuración ACTIVE, perfil y tipo permitido).
- **Verificación del RUC**: formato y dígito verificador siempre; existencia, estado (ACTIVO) y condición (HABIDO) con el
  padrón configurado en el servidor (`RUC_LOOKUP_URL`, `RUC_LOOKUP_TOKEN`). Si el padrón no responde, el perfil queda en
  ERROR: nunca se asume válido. En modo de prueba sin padrón se acepta el formato, marcado `SANDBOX`, y eso no sirve para
  emitir de verdad. Cambiar un RUC ya verificado exige identidad confirmada.
- **Credenciales**: el token del proveedor se guarda con AES-256-GCM (`SecretCipher`, prefijo `v1:` para rotar). La clave
  vive en el entorno (`SECRETS_ENCRYPTION_KEY`), nunca en la base; sin ella se deriva de `JWT_SECRET`. El token nunca
  vuelve al panel (solo sus últimos 4 caracteres) y guardarlo exige identidad confirmada.
- **SSRF**: la ruta del proveedor la escribe el vendedor, así que solo se llama por HTTPS a hosts permitidos
  (`NUBEFACT_ALLOWED_HOSTS`, por defecto `nubefact.com`), sin seguir redirecciones. Lo mismo para los enlaces de PDF/XML
  que devuelve el proveedor.
- **Archivos privados**: PDF y XML se transmiten desde Fluxy tras validar empresa y permiso; las URLs del proveedor no salen
  del servidor. La consulta pública (correo, QR) usa un token aleatorio de 256 bits, revocable, y muestra lo mínimo.
- **Cambios sensibles**: cambiar RUC, proveedor o credenciales deja la configuración en `REQUIRES_ACTION` hasta volver a
  probar la conexión y activar. La revalidación diaria (04:20 Lima, cada 7 días por negocio) suspende la emisión si el RUC
  deja de estar activo o habido, sin tocar lo emitido, y avisa al dueño.
- **Auditoría**: verificación de RUC (enmascarado `20******005`), cambios de proveedor (sin secretos), activación, series,
  emisiones, rechazos, fallas, reenvíos, enlaces revocados y notas de crédito.

## Numeración e idempotencia

- Series por empresa, entorno (TEST/PRODUCTION) y tipo: `B001`, `F001`, `BC01` y `FC01` por defecto. Las de prueba nunca
  comparten numeración con las reales. Únicos: `(company_id, environment, document_type, series)` y
  `(company_id, environment, series, number)`.
- El número se reserva en el servidor con la fila de la serie bloqueada (`SELECT … FOR UPDATE`) dentro de la transacción del
  comprobante. Las series se buscan como proyección y la fila se relee con el lock tomado: si no, Hibernate devolvía la
  copia vieja y dos cajas podían tomar el mismo número (lo detectó la prueba de concurrencia).
- `POST /invoicing/documents`, `/credit-notes`, `/resend-email` y `/retry` aceptan `Idempotency-Key`: la misma clave
  devuelve el mismo resultado y con otro contenido responde 422. Además, un pedido no puede tener dos comprobantes de venta
  vigentes (409 `DOCUMENT_ALREADY_EXISTS`).

## Ciclo de vida y procesamiento

`PENDING → PROCESSING → ACCEPTED`, o `REJECTED` (validación) o `ERROR` (falla técnica). Un aceptado no se edita: se anula
con una nota de crédito por el total (`CANCEL_PENDING → CANCELLED`; si la rechazan vuelve a `ACCEPTED`).

- Emitir solo registra el comprobante y responde en milisegundos; el envío sigue en otro hilo (`@Async` + `AFTER_COMMIT`).
- La fila del comprobante es la cola (hace de outbox): `status` + `nextAttemptAt`. `InvoicingJobs` retoma cada 15 s lo
  pendiente, así que un reinicio no pierde envíos ni correos.
- Cada envío se toma con un `UPDATE` condicional: dos hilos o dos instancias nunca envían el mismo documento.
- Falla técnica: reintentos a los 30 s, 2 min y 10 min; después `ERROR`, auditoría y correo al dueño. Cortacircuito por
  proveedor (5 fallas → pausa de 2 min).
- Recibido sin respuesta de SUNAT (las boletas van en el resumen diario): se consulta a los 2, 10, 30 min, 1, 3 y 6 h; a las
  72 h queda en `ERROR` para revisarlo en el proveedor.
- El proveedor debe ser idempotente por serie y número: si el documento ya existe, se consulta su estado en vez de emitir.

## Importes

Los precios de la tienda incluyen IGV. El descuento del pedido (cupón) se reparte entre las líneas en proporción a su
importe; la base y el IGV se calculan sobre el total del documento y el redondeo lo absorbe la última línea, así el
comprobante suma exactamente lo cobrado. Afectación configurable: gravado 18 %, exonerado o inafecto.

Receptor: la factura exige RUC válido y razón social; la boleta desde S/ 700 exige documento de identidad (debajo, puede ir a
nombre del cliente o "CLIENTES VARIOS"). El Nuevo RUS no emite facturas. Los datos del emisor, del receptor y los ítems se
copian al comprobante.

## Emisión automática, correo e impresión

- Automática (opcional): cuando el pedido queda pagado por completo o cuando se entrega. Usa el comprobante que pidió el
  cliente en el checkout (boleta o factura, con sus datos ya validados) o, si no pidió, boleta a su nombre.
- Correo (Brevo) con el PDF y opcionalmente el XML adjuntos y el enlace seguro. Estado propio
  (`PENDING → SENT / FAILED`, 3 reintentos). `DELIVERED` y `BOUNCED` quedan para cuando se reciban los eventos del
  proveedor de correo. Reenviar nunca vuelve a emitir.
- Ticket de 80 o 58 mm desde el navegador, con el QR de SUNAT (`RUC|tipo|serie|número|IGV|total|fecha|tipo doc|doc|hash|`).
  "Imprimir al emitir" abre el ticket apenas el comprobante es aceptado. El POS Agent para impresión térmica silenciosa es
  una fase posterior.

## Proveedores

`ElectronicBillingProvider` (`issue`, `getStatus`, `healthCheck`, `download`); el resto de Fluxy no conoce sus APIs.

| Código | Entorno | Notas |
|---|---|---|
| `SANDBOX` | TEST | Acepta todo lo que pasó las validaciones de Fluxy, no envía nada a SUNAT. Fluxy genera el PDF (A4) y un XML UBL 2.1 sin firma, marcados "sin valor tributario". |
| `NUBEFACT` | PRODUCTION | API JSON de Nubefact con la ruta y el token de la cuenta del negocio. Usa el PDF/XML/CDR del proveedor. |

## Webhooks

`POST /webhooks/invoicing/{provider}` para proveedores que notifican. Cabecera
`X-Fluxy-Signature: t=<unix>,v1=<hex HMAC-SHA256 de "t.cuerpo">` con `INVOICING_WEBHOOK_SECRET`; se rechaza sin firma
válida o con más de 5 minutos de diferencia, y un `eventId` repetido responde `duplicate: true` sin volver a aplicarse.
Cuerpo: `{eventId, ruc, series, number, status: ACCEPTED|REJECTED|PROCESSING, message}`.

## Permisos y plan

`INVOICE_VIEW`, `INVOICE_CREATE`, `INVOICE_RESEND`, `INVOICE_CREDIT_NOTE`, `INVOICING_CONFIGURE`, `INVOICING_SERIES`.
Dueño y administrador: todos. Encargado: ver, emitir, reenviar y notas de crédito. Vendedor: ver, emitir y reenviar.
Solo lectura: ver. Disponible desde el plan Pro (`Feature.ELECTRONIC_INVOICING` en `PlanCatalog`).

## Métricas

`invoicing.invoice.issue{outcome}`, `invoicing.invoice.issue.duration`, `invoicing.invoice.provider.timeout`,
`invoicing.email.delivery{result}`, `invoicing.webhook.invalid_signature` e `invoicing.queue.pending`.

## API

| Método | Ruta | Permiso |
|---|---|---|
| GET | `/invoicing/status` | INVOICE_VIEW |
| GET / PUT | `/invoicing/configuration` | INVOICING_CONFIGURE |
| POST | `/invoicing/configuration/verify-ruc` · `/test` · `/activate` · `/pause` | INVOICING_CONFIGURE |
| PUT | `/invoicing/configuration/provider` | INVOICING_CONFIGURE |
| POST / PUT | `/invoicing/series` · `/invoicing/series/{id}` | INVOICING_SERIES |
| GET / POST | `/invoicing/documents` | INVOICE_VIEW / INVOICE_CREATE |
| GET | `/invoicing/documents/{id}` · `/pdf` · `/xml` | INVOICE_VIEW |
| POST | `/invoicing/documents/{id}/resend-email` | INVOICE_RESEND |
| POST | `/invoicing/documents/{id}/credit-notes` | INVOICE_CREDIT_NOTE |
| POST | `/invoicing/documents/{id}/retry` · `/public-link/revoke` | INVOICE_CREATE |
| GET | `/store/documents/{token}` · `/pdf` | Público (token) |
| POST | `/webhooks/invoicing/{provider}` | Firma HMAC |

Rutas sin `/api/v1`, como el resto de Fluxy. Esquema creado por `ddl-auto=update` (sin Flyway). Los comprobantes se
conservan aunque se elimine el negocio: son documentos tributarios; se borran la configuración y las credenciales.

## Checklist de salida a producción

Hecho en código: verificación fiscal server-side, capacidades no aceptadas del frontend, revalidación, bloqueo por perfil,
auditoría sin secretos, aislamiento por empresa probado, permisos, idempotencia, numeración concurrente probada, tiempos
límite + reintentos + cortacircuito, webhooks firmados con protección de replay, credenciales cifradas, archivos privados,
correo con estado y reenvío, métricas y pruebas de punta a punta con un Nubefact falso.

Pendiente de operación: contratar el padrón de RUC y el proveedor, validar con su entorno de pruebas, definir
`SECRETS_ENCRYPTION_KEY` e `INVOICING_WEBHOOK_SECRET`, alertas sobre las métricas, backups probados y la revisión
legal/tributaria vigente.
