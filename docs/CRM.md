# CRM ligero (módulo Clientes)

El CRM evoluciona el módulo **Clientes** existente; no hay un módulo paralelo. Los clientes se siguen
creando solos con cada pedido (se reconocen por teléfono, o por nombre si no dejaron teléfono) y también
a mano desde el panel.

## Qué agrega

| Pieza | Dónde |
|---|---|
| Origen del cliente (`CustomerSource`) y campaña de origen | `customer/CustomerSource`, columnas `customers.source` y `customers.source_campaign_id` |
| Segmentos automáticos | `customer/segmentation/CustomerSegmentationService` |
| Línea de tiempo | `customer/activity/*`, tabla `customer_activities` |
| Datos de clientes anteriores al CRM | `customer/CustomerDataBackfill` (al arrancar, idempotente) |
| API | `controller/CustomerController` |
| Audiencias de Marketing | `marketing/service/SegmentService`, `marketing/enums/SegmentKey` |

## Segmentos automáticos

Se calculan en el servidor a partir de los pedidos **no cancelados**; no se guardan ni se editan a mano.
Son distintos de las **etiquetas manuales** (`customers.tags`), que el equipo pone y quita. Un cliente
puede estar en varios segmentos a la vez.

| Segmento | Regla (valores por defecto) | Propiedad |
|---|---|---|
| `NEW` Nuevo | primera compra en los últimos 30 días | `app.customers.segments.new_days` |
| `RECURRING` Recurrente | 2 compras o más y la última en los últimos 30 días | `recurring_min_orders`, `recurring_window_days` |
| `FREQUENT` Frecuente | 3 compras o más | `frequent_min_orders` |
| `INACTIVE` Inactivo | 30 días o más sin comprar | `inactive_days` |
| `HIGH_VALUE` Alto valor | S/ 300 o más en total | `high_value_spent` |

Un cliente sin compras no tiene segmentos. Marketing usa exactamente la misma regla
(`SegmentKey.INACTIVE_30` conserva su nombre por las campañas guardadas, pero usa `inactive_days`).

## Origen

`ONLINE_STORE` (pedido desde la tienda), `POS` (pedido cargado en el panel), `MANUAL` (creado a mano),
`CAMPAIGN` (su primera compra llegó por el enlace de una campaña; guarda `source_campaign_id`), además de
`IMPORT`, `WHATSAPP`, `INSTAGRAM` y `FACEBOOK`, reservados para importaciones y canales futuros. Solo se
fija al crear el cliente (o al atribuir su primera compra a una campaña) y no cambia con compras
posteriores.

## Actividad

Se registra en la **misma transacción** que el cambio que la origina (si el pedido no se guarda, la
actividad tampoco):

- `CUSTOMER_CREATED`, `CUSTOMER_UPDATED`, `TAG_ADDED`, `TAG_REMOVED`, `NOTE_CREATED`: `CustomerService`
  (con el nombre de quien lo hizo).
- `ORDER_CREATED` y `COUPON_USED`: `OrderService.placeOrder`.
- `ORDER_PAID` (una vez, cuando el pedido queda pagado del todo), `ORDER_DELIVERED`, `ORDER_CANCELLED`:
  `CustomerActivityListener` (eventos `PaymentApprovedEvent` y `OrderStatusChangedEvent`, `BEFORE_COMMIT`).
- `REFUND_CREATED`: `OrderPaymentService.refund`.
- `CAMPAIGN_INTERACTION`: `CampaignTrackingService.attributeOrder`.

Al arrancar, `CustomerDataBackfill` reconstruye con SQL por conjuntos la actividad de los pedidos
anteriores al CRM y completa el origen (`NOT EXISTS`: correrlo de nuevo no duplica nada).

## API

| Método | Ruta | Permiso |
|---|---|---|
| GET | `/customers?q&tag&segment&source&marketingAllowed&lastPurchaseFrom&lastPurchaseTo&sort&direction&page&size` | `CUSTOMER_VIEW` |
| GET | `/customers/tags` | `CUSTOMER_VIEW` |
| GET | `/customers/{id}` (perfil: métricas, segmentos con su regla, origen, preferencias) | `CUSTOMER_VIEW` |
| GET | `/customers/{id}/segments` | `CUSTOMER_VIEW` |
| GET | `/customers/{id}/activity?page&size` | `CUSTOMER_VIEW` |
| GET | `/customers/{id}/orders?page&size` | `CUSTOMER_VIEW` + `ORDER_VIEW` |
| POST | `/customers` | `CUSTOMER_CREATE` |
| PUT | `/customers/{id}` | `CUSTOMER_UPDATE`; cambiar notas pide además `CUSTOMER_NOTES` y etiquetas, `CUSTOMER_TAGS` |

- Todo se filtra por la empresa de la sesión: el cliente de otra empresa responde **404**.
- Las **notas internas** solo viajan a quien tiene `CUSTOMER_NOTES` (`notes: null`, `notesVisible: false`
  para el resto). La regla está en el servidor, no en el panel.
- Métricas del perfil: `totalSpent` y `averageTicket` cuentan ventas confirmadas (`OrderStatus.SALE`);
  `ordersCount` cuenta todos los pedidos; `daysSinceLastPurchase` y los segmentos usan compras no
  canceladas. Preferencias: `favoriteCategory`, `mostPurchasedProduct`, `totalProductsPurchased`.

## Rendimiento

- Listado: dos consultas agregadas por empresa (totales y compras) más la de clientes; nada por fila.
  Se filtra y ordena en memoria sobre ese resultado y se pagina la respuesta.
- Perfil: consultas de **un solo cliente** (estadísticas, compras, productos) en lugar de las de toda la
  empresa.
- Actividad y pedidos del perfil van paginados.
- Índices: `customers (company_id, source)`, `customers (company_id, marketing_opt_out)`,
  `customer_activities (company_id, customer_id, created_at)` y
  `customer_activities (customer_id, type, reference_id)`.

## Migraciones

El proyecto no usa Flyway: el esquema lo crea `ddl-auto=update` a partir de las entidades (columnas
nuevas nullable e índices con `@Index`), y los datos los completa `CustomerDataBackfill`.

## Permisos

`CUSTOMER_CREATE`, `CUSTOMER_NOTES` y `CUSTOMER_TAGS` son nuevos. Los traen por defecto dueño,
administrador, encargado y vendedor; solo lectura no. **Los miembros con permisos personalizados no los
reciben automáticamente**: hay que concedérselos desde Equipo.
