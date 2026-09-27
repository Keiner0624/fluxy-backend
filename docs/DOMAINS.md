# Dominio propio de la tienda (plan Business)

Una tienda Business puede abrirse en su dominio (`mitienda.com`) en lugar de
`fluxyweb.vercel.app/store/slug`. El mismo proyecto de Vercel que sirve el frontend sirve todos los
dominios: la app mira en qué dominio se abrió y muestra esa tienda.

## Cómo funciona

1. En **Configuración → Dominio personalizado**, el negocio escribe su dominio.
2. El backend lo agrega al proyecto de Vercel (`VercelDomainClient`). Un dominio raíz se agrega junto
   con `www.` + dominio, que redirige (308) al raíz. Un subdominio (`tienda.marca.com`) va solo.
3. El panel muestra los registros DNS exactos que faltan (A `@` y CNAME `www`, o CNAME del subdominio,
   y un TXT si el dominio figura en otra cuenta de Vercel).
4. `CustomDomainJobs` revisa cada 10 minutos los dominios pendientes (durante 14 días) y una vez por día
   los activos. El botón **Verificar ahora** revisa en el momento.
5. Cuando los DNS apuntan a Vercel, el estado pasa a `ACTIVE`: Vercel emite el certificado HTTPS solo,
   el panel y los enlaces de campañas usan `https://mitienda.com`.

En el navegador, `src/app/storeHost.js` detecta que la app no está en una dirección de Fluxy, pide
`GET /store/domain?host=…` y muestra la tienda en la raíz. En ese dominio:

- `/store/slug?…` (enlaces viejos) lleva a `/?…` del mismo dominio.
- `/comprobante/:token` funciona igual que en Fluxy.
- Cualquier otra ruta (`/dashboard`, `/login`…) lleva a la dirección de Fluxy.

## Estados

| Estado | Qué significa |
|---|---|
| `PENDING_DNS` | Conectado en Vercel; faltan o no se propagaron los DNS. |
| `VERIFICATION_REQUIRED` | El dominio está en otra cuenta de Vercel: falta el TXT de verificación. |
| `ACTIVE` | La tienda se abre en el dominio, con HTTPS. |
| `ERROR` | Vercel rechazó el dominio. |

## Plan

- Conectar pide el plan Business. Ver el estado, verificar y quitar no (para poder limpiar al bajar
  de plan).
- Si el plan deja de incluir dominio propio, el dominio **queda guardado**: quien entra a él es
  redirigido a la tienda en Fluxy (con los parámetros del enlace). Al volver a Business funciona de
  nuevo sin reconfigurar.
- Al eliminar o anonimizar un negocio, el dominio se quita de Vercel.

## Seguridad

- Un dominio pertenece a una sola tienda (índice único `uk_company_custom_domain`).
- No se aceptan dominios de Fluxy ni `*.vercel.app` (ni los de `APP_FRONTEND_URL`/`APP_ALLOWED_ORIGINS`).
- CORS: el dominio de una tienda (solo por HTTPS) puede llamar **únicamente** a la API pública de la
  tienda (`/store/**` y `/coupons/validate`), que no usa sesión. El panel y el resto de la API siguen
  aceptando solo `APP_ALLOWED_ORIGINS` (`StoreCorsConfigurationSource`).

## Configuración

Backend (Render):

| Variable | Valor |
|---|---|
| `VERCEL_TOKEN` | Token de Vercel con acceso al proyecto del frontend. |
| `VERCEL_PROJECT_ID` | Project ID del frontend (Vercel → proyecto → Settings → General). |
| `VERCEL_TEAM_ID` | Solo si el proyecto está en un equipo (Team Settings → General). |
| `APP_FRONTEND_URL` | Dirección pública de Fluxy (ya configurada). |

Sin `VERCEL_TOKEN`/`VERCEL_PROJECT_ID` el panel muestra que conectar dominios no está disponible.

Frontend (Vercel): `VITE_SELLER_APP_URL` y `VITE_STORE_APP_URL` con la dirección pública de Fluxy, para
que un dominio propio sepa a dónde llevar al panel. `VITE_PLATFORM_HOSTS` (opcional, separado por
coma) agrega otras direcciones de Fluxy además de `fluxyweb.vercel.app`, `fluxyweb.com` y
`www.fluxyweb.com`.

## Probarlo en local

`http://mitienda.com.localhost:5173` simula el dominio `mitienda.com` en desarrollo (solo con
`npm run dev`). El backend local necesita un dominio conectado en la base con ese nombre.
