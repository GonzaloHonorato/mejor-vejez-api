# Base de datos · Mejor Vejez

Modelo de datos Oracle del MVP (27 tablas, un solo esquema).

## Migraciones (Flyway)

El esquema lo crea **Flyway** al arrancar la API, con las migraciones de
`src/main/resources/db/migration/`:

| Versión | Migración | Qué hace |
|---|---|---|
| V1 | `V1__tablas.sql` | Crea las 27 tablas con sus PK, FK, UNIQUE y CHECK. |
| V2 | `V2__catalogos.sql` | Carga estados, roles de grupo y opciones de negocio con `MERGE`. |
| V3 | `V3__indices_y_triggers.sql` | Índices de consulta, índices únicos basados en función (leen IDs del catálogo) y triggers de inmutabilidad. |

Flyway registra lo aplicado en la tabla `flyway_schema_history` y nunca vuelve a
ejecutar una versión. **No edites una migración ya aplicada:** cualquier cambio
al esquema va en una nueva (`V4__...sql`). Las pruebas
(`MigracionesFlywayTest`) aplican las tres migraciones en un Oracle Free limpio
con Testcontainers.

## Scripts de esta carpeta (manuales, solo desarrollo)

| Script | Qué hace | Reejecutable |
|---|---|---|
| `datos_demo.sql` | Datos ficticios para probar los flujos. Opcional; ejecutar después de que Flyway aplique las migraciones. | Sí (no duplica) |
| `reiniciar_esquema.sql` | Elimina las 27 tablas, sus datos y `flyway_schema_history`, para que Flyway recree el esquema desde V1 en el próximo arranque. **Destructivo; nunca en producción.** | Sí (destructivo) |

Se ejecutan con SQL Developer, SQLcl o la consola SQL de Oracle Cloud, conectado
al esquema de la aplicación.

Antes de `datos_demo.sql`, reemplaza en el bloque `DECLARE` los valores `REEMPLAZAR_UID_*` por los **uid de Firebase** de tres cuentas de prueba (consola de Firebase → Authentication → Users). Si no lo haces, esos usuarios no podrán iniciar sesión.

## Decisiones del modelo que conviene conocer

- **Usuarios:** se identifican por `UID_FIREBASE` (Firebase Auth). El superadministrador es `USUARIO.ES_ADMINISTRADOR = 'S'`; los roles por grupo son solo COORDINADOR y COLABORADOR.
- **Horario de cada medicamento:** son filas en `DETALLE_PLAN_HORARIO`, con `TIMESTAMP` sin zona y fecha fija `1970-01-01`. Representan la hora de reloj en America/Santiago.
- **Medicamentos:** son un catálogo global, sin duplicados aunque cambien las mayúsculas o los espacios.
- **Grupo coherente:** las tablas operativas repiten `ID_GRUPO`, pero amarrado con FK compuestas `(ID_ADULTO_MAYOR, ID_GRUPO)`. Así la base de datos rechaza datos cruzados entre familias.
- **Auditoría:** `EVENTO_AUDITORIA` y `HISTORIAL_ESTADO_TAREA` son de solo inserción (triggers).
- **Estados:** todos los estados son IDs de `CATALOGO_ESTADOS`. El backend valida que cada ID corresponda a su tabla y campo.

## Relación con JPA

Este esquema lo crea Flyway, no Hibernate. `JPA_DDL_AUTO` vale `validate` por
defecto: Hibernate solo verifica que las entidades calcen con el esquema y no
lo modifica.
