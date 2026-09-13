# Fluxy Backend

Backend de **Fluxy**, una plataforma SaaS para que negocios puedan crear y gestionar su propia tienda online, administrar productos, recibir pedidos y conectar con sus clientes de forma simple.

Este backend está desarrollado con **Java + Spring Boot** y expone una API REST para manejar autenticación, empresas, productos, pedidos y pagos.

## Tecnologías utilizadas

- Java 21
- Spring Boot 4
- Spring Security
- JWT (jjwt)
- Spring Data JPA / Hibernate
- PostgreSQL
- Maven
- Mercado Pago SDK
- SendGrid + Java Mail Sender
- Web Push (VAPID)
- OpenAPI 3 + Swagger UI (springdoc)

## Características principales

- Registro e inicio de sesión de usuarios
- Autenticación con JWT
- Gestión de empresas / tiendas
- Gestión de productos, categorías y cupones
- Gestión de pedidos
- Integración con Mercado Pago, con webhooks firmados e idempotentes
- Activación de planes tras el pago
- Envío de correos de confirmación
- Notificaciones push al vendedor
- Base de datos PostgreSQL

## Puesta en marcha

Requisitos: **JDK 21+** y una instancia de **PostgreSQL**.

Creá la base de datos:

```sql
CREATE DATABASE fluxy_db;
```

El esquema lo genera Hibernate al arrancar (`spring.jpa.hibernate.ddl-auto=update`).

```powershell
.\mvnw.cmd spring-boot:run
```

La API queda en `http://localhost:8080`.

## Documentación interactiva de la API

Con el backend iniciado:

| Recurso | URL local |
| --- | --- |
| Swagger UI | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |
| OpenAPI JSON | [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs) |
| OpenAPI YAML | [http://localhost:8080/v3/api-docs.yaml](http://localhost:8080/v3/api-docs.yaml) |

La documentación se genera desde los controladores y DTOs e incluye descripciones
en español, ejemplos de entrada y restricciones de validación. Los endpoints se
agrupan por autenticación, perfil, empresas, productos, categorías, tienda pública,
pedidos, cupones, dashboard, pagos, dominios, notificaciones y administración.
La integración usa springdoc 3.0.x, compatible con Spring Boot 4.0.x según la
[matriz oficial](https://springdoc.org/faq.html).

### Probar endpoints con JWT

1. Abrir **Autenticación > POST /auth/login**, pulsar **Try it out** y enviar
   el correo y la contraseña de una cuenta existente.
2. Copiar el valor `token` de la respuesta.
3. Pulsar **Authorize**, pegar únicamente el JWT y confirmar. Swagger UI añade
   automáticamente `Authorization: Bearer <token>`.
4. Ejecutar los endpoints con candado. Para `/admin/**` y para crear o listar
   todas las empresas, obtener el token en `POST /auth/admin-login`.

Las rutas `/auth/**`, `/store/**` y `GET /coupons/validate` son públicas.
`POST /payments/webhook` no usa JWT; los eventos de pago requieren la firma
de Mercado Pago. La configuración de seguridad actual devuelve **403** si falta
autenticación en una ruta privada; un login con credenciales inválidas devuelve
**401**. Si se bloquean los intentos de login, devuelve **429** y `Retry-After`.

### Ejemplo: crear una preferencia de pago

`POST /payments/create-preference` requiere JWT y un usuario con empresa:

```json
{
  "plan": "PRO",
  "months": "3"
}
```

`plan` admite `PRO` o `BUSINESS`; `months` se envía como texto con un entero
de 1 a 12. Si se omiten, se usa `PRO` y `"1"`. Según `PlanPricingService`,
PRO cuesta **S/ 39.00/mes**, BUSINESS **S/ 59.00/mes**, y la moneda es **PEN**.
El ejemplo cobra **S/ 117.00**. La respuesta contiene `preferenceId`, `initPoint`
y `sandboxUrl`. El plan se activa después de verificar el pago recibido por webhook.

### Configuración y mantenimiento

- Swagger y el contrato OpenAPI son públicos mientras están habilitados.
  Definir `API_DOCS_ENABLED=false` y reiniciar deshabilita ambos.
- Si se cambia `PORT`, usar ese puerto en las URLs. En un despliegue, sustituir
  `http://localhost:8080` por la URL del backend.
- Al añadir endpoints, usar `@Tag` y `@Operation`; documentar entradas con
  `@Schema` y respuestas específicas con `@ApiResponse`.
- Marcar operaciones privadas con `@SecurityRequirement(name = "bearerAuth")`.
  En controladores con rutas públicas y privadas, anotar únicamente los métodos
  privados para evitar heredar el requisito de JWT. Estas anotaciones describen
  permisos; los permisos efectivos se configuran en `SecurityConfig`.
- Conservar los nombres JSON existentes: por ejemplo, el registro de negocio
  recibe `businesName` y `whatssapp` (también acepta el alias `whatsapp`).
- En IntelliJ IDEA, recargar el proyecto Maven después de cambiar dependencias
  para que el editor reconozca las anotaciones de Swagger.

### Variables de entorno

Todos los valores de `application.properties` se leen del entorno con la forma
`${VARIABLE:default}`. Los defaults sirven para desarrollo local; **en
producción hay que definirlas explícitamente**. Nunca guardes secretos reales
en el repositorio. Para desarrollo local, la aplicación carga automáticamente
el archivo `.env` desde la raíz del proyecto mediante
[`spring.config.import`](https://docs.spring.io/spring-boot/reference/features/external-config.html).
Este archivo está excluido de Git y Docker. Usa formato `NOMBRE=valor`, sin
comillas ni `export`; las variables de entorno del proceso tienen prioridad.
Si prefieres `src/main/resources/application-local.properties`, activa el perfil
`local` con `SPRING_PROFILES_ACTIVE=local`.

En IntelliJ, usa la raíz del proyecto como **Working directory**. Puedes guardar
la clave en `.env` con `JWT_SECRET=tu_clave_base64`, o en **Run > Edit
Configurations > Environment variables**, con **Nombre: JWT_SECRET** y
**Valor: la clave generada**. Pegar la clave como nombre de variable no configura
`JWT_SECRET`.

| Variable | Default | Descripción |
| --- | --- | --- |
| `PORT` | `8080` | Puerto del servidor |
| `API_DOCS_ENABLED` | `true` | Habilita Swagger UI y el contrato OpenAPI |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/fluxy_db` | Conexión a PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Usuario de la base |
| `SPRING_DATASOURCE_PASSWORD` | *(vacío)* | Contraseña de la base |
| `HIBERNATE_DDL_AUTO` | `update` | Estrategia de esquema |
| `JWT_SECRET` | **sin valor** | **Obligatoria siempre.** Base64 de 32 bytes o más. Sin ella la aplicación no arranca |
| `JWT_EXPIRATION_MS` | `86400000` | Vigencia del token (24 h) |
| `APP_ALLOWED_ORIGINS` | `http://localhost:5173,…` | Orígenes permitidos por CORS |
| `APP_FRONTEND_URL` | `http://localhost:5173` | URL del frontend |
| `APP_BACKEND_URL` | `http://localhost:8080` | URL pública de esta API |
| `MERCADOPAGO_ACCESS_TOKEN` | *(vacío)* | Token de Mercado Pago |
| `MERCADOPAGO_WEBHOOK_SECRET` | *(vacío)* | Secreto para validar la firma del webhook |
| `SENDGRID_API_KEY` | *(vacío)* | Envío de correos |
| `MAIL_FROM` | `notificaciones@fluxyweb.com` | Remitente |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | *(vacío)* | Notificaciones push |
| `VERCEL_TOKEN` / `VERCEL_PROJECT_ID` / `VERCEL_TEAM_ID` | *(vacío)* | Dominios personalizados |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | *(vacío)* | Credenciales del panel de administración |
| `GEMINI_API_KEY` | *(vacío)* | Descripciones generadas con IA |

`JWT_SECRET` no tiene valor por defecto a propósito. Una clave por defecto en
el repositorio permite firmar tokens válidos de cualquier usuario sin conocer su
contraseña. Si falta, la aplicación se niega a arrancar y explica cómo generarla.

Para generar una clave JWT válida:

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

## Salud del servicio

```
GET /actuator/health   →   {"status":"UP"}
```

Es público porque Render lo consulta para saber si la instancia está sana, en
lugar de limitarse a comprobar que el puerto abre. No expone detalles internos
(`show-details=never`) y el resto de `/actuator/**` queda cerrado.

## Pruebas

```powershell
.\mvnw.cmd test
```

La suite corre sobre **H2 en memoria** (modo PostgreSQL) con valores ficticios,
así que no necesita la base local ni credenciales reales.

## Despliegue en Render

El repositorio trae `render.yaml` (blueprint) y `Dockerfile`. Render no tiene
runtime nativo de Java, así que el servicio se construye con Docker.

1. En Render: **New > Blueprint** y elegir este repositorio. Detecta
   `render.yaml` y propone dos recursos: el servicio web `fluxy-backend` y la
   base `fluxy-db`.
2. Render pedirá las variables marcadas como secretas. Como mínimo:
   - `JWT_SECRET` — Base64 de 32 bytes o más. Generar con:
     ```powershell
     [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
     ```
   - `MERCADOPAGO_ACCESS_TOKEN` y `MERCADOPAGO_WEBHOOK_SECRET` si se cobran planes.
   - El resto puede quedar vacío hasta que se necesite.
3. Tras el primer despliegue, Render asigna una URL del tipo
   `https://fluxy-backend.onrender.com`. Copiarla en la variable
   `APP_BACKEND_URL` del servicio y volver a desplegar. Mercado Pago la usa
   para construir la URL de retorno y la del webhook.
4. En **Vercel**, el frontend debe apuntar al backend nuevo: definir
   `VITE_API_URL` con esa misma URL y redesplegar. Las variables `VITE_*` se
   resuelven al compilar, así que un build viejo conserva el valor anterior.

### Detalles a tener en cuenta

- **La base arranca vacía.** Con `HIBERNATE_DDL_AUTO=update` el esquema se crea
  solo, pero los datos locales no se migran. Para llevarlos:
  ```bash
  pg_dump -U postgres -h localhost fluxy_db > fluxy.sql
  psql "<External Database URL de Render>" < fluxy.sql
  ```
- **El plan gratuito suspende el servicio** tras 15 minutos sin tráfico. La
  primera petición después de dormir tarda cerca de 30 segundos, lo que en el
  login se ve como una demora larga o un tiempo de espera agotado.
- **CORS**: `APP_ALLOWED_ORIGINS` ya incluye los dominios de Vercel. Si se
  agrega otro dominio, hay que sumarlo ahí o el navegador bloqueará las
  peticiones.

## Estructura del proyecto

```
src/main/java/com/fluxyBackend
├── config          Configuración de seguridad
├── controller      Endpoints REST
├── DTOs            Objetos de request/response
├── entity          Entidades JPA
├── exception       Manejo global de errores
├── repository      Repositorios Spring Data
├── security        JWT, filtros y UserDetails
└── service         Lógica de negocio
```
