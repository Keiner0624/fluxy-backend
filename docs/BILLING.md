# Planes y suscripciones

Implementación de `Fluxy_Arquitectura_Planes_Suscripciones_Cancelaciones.docx` (fases P0 y P1, y lo esencial de P2).
Código en `com.fluxyBackend.billing`.

## Modelo de renovación

**Manual**, como dicen los Términos vigentes (2026-09-13): cada pago en Mercado Pago compra de 1 a 12 meses de un plan y
no hay cobros automáticos. `Subscription.renewal` ya admite `AUTOMATIC` para el día que se integren suscripciones del
proveedor; eso exige cambiar los Términos y pedir el consentimiento del cobro recurrente.

| Acción | Qué pasa |
|---|---|
| Pagar el mismo plan | Los meses se suman al final de lo ya pagado (no se pierden días). |
| Pagar un plan superior | Se aplica al confirmar el pago. Los días que quedaban del plan anterior se convierten en días del nuevo en proporción al precio (`creditDays`). Los días de prueba no se convierten. |
| Pagar un plan inferior | Queda programado (`nextPlan`) desde el fin del periodo actual, ya pagado. Si el uso excede el nuevo límite se avisa antes de pagar; nada se borra, solo no se pueden crear más productos. |
| Cancelar | `cancelAtPeriodEnd = true`. La suscripción sigue `ACTIVE` hasta el fin del último periodo pagado (incluido un cambio programado) y ahí pasa a `CANCELED` y el negocio a Free. Repetirlo no cambia nada. |
| Reactivar | Quita `cancelAtPeriodEnd` mientras el periodo sigue vigente. Pagar un periodo nuevo también la reactiva. |
| No renovar | Al terminar lo pagado pasa a `EXPIRED` y el negocio a Free. |

Estados: `TRIALING`, `ACTIVE`, `CANCELED`, `EXPIRED` (`PAST_DUE` queda reservado para la renovación automática).

## Fuente de verdad y acceso

- `Subscription` (una por empresa) es la fuente de verdad. `Company.plan` y `Company.planExpiresAt` son una copia que
  solo escribe `SubscriptionService` (`planExpiresAt` = fin del último periodo pagado).
- `PlanCatalog` define precio, límite de productos y funciones de cada plan. `EntitlementService` / `PlanCatalog.has`
  deciden el acceso en el servidor: métricas, reportes, cupones (gestión y aplicación en la tienda), estilo (guardar y
  mostrar en la tienda pública), WhatsApp, dominio, IA y límite de productos.
- Un plan cuyo periodo pagado terminó vale como Free **en el acto**, aunque la tarea de fin de periodo no haya corrido.
- Bajar de plan o vencer nunca borra datos ni configuración: se dejan de aplicar.

## API

| Método | Ruta | Uso |
|---|---|---|
| GET | `/billing/plans` | Catálogo. |
| GET | `/billing/subscription` | Estado, periodo, hasta cuándo está pagado, cancelación y cambio programado. |
| GET | `/billing/usage` | Productos usados frente al límite. |
| GET | `/billing/subscription/quote?plan=&months=` | Qué pasaría al pagar: tipo, importe, fechas, días convertidos y avisos. |
| POST | `/billing/subscription/checkout` | URL de Mercado Pago. No cambia el plan. |
| POST | `/billing/subscription/cancel` | Cancelar al fin del periodo (motivo opcional). |
| POST | `/billing/subscription/reactivate` | Deshacer la cancelación. |
| POST | `/billing/subscription/trial` | Prueba de Pro por 1 mes, una vez. |
| GET | `/billing/history` | Cambios y cobros. |
| POST | `/payments/webhook` | Aviso de Mercado Pago (firma verificada; se consulta el pago al proveedor). |

Todas las de `/billing` (salvo planes) exigen `BILLING_MANAGE` (solo el dueño) y toman la empresa de la sesión.
Checkout, cancelación, reactivación y prueba aceptan `Idempotency-Key`. `/payments/create-preference` queda por
compatibilidad y usa el mismo checkout.

## Pagos

- El plan cambia solo con el webhook: nunca por los parámetros del retorno del navegador.
- Cada pago aprobado se guarda en `billing_payments` con el periodo que cubrió. Un pago ya registrado (o procesado
  antes de este módulo, en `processed_payments`) no se vuelve a aplicar; la empresa se bloquea durante la aplicación.
- Monto y moneda se verifican contra el catálogo.
- `PaymentProvider` desacopla el proveedor; `MercadoPagoProvider` es la implementación actual.

## Tareas

- `SubscriptionLifecycleJob.closeEndedPeriods`: cada 5 min (`app.billing.lifecycle_every_ms`) aplica cambios
  programados y cierra periodos vencidos. Cada empresa en su transacción; reintentable.
- `SubscriptionLifecycleJob.remindUpcomingEnds`: 9:00 (Lima). Avisos a 7, 3 y 1 días, uno por etapa; con la
  cancelación pedida, solo a 3 y 1 días.
- `SubscriptionBackfill`: al arrancar crea la suscripción de los negocios que tenían plan antes del módulo.

## Auditoría

`subscription_events` guarda el historial de la suscripción; además, `audit_logs` registra `PLAN_CHANGED`,
`SUBSCRIPTION_CANCEL_REQUESTED` y `SUBSCRIPTION_REACTIVATED` para la sección Actividad.

## Diferencias con el documento

- Tablas creadas por Hibernate (`ddl-auto=update`), como el resto del proyecto. Migrar a Flyway requiere baselinar la
  base de producción y conviene hacerlo aparte.
- Rutas sin prefijo `/api/v1`, igual que el resto de la API.
- No hay `DELETE /billing/subscription/pending-change`: con renovación manual el cambio programado ya está pagado;
  anularlo es un reembolso y se atiende por soporte.
- Catálogo de planes en código (`PlanCatalog`, con versión) en lugar de tabla.
- Pendiente para la renovación automática: `PAST_DUE`, reintentos de cobro y conciliación con el proveedor.

## Pruebas

`SubscriptionIntegrationTest`: checkout sin activar, pago duplicado, monto manipulado, renovación que suma, subida con
días convertidos, bajada programada aplicada una sola vez, aviso de límite al bajar, cancelar dos veces, reactivar sin
duplicar, sin plan no hay qué cancelar, solo el dueño y solo su negocio, vencimiento con acceso Free inmediato,
funciones pagas validadas en el servidor, prueba gratuita única y contadores del menú por permiso.
