-- =====================================================================
-- V4__corrige_indice_codigo_adulto.sql
-- Corrige UQ_ADULTO_GRUPO_CODIGO (V1): un UNIQUE compuesto normal en Oracle
-- SÍ compara los NULL de CODIGO_INTERNO como iguales entre sí mientras el
-- otro componente (ID_GRUPO, NOT NULL) exista, así que dos adultos del
-- mismo grupo sin código ("código en blanco" normalizado a NULL por
-- SolicitudGuardarAdultoMayor) chocaban con ORA-00001 — al revés de lo que
-- pide la regla de negocio ("dos adultos sin código no chocan").
--
-- Se reemplaza por un índice único basado en función (mismo patrón que
-- UX_MEDICAMENTO_NORMALIZADO / UX_CONTACTO_PRINCIPAL_ACTIVO, V3): cuando
-- CODIGO_INTERNO es NULL, ambas expresiones son NULL y Oracle no indexa la
-- fila (todas las columnas de la clave son NULL), así que no hay choque;
-- cuando CODIGO_INTERNO no es NULL, indexa (ID_GRUPO, CODIGO_INTERNO) de
-- verdad y sí exige unicidad. Se conserva el nombre UQ_ADULTO_GRUPO_CODIGO.
-- =====================================================================

ALTER TABLE ADULTO_MAYOR DROP CONSTRAINT UQ_ADULTO_GRUPO_CODIGO;

CREATE UNIQUE INDEX UQ_ADULTO_GRUPO_CODIGO ON ADULTO_MAYOR (
  CASE WHEN CODIGO_INTERNO IS NOT NULL THEN ID_GRUPO END,
  CASE WHEN CODIGO_INTERNO IS NOT NULL THEN CODIGO_INTERNO END
);
