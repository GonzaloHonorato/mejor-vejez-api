# mejor-vejez-api

API de **Mejor Vejez** — Spring Boot 4.1 · Java 21 · Oracle Autonomous DB (wallet) ·
Firebase Auth. Producción: `https://mejor-vejez-api.adndigital.cl`.

Fase 0: esquema completo con Flyway (usuarios, catálogos, auditoría…), alta
del usuario en su primer ingreso, catálogos para el front y errores uniformes
en español (`ProblemDetail` con `idCorrelacion`).

## Endpoints

| Método | Ruta | Auth | Respuesta |
| --- | --- | --- | --- |
| GET | `/api/salud` | pública | `200` siempre que el proceso viva; el estado de la BD va en el cuerpo |

```json
{ "estado": "DISPONIBLE | DEGRADADO", "servicio": "mejor-vejez-api", "version": "0.0.1-SNAPSHOT",
  "fechaHora": "…", "baseDatos": { "estado": "DISPONIBLE | NO_DISPONIBLE", "latenciaMs": 18 } }
```

| GET | `/api/yo` | token | `200` con el usuario autenticado; lo da de alta en su primer ingreso |
| GET | `/api/catalogos/estados?tabla=&campo=` | token | `200` con los estados de ese campo, p. ej. `?tabla=USUARIO&campo=ESTADO` |
| GET | `/api/catalogos/opciones?tipo=` | token | `200` con las opciones del tipo (`PRESENTACION_MEDICAMENTO`, `UNIDAD_DOSIS`, `DIA_SEMANA`) |

Todo lo que no es `/api/salud` exige `Authorization: Bearer <ID token de Firebase>` → `401` sin él.

### `GET /api/yo`

```json
{ "idUsuario": 1, "nombre": "Ana Pérez", "email": "ana@ejemplo.cl", "esAdministrador": false }
```

| Código | Cuándo |
| --- | --- |
| `401` | sin token, token inválido o sin `sub` |
| `403` | la cuenta no está `ACTIVO` (bloqueada o inactiva) |
| `409` | primer ingreso con un correo que ya pertenece a otra cuenta |
| `422` | primer ingreso de una cuenta sin correo |

### Catálogos

`estados` y `opciones` devuelven solo los registros activos, ordenados por `orden` (lista vacía si no hay), para armar selects:
`[{ "idEstado": 1, "codigo": "ACTIVO", "descripcion": "Activo", "orden": 1 }]`
(en `opciones` el id es `idOpcion`). Falta un parámetro o `tipo` inválido → `400`.

### Errores

Todo error responde `application/problem+json` en español, con `title`,
`detail` e `idCorrelacion` (el mismo valor que la cabecera `X-Id-Correlacion`,
que el cliente puede enviar y leer). Nunca incluye mensajes de Oracle ni
nombres internos.

## Auth con Firebase

El ID token se valida como JWT (`spring-boot-starter-security-oauth2-resource-server`):
firma con las llaves públicas de Google, `iss = https://securetoken.google.com/<project>`
y `aud = <project>`. Sólo hace falta `FIREBASE_PROJECT_ID`; **no** se necesita cuenta de
servicio. En un controller, el usuario sale de `@AuthenticationPrincipal Jwt jwt`
(`jwt.getSubject()` = uid de Firebase, `jwt.getClaimAsString("email")`).

## Variables

| Variable | Obligatoria | Uso |
| --- | --- | --- |
| `ORACLE_TNS_ALIAS` | sí | alias del `tnsnames.ora` del wallet, p. ej. `mejorvejez_high` |
| `ORACLE_USERNAME` / `ORACLE_PASSWORD` | sí | usuario de la BD |
| `ORACLE_WALLET_BASE64` | sí (contenedor) | wallet en base64 (zip de Oracle o tar.gz) |
| `FIREBASE_PROJECT_ID` | sí | project ID de Firebase |
| `CORS_ORIGINS` | no | orígenes separados por coma (def. `https://mejor-vejez-app.adndigital.cl`) |
| `JPA_DDL_AUTO` | no | def. `validate`: el esquema lo crea Flyway (`src/main/resources/db/migration`) y Hibernate solo lo valida. Flyway corre al arrancar, así que la API **exige** la BD para levantar |

### Wallet en base64

```sh
# El zip tal como lo descarga Oracle sirve directo:
base64 -i Wallet_MEJORVEJEZ.zip | tr -d '\n' | pbcopy
```

`entrypoint.sh` lo decodifica al arrancar en `/app/wallet` (detecta zip o tar.gz).

## Desarrollo local

> ⚠️ **Nunca** uses el wallet ni la Oracle Autonomous DB (ADB) de producción
> para desarrollar o probar en tu máquina. Para eso existe este Oracle Free
> local en Docker — el wallet (`.env`, `Wallet_*`) es solo para producción.

1. Levantar el Oracle Free local (`docker-compose.local.yml`, servicio
   `oracle-local`, solo para desarrollo). Con Colima:

   ```sh
   DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
     docker compose -f docker-compose.local.yml up -d
   ```

   Si no tienes el plugin `docker compose` (Compose V2) instalado, usa el
   binario clásico `docker-compose` con el mismo flag `-f`. Si no tienes
   ninguno de los dos, no instales nada: levanta el contenedor equivalente a
   mano con `docker run` (mismo `DOCKER_HOST`):

   ```sh
   DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
     docker run -d --name oracle-local \
       -e ORACLE_PASSWORD=oracle_local_dev \
       -e APP_USER=mejorvejez -e APP_USER_PASSWORD=mejorvejez \
       -p 127.0.0.1:1521:1521 -v oracle-local-data:/opt/oracle/oradata \
       gvenzl/oracle-free:23-slim-faststart
   ```

   Espera a que quede `healthy` (con `docker compose`/`docker-compose`:
   `docker ps`; con `docker run`: `docker inspect -f '{{.State.Health.Status}}' oracle-local`).

2. Arrancar la API contra ese Oracle local:

   ```sh
   cp .env.local.example .env.local      # .env.local está en .gitignore
   set -a; source .env.local; set +a
   ./mvnw spring-boot:run                 # http://localhost:8080/api/salud
   ```

   Flyway crea el esquema (3 migraciones) en el Oracle local al arrancar.

3. Datos demo (opcional), después de que Flyway haya corrido:

   ```sh
   docker exec -i oracle-local sqlplus -s mejorvejez/mejorvejez@localhost/FREEPDB1 \
     < database/datos_demo.sql
   ```

   (reemplaza antes los `REEMPLAZAR_UID_*` del script, ver `database/README.md`).

```sh
./mvnw test
```

Las pruebas de integración levantan un Oracle Free real con Testcontainers
(imagen `gvenzl/oracle-free:23-slim-faststart`, independiente del
`oracle-local` de arriba) y nunca usan el wallet. Con Colima:

```sh
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw test
```

## Despliegue en Dokploy

App de tipo **Compose** apuntando a este repo, archivo `docker-compose.yml`.

1. **Environment:** las variables de arriba.
2. **Domains:** servicio `mejor-vejez-api`, puerto `8080`, host
   `mejor-vejez-api.adndigital.cl`, HTTPS (Let's Encrypt).
3. Deploy. Verificar: `curl https://mejor-vejez-api.adndigital.cl/api/salud`.
