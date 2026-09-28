# Seguridad de Fluxy

Implementación del *Fluxy Security Blueprint 2026*. Este documento explica qué
hace el backend, qué hay que configurar y qué hacer ante un incidente.

## 1. Identidad y sesiones

| Pieza | Comportamiento |
|---|---|
| Access token | JWT HS256 de **15 minutos**, con `uid`, `sid` (sesión) y `typ`. Un token de una sesión revocada deja de servir aunque no haya vencido (caché de 15 s por instancia). |
| Refresh token | Opaco de 256 bits, guardado como SHA-256. **Rota en cada uso.** Dura 14 días con "Recordarme" y 12 horas sin. |
| Reutilización | Un refresh ya rotado dentro de 30 s responde `409 REFRESH_ROTATED` (dos pestañas). Pasado ese margen se considera robo: se revoca la sesión (`401 SESSION_REVOKED`), se audita y dispara alerta. |
| Sesiones | `GET /me/sessions`, `DELETE /me/sessions/{id}`, `POST /me/sessions/revoke-others`, `POST /me/logout`. Aviso por correo al iniciar sesión desde un dispositivo nuevo. |
| Step-up | Cambiar correo o celular, vincular proveedores, transferir la propiedad y eliminar el negocio exigen `POST /me/reauth` en los últimos 10 minutos (`403 REAUTH_REQUIRED`). |
| Contraseñas | BCrypt, 10 a 72 bytes, sin contraseñas comunes ni el correo. Login con tiempo constante, bloqueo progresivo por cuenta e IP. |
| Recuperación | Respuesta idéntica exista o no la cuenta. Enlace de un solo uso (hash en base), 30 minutos, límite por IP y por correo. Al completar se cierran **todas** las sesiones y se avisa por correo. |

Los tokens emitidos antes de esta versión no tienen `sid`: cada persona inicia sesión una vez más.

## 2. Registro verificado

1. `POST /auth/signup` crea el usuario en `PENDING_VERIFICATION` y un borrador del negocio. **No crea la empresa.**
2. Se envía un código de 6 dígitos al correo (Brevo) y, si Twilio está configurado, por SMS al celular. La verificación por WhatsApp está **congelada** (el código sigue en `WhatsAppOtpService` y se reactiva con `PHONE_VERIFICATION_CHANNEL=whatsapp`).
3. `POST /auth/signup/verify` con cada código. Al completar lo pendiente se crea la empresa y se abre la sesión.

Códigos: HMAC con clave derivada, 10 minutos, 5 intentos, reenvío cada 60 s, 5 por destino y 10 por IP por hora, envío asíncrono después del commit, un código nuevo invalida los anteriores. Los registros sin completar se borran a los 7 días.

### Google y Apple (OIDC)

`POST /auth/oauth/nonce` → el frontend pide el ID token con ese nonce → `POST /auth/oauth/{google|apple}`.
Se verifica firma RS256 con las JWKS del proveedor, emisor, audiencia (client id), vencimiento y nonce de un solo uso.
Si el correo ya tiene cuenta, se vincula y entra solo cuando el proveedor garantiza ese correo (`email_verified` y, en Google, Gmail o un dominio de Workspace con `hd`) y la cuenta ya lo tenía verificado. Queda en la actividad (`IDENTITY_LINKED`, origen `AUTO_EMAIL`) y se avisa por correo. En cualquier otro caso responde `409 ACCOUNT_EXISTS` y se vincula desde Seguridad, con identidad reciente.

## 3. Autorización y aislamiento

- Toda consulta se filtra por la empresa del `Member` resuelto en el servidor; el id de empresa nunca viene del cliente. Un recurso de otra empresa responde **404**.
- Roles: `OWNER`, `ADMIN`, `MANAGER`, `SELLER`, `WAREHOUSE`, `VIEWER`, con permisos por módulo (`@RequirePermission`). `BILLING_MANAGE` es exclusivo del dueño; `SETTINGS_MANAGE` y `AUDIT_VIEW` no los tienen los roles operativos.
- Nadie modifica al dueño: la propiedad se transfiere (`/team/ownership-transfer`), el destinatario acepta desde su cuenta y queda auditado.
- Desactivar a alguien corta su acceso y revoca sus sesiones al instante.
- Asignación masiva: la configuración de la tienda usa un DTO con validación; productos ignoran `id`, `company`, `owner`, `stock` y fechas.

## 4. Abuso y robustez

| Límite | Valor |
|---|---|
| API autenticada | 300 req/min por usuario |
| Tienda pública | 240 lecturas/min, 15 pedidos/10 min y 120 eventos de campaña/min por IP |
| Webhooks de facturación | 120/min por IP, además de la firma HMAC |
| Registro y OAuth | 10/hora por IP |
| Renovación de sesión | 60/min por IP |
| Recuperación de contraseña | 10/hora por IP y 3/hora por correo (silencioso) |
| Reautenticación y cambios sensibles | 10 cada 15 min por usuario |
| Exportación de datos | 5/hora por empresa |
| Libro de Reclamaciones | 5 hojas/hora por IP |
| Cuerpo de la petición | 1 MB (`413`) |

Todas las respuestas 429 llevan `Retry-After`. Los límites son por instancia (en memoria); con más de una instancia hay que moverlos a Redis.

- **Idempotencia:** `Idempotency-Key` en pedidos de la tienda, `POST /orders`, `POST /payments`, reembolsos y `create-preference`. Misma clave y contenido → misma respuesta (`Idempotent-Replayed: true`); otro contenido → `422`; en curso → `409`. Se guardan 24 h.
- **Concurrencia:** stock y sesiones con bloqueo pesimista; `CHECK (stock >= 0)` en PostgreSQL.
- **Proveedores externos:** tiempos límite en Brevo, Twilio, WhatsApp, JWKS, Vercel, Mercado Pago y Gemini; circuit breaker en Brevo, Twilio y WhatsApp.
- **Errores:** forma única `{status, code, message, path, requestId}`; los 500 no exponen detalles.
- **Cabeceras:** HSTS, `Referrer-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Permissions-Policy`.

## 5. Ciclo de vida de negocios FREE

Solo cuenta la **actividad real**: cambios hechos desde el panel y pedidos recibidos. Iniciar sesión no cuenta.

| Días sin actividad | Estado | Efecto |
|---|---|---|
| 30 | `INACTIVE` | Aviso. Cualquier actividad lo vuelve a `ACTIVE`. |
| 60 (y 7 días inactivo) | `SUSPENDED` | Catálogo visible, **no acepta pedidos** (`423`). Botón "Reactivar". |
| 90 (y 14 días suspendido) | `ARCHIVED` | Tienda fuera de línea (`410`); panel en solo lectura hasta reactivar. |
| 180 (y 30 días archivado) | `DELETION_PENDING` | Aviso con fecha; 30 días de gracia. |
| Fin de la gracia | `ANONYMIZED` | Solo con `LIFECYCLE_PURGE_ENABLED=true`. Borra datos personales y deja montos. |

Los planes pagos vigentes no avanzan y, si pagan estando frenados, se reactivan. El job corre a las 05:00 (Lima).
El dueño puede programar la eliminación (`POST /company-account/deletion`, identidad reciente + nombre exacto del negocio, 14 días de gracia) y exportar todo en JSON (`GET /company-account/export`).

## 6. Auditoría y observabilidad

- `audit_logs`: accesos y fallos, sesiones, verificaciones, cambios de contraseña/correo/celular, equipo y propiedad, plan, configuración, cancelaciones, reembolsos, ajustes de stock, borrados, exportaciones y cambios de estado. Sin secretos; IP como hash. Retención 365 días. Consulta: `GET /audit` (permiso `AUDIT_VIEW`).
- Cada petición lleva `X-Request-Id` (se acepta el del cliente o se genera) y aparece en cada línea de log.
- Micrometer: `fluxy.auth.login`, `fluxy.auth.refresh_reuse`, `fluxy.otp.sent`, `fluxy.ratelimit.blocked`, `fluxy.http.denied`, `fluxy.http.server_errors` y `fluxy.company.lifecycle` en `/actuator/metrics` (solo token de administrador).
- Alertas por correo a `SECURITY_ALERT_EMAIL` (o `ADMIN_EMAIL`) al superar umbrales: fallos de login, 5xx, 429, 401/403, fallos de envío de códigos y **cualquier** reutilización de refresh token. Máximo una alerta por señal por hora.

## 7. Variables de entorno

| Variable | Obligatoria | Uso |
|---|---|---|
| `JWT_SECRET` | Sí | Base64 de 32+ bytes. Rotarla invalida todas las sesiones y códigos vigentes. |
| `BREVO_API_KEY`, `MAIL_FROM` | Sí | Códigos, recuperación y avisos. El remitente debe estar verificado en Brevo. |
| `PHONE_VERIFICATION_CHANNEL` | No | `sms` (por defecto), `whatsapp` (congelado) o `none`. |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | No | Verificación del celular por SMS. Sin ellas el registro verifica solo el correo. |
| `TWILIO_SMS_FROM` o `TWILIO_MESSAGING_SERVICE_SID` | Con Twilio | Remitente: número de Twilio (`+1…`) o Messaging Service (`MG…`, tiene prioridad). |
| `SMS_BRAND` | No | Nombre en el texto del SMS (por defecto `Fluxy`). |
| `WHATSAPP_CLOUD_TOKEN`, `WHATSAPP_CLOUD_PHONE_NUMBER_ID`, `WHATSAPP_CLOUD_OTP_TEMPLATE` | No | **Congelado.** Solo se usan con `PHONE_VERIFICATION_CHANNEL=whatsapp`: plantilla **Authentication** aprobada en Meta, con el código como `{{1}}`. |
| `OAUTH_GOOGLE_CLIENT_ID` | No | Client ID web de Google Cloud; origen autorizado = URL del frontend. |
| `OAUTH_APPLE_CLIENT_ID`, `OAUTH_APPLE_REDIRECT_URI` | No | Services ID de Apple y la URL de retorno registrada. |
| `SECURITY_ALERT_EMAIL` | Recomendada | Destino de las alertas. |
| `LEGAL_PROVIDER_NAME`, `LEGAL_PROVIDER_TAX_ID`, `LEGAL_PROVIDER_ADDRESS` | Recomendada | Titular, RUC y domicilio que encabezan el Libro de Reclamaciones. Deben coincidir con `PROVIDER` en `src/modules/landing/legal/<versión>.js` del frontend. |
| `COMPLAINTS_NOTIFY_EMAIL`, `COMPLAINTS_RESPONSE_BUSINESS_DAYS` | No | A quién avisar de cada hoja (por defecto soporte@fluxyweb.com) y plazo de respuesta en días hábiles (15). |
| `JWT_ACCESS_TTL_MINUTES`, `SESSION_REMEMBER_DAYS`, `SESSION_DEFAULT_HOURS` | No | 15, 14 y 12 por defecto. |
| `LIFECYCLE_*_DAYS`, `LIFECYCLE_PURGE_ENABLED` | No | Umbrales del ciclo de vida; purga apagada por defecto. |
| `VERIFICATION_LOG_CODES` | Solo local | Escribe los códigos en el log. **Nunca en producción.** |

## 7.0 Planes y suscripciones

El acceso por plan se decide en el servidor (`EntitlementService`); un plan vencido vale Free en el acto. Cancelar deja el plan activo hasta el fin de lo pagado. Detalle en `docs/BILLING.md`.

## 7.1 Documentos legales y Libro de Reclamaciones

- **Versiones:** `LegalAcceptance.TERMS_VERSION` debe ser igual a `CURRENT_LEGAL_VERSION` del frontend. Cada versión publicada es un archivo inmutable y las anteriores siguen visibles en `/terms`.
- **Aceptación:** el registro guarda la versión, la fecha y la IP como hash. `GET /me/legal` indica si falta aceptar la vigente; el panel lo pide con un aviso que no se puede cerrar, y `POST /me/legal/accept` solo acepta la versión vigente (`409 LEGAL_VERSION_OUTDATED`). Queda en auditoría como `TERMS_ACCEPTED`.
- **Libro de Reclamaciones:** `POST /complaints` (público) guarda la hoja con código `LR-AAAA-NNNNNN`, fecha límite de 15 días hábiles (sin contar sábados ni domingos) e IP como hash; envía copia al consumidor y aviso a `COMPLAINTS_NOTIFY_EMAIL`. `GET /admin/complaints` y `POST /admin/complaints/{id}/response` son solo para administración; la respuesta se envía por correo y no se puede reemplazar.
- **Cookies:** el sitio carga Google Analytics solo con consentimiento (`fluxy_cookie_consent`); las tiendas no cargan la medición de Fluxy.

## 8. CI, dependencias y secretos

- GitHub Actions: pruebas → imagen Docker → gitleaks. Render despliega con `autoDeployTrigger: checksPass`.
- Dependabot semanal para Maven, Docker y Actions.
- **A configurar en GitHub (no se puede desde el código):** Settings → Code security → activar *Secret scanning* y *Push protection*; Branches → proteger `main` exigiendo el check `CI / test`.
- `.env` está en `.gitignore`. Si un secreto llegó a un commit, rotarlo: borrarlo del historial no alcanza.

## 9. Base de datos y respaldos

- Esquema con `ddl-auto=update` más `SchemaUpgrade` (migraciones idempotentes al arrancar). **Pendiente:** pasar a Flyway antes de tener más de una instancia o cambios destructivos de columnas.
- Render PostgreSQL free no tiene backups automáticos. Antes de activar la purga: pasar a un plan con backups diarios o programar `pg_dump` diario a almacenamiento externo, y **probar una restauración**.
- Índices en pedidos, productos, clientes, sesiones, códigos, auditoría y estado de empresas.

## 10. Respuesta a incidentes

1. **Detectar:** alerta por correo o reporte. Anotar el `requestId`.
2. **Contener:**
   - Cuenta comprometida: el dueño cierra sesiones desde Seguridad, o el administrador usa `POST /admin/vendors/{companyId}/revoke-sessions`.
   - Integrante malicioso: desactivarlo en Equipo (revoca sus sesiones).
   - Clave filtrada: rotar `JWT_SECRET` en Render (cierra todas las sesiones del sistema) y la credencial del proveedor afectado.
   - Abuso de tráfico: bajar límites o bloquear la IP en el proxy.
3. **Investigar:** `audit_logs` por empresa, acción y fecha; logs de Render por `requestId`.
4. **Recuperar:** restaurar desde backup si hubo pérdida; forzar cambio de contraseña con el flujo de recuperación.
5. **Comunicar:** avisar a los negocios afectados qué pasó, qué datos y qué hicimos, dentro de las 72 h.
6. **Aprender:** agregar una prueba que reproduzca el caso.

## 11. Pruebas

`./mvnw verify` corre, entre otras, `SecurityBlueprintIntegrationTest` y `OidcTokenVerifierTest`: registro verificado, bloqueo y vencimiento de códigos, rotación y reutilización de refresh tokens, cierre de sesiones, tokens falsos, aislamiento entre negocios (IDOR), roles, desactivación, límites con `Retry-After`, tamaño máximo, asignación masiva, idempotencia, ciclo de vida con plan pago exento, identidad reciente para eliminar, recuperación de un solo uso y respuesta genérica, y validación OIDC (audiencia, emisor, nonce, vencimiento, firma ajena y algoritmo simétrico).

Las pruebas usan H2. Hay que cubrir también con PostgreSQL real (Testcontainers) los bloqueos y el `CHECK` de stock cuando haya Docker en CI.

## Facturación electrónica

Credenciales de proveedores cifradas con AES-256-GCM (clave en `SECRETS_ENCRYPTION_KEY`, fuera de la base), habilitación
fiscal calculada solo en el servidor, llamadas a proveedores restringidas a hosts permitidos (SSRF), archivos privados
transmitidos por Fluxy y webhooks firmados con protección de replay. Detalle en `docs/INVOICING.md`.
