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

### Variables de entorno

Todos los valores de `application.properties` se leen del entorno con la forma
`${VARIABLE:default}`. Los defaults sirven para desarrollo local; **en
producción hay que definirlas explícitamente**. Nunca guardes secretos reales
en el repositorio: para valores locales usá `application-local.properties` o un
`.env`, ambos ignorados por git.

| Variable | Default | Descripción |
| --- | --- | --- |
| `PORT` | `8080` | Puerto del servidor |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/fluxy_db` | Conexión a PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Usuario de la base |
| `SPRING_DATASOURCE_PASSWORD` | *(vacío)* | Contraseña de la base |
| `HIBERNATE_DDL_AUTO` | `update` | Estrategia de esquema |
| `JWT_SECRET` | *(clave de desarrollo)* | **Obligatoria en producción.** Base64 de 32 bytes o más |
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

Para generar una clave JWT válida:

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

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
