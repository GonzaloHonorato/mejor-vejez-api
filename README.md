# mejor-vejez-api

API de **Mejor Vejez** — Spring Boot 4.1 · Java 21 · Oracle Autonomous DB (wallet) ·
Firebase Auth. Producción: `https://mejor-vejez-api.adndigital.cl`.

Boilerplate: todavía **sin entidades**. Sólo expone el health.

## Endpoints

| Método | Ruta | Auth | Respuesta |
| --- | --- | --- | --- |
| GET | `/api/health` | pública | `200` siempre que el proceso viva; el estado de la BD va en el cuerpo |

```json
{ "status": "UP | DEGRADED", "service": "mejor-vejez-api", "version": "0.0.1-SNAPSHOT",
  "timestamp": "…", "database": { "status": "UP | DOWN", "latencyMs": 18 } }
```

Todo lo demás exige `Authorization: Bearer <ID token de Firebase>` → `401` sin él.

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
| `JPA_DDL_AUTO` | no | def. `update` (con `update` la API **exige** la BD al arrancar) |

### Wallet en base64

```sh
# El zip tal como lo descarga Oracle sirve directo:
base64 -i Wallet_MEJORVEJEZ.zip | tr -d '\n' | pbcopy
```

`entrypoint.sh` lo decodifica al arrancar en `/app/wallet` (detecta zip o tar.gz).

## Desarrollo local

```sh
unzip Wallet_MEJORVEJEZ.zip -d wallet      # ./wallet está en .gitignore
cp .env.example .env                        # y completar
set -a; source .env; set +a
./mvnw spring-boot:run                      # http://localhost:8080/api/health
./mvnw test
```

## Despliegue en Dokploy

App de tipo **Compose** apuntando a este repo, archivo `docker-compose.yml`.

1. **Environment:** las variables de arriba.
2. **Domains:** servicio `mejor-vejez-api`, puerto `8080`, host
   `mejor-vejez-api.adndigital.cl`, HTTPS (Let's Encrypt).
3. Deploy. Verificar: `curl https://mejor-vejez-api.adndigital.cl/api/health`.
