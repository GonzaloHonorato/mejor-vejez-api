-- =====================================================================
-- reiniciar_esquema.sql
-- SOLO DESARROLLO. Deja el esquema vacío para que Flyway vuelva a aplicar
-- las migraciones desde V1 al próximo arranque de la API.
--
-- ADVERTENCIA: elimina las 27 tablas del modelo, sus datos y el historial
-- de Flyway. Nunca ejecutarlo contra la base de producción.
-- Incluye nombres de la versión v11 ya reemplazados.
-- =====================================================================

SET SERVEROUTPUT ON;

DECLARE
  PROCEDURE eliminar_si_existe(p_tabla VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE ' || DBMS_ASSERT.SIMPLE_SQL_NAME(p_tabla) || ' CASCADE CONSTRAINTS PURGE';
    DBMS_OUTPUT.PUT_LINE('Eliminada: ' || p_tabla);
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE != -942 THEN
        RAISE;
      END IF;
  END;
BEGIN
  -- Historial de Flyway: la tabla se llama "flyway_schema_history", en
  -- minúsculas, así que el nombre va entre comillas dobles.
  eliminar_si_existe('"flyway_schema_history"');
  eliminar_si_existe('EVENTO_AUDITORIA');
  eliminar_si_existe('DETALLE_TRASPASO');
  eliminar_si_existe('TRASPASO_FAMILIAR');
  eliminar_si_existe('INTENTO_NOTIFICACION');
  eliminar_si_existe('ALERTA_CUIDADO');
  eliminar_si_existe('DETALLE_CITA_MEDICA');
  eliminar_si_existe('DETALLE_COMPRA_MEDICAMENTOS');
  eliminar_si_existe('ASIGNACION_TAREA');
  eliminar_si_existe('HISTORIAL_ESTADO_TAREA');
  eliminar_si_existe('OCURRENCIA_PLANIFICADA');
  eliminar_si_existe('TAREA_CUIDADO');
  eliminar_si_existe('EJECUCION_PROCESO');
  eliminar_si_existe('EJECUCION_GENERACION');              -- v11
  eliminar_si_existe('DETALLE_PLAN_HORARIO');
  eliminar_si_existe('DETALLE_PLAN_DIA');
  eliminar_si_existe('DETALLE_PLAN');
  eliminar_si_existe('PLAN_MEDICAMENTOS');
  eliminar_si_existe('CONTACTO_EMERGENCIA');
  eliminar_si_existe('ADULTO_MAYOR');
  eliminar_si_existe('PROPUESTA_SUCESION');
  eliminar_si_existe('INTEGRANTE_GRUPO');
  eliminar_si_existe('ROL_GRUPO');
  eliminar_si_existe('GRUPO_FAMILIAR');
  eliminar_si_existe('CANAL_NOTIFICACION_USUARIO');
  eliminar_si_existe('USUARIO');
  eliminar_si_existe('MEDICAMENTO');
  eliminar_si_existe('CATALOGO_FRECUENCIAS_MEDICAMENTO');  -- v11 (INC-03)
  eliminar_si_existe('CATALOGO_NEGOCIO');
  eliminar_si_existe('CATALOGO_ESTADOS');
END;
/
