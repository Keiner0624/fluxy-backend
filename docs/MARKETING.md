# Marketing

Implementación de `Fluxy_Modulo_Marketing_Funciones_Arquitectura.docx`: la V1 completa y, de la V2, cupones vinculados,
segmentos, embudo y oportunidades automáticas. La IA (V3) y la publicación automática en redes o ads (V4) quedan para
después, como pide el documento: primero medir, después ayudar a crear.

Código en `com.fluxyBackend.marketing` (`controller`, `service`, `repository`, `entity`, `dto`, `enums`, `integration`).
Panel en `fluxy-store-react/src/modules/marketing`; medición en la tienda en `modules/store/hooks/useCampaignTracking.js`.

## Campañas

| Campo | Notas |
|---|---|
| `type` | `STORE`, `PRODUCT`, `CATEGORY` o `COUPON`. `targetId` es el producto, la categoría o el cupón. |
| `objective` | `VISITS`, `SELL_PRODUCT`, `PROMOTION`, `WIN_BACK`. Orienta el texto sugerido y las oportunidades. |
| `channel` | `WHATSAPP`, `INSTAGRAM`, `FACEBOOK`, `TIKTOK`, `DIRECT`, `QR`. Es el canal principal; se pueden sacar enlaces para cualquier otro. |
| `couponId` | Opcional (obligatorio en `COUPON`). Reutiliza un cupón de Cupones; Marketing no duplica su lógica. Requiere el plan con cupones. |
| `segment` | Audiencia opcional para escribir uno a uno por WhatsApp. |
| `trackingCode` | 12 caracteres aleatorios (`SecureRandom`), único. Es lo único de la campaña que sale a la tienda. |
| `startsAt` / `endsAt` | Vigencia. Fuera de ella no se atribuyen visitas ni pedidos. |

Ciclo de vida: `DRAFT → ACTIVE ⇄ PAUSED → FINISHED → ARCHIVED`. Activar con un inicio futuro deja `SCHEDULED`;
`CampaignLifecycleJob` (cada 5 min) pasa a `ACTIVE` las programadas y a `FINISHED` las vencidas. Solo los borradores se
eliminan; una campaña publicada se finaliza y se archiva para no perder resultados. Publicada, lo que se promociona y el
canal quedan fijos (no se mezclan resultados); el contenido, las fechas y el cupón se pueden editar.

## Enlace rastreable y atribución

El enlace es la URL pública de la tienda con `cmp=<trackingCode>`, `utm_source`/`utm_medium`/`utm_campaign` del canal
(Google Analytics, si el comercio lo conectó, atribuye la visita a la misma campaña) y lo que la tienda ya muestra en su
URL (`producto` o `vista`/`categoria`). Nunca lleva datos de clientes.

1. Al entrar con `cmp`, la tienda envía `VIEW` a `POST /store/{companyId}/events` con un id aleatorio del navegador
   (`sessionId`). Si la campaña es de esa tienda y está vigente, responde `accepted` y, si tiene un cupón usable, su
   código para completarlo en el checkout. La tienda guarda el código 7 días.
2. `PRODUCT_VIEW`, `ADD_TO_CART` y `CHECKOUT_STARTED` solo cuentan si ese navegador tuvo antes una `VIEW` de la campaña
   en la ventana: un evento suelto con un código válido no ensucia las métricas.
3. El pedido lleva `marketingSessionId`. Después de confirmado (evento `CampaignOrderEvent`, `AFTER_COMMIT`, en su propia
   transacción) el servidor lo atribuye a la **última campaña vigente cuyo enlace abrió ese navegador en los últimos 7
   días** (`ORDER_COMPLETED`). Si la atribución falla, el pedido no se entera.

Deduplicación: `dedupeKey` único por campaña (una visita por navegador y día, un producto por navegador y día, un pedido
una sola vez). `PAYMENT_COMPLETED` queda definido para cuando el checkout de la tienda cobre en línea.

Resultados: un pedido cuenta con el estado que tiene hoy. **Ventas** son los atribuidos ya confirmados (la regla de
Métricas); los pendientes se muestran aparte y los cancelados dejan de sumar. Conversión = pedidos / visitantes.

## Segmentos y oportunidades

Segmentos sobre el historial sin cancelados: todos, nuevos (primer pedido en 30 días), inactivos 30 días, frecuentes (3+
pedidos), alto valor (S/ 300+) y compradores de una categoría. Quien tiene `Customer.marketingOptOut` (casilla "No quiere
recibir promociones" en Clientes) no entra en ninguno.

Oportunidades (hasta 4, con prioridad y motivo): caída de ventas semanal de 30% o más, clientes inactivos contactables,
producto con visitas desde campañas y casi sin ventas, producto con stock y sin ventas en 30 días, y la categoría que más
crece. No se sugiere lo que ya tiene una campaña en curso. El panel permite ocultar cada una por una semana.

## Plan y permisos

Los límites salen de `PlanCatalog`/`EntitlementService`, no del panel:

| | Free | Pro | Business |
|---|---|---|---|
| Campañas activas o programadas a la vez | 2 | 20 | Sin límite |
| Enlaces, QR y piezas para redes | Sí | Sí | Sí |
| QR con color y logo | — | Sí | Sí |
| Analítica | Totales | Embudo, canales, diaria y productos | Igual + exportación CSV |
| Segmentos | Básicos | Avanzados | Avanzados |
| Campañas con cupón | — | Sí | Sí |

Permisos: `MARKETING_VIEW` (ver, enlaces, segmentos, oportunidades), `MARKETING_CREATE`, `MARKETING_EDIT` (editar y
borrar borradores), `MARKETING_PUBLISH` (activar, pausar, finalizar, archivar) y `MARKETING_ANALYTICS` (resultados). La
lista de clientes de un segmento exige además `CUSTOMER_VIEW`. Encargado tiene todos; Vendedor y Solo lectura, ver.
Todo se audita (`CAMPAIGN_*` en Actividad).

## API

| Método | Ruta | Uso |
|---|---|---|
| GET | `/marketing/overview?days=30` | Resultados del periodo, campañas en curso frente al límite y capacidades del plan |
| GET / POST | `/marketing/campaigns` | Listar (con resultados si hay permiso) / crear |
| GET / PATCH / DELETE | `/marketing/campaigns/{id}` | Detalle / editar / eliminar borrador |
| POST | `/marketing/campaigns/{id}/activate` · `pause` · `finish` · `archive` | Ciclo de vida |
| GET | `/marketing/campaigns/{id}/analytics?from&to` | Resultados |
| POST | `/marketing/campaigns/{id}/links` | Enlace, texto y enlace de compartir de un canal |
| GET | `/marketing/opportunities` | Oportunidades |
| GET | `/marketing/segments` · `/marketing/segments/{key}/customers` | Segmentos y sus clientes |
| POST | `/store/{companyId}/events` | Pública: pasos del embudo (120/min por IP) |

## Diferencias con el documento

- Rutas sin `/api/v1`, igual que el resto de la API de Fluxy.
- Ids `Long` como el resto de las entidades; el identificador no predecible de la campaña es `trackingCode`.
- Sin Flyway: el esquema lo crea `ddl-auto=update` como en todo el proyecto (tablas `marketing_campaigns` y
  `campaign_events`, columna `customers.marketing_opt_out`).
- Publicación automática: `integration.ChannelPublisher` es la interfaz para Meta, TikTok o la API de WhatsApp; hoy solo
  existe `ManualSharePublisher` (enlaces de compartir de WhatsApp y Facebook).
