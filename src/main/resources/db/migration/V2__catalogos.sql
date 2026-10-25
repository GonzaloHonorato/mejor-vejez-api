-- =====================================================================
-- V2__catalogos.sql
-- Carga de catálogos: estados, roles de grupo y opciones de negocio.
-- Migración Flyway (origen: database/02_catalogos.sql).
--
-- Usa MERGE: solo inserta lo que falta y actualiza descripción y orden de
-- lo existente. No borra nada. Va antes de V3 porque los índices basados en
-- función leen IDs de CATALOGO_ESTADOS.
-- =====================================================================

DECLARE
  PROCEDURE estado(p_tabla VARCHAR2, p_campo VARCHAR2, p_codigo VARCHAR2,
                   p_descripcion VARCHAR2, p_orden NUMBER) IS
  BEGIN
    MERGE INTO CATALOGO_ESTADOS c
    USING (SELECT p_tabla AS t, p_campo AS f, p_codigo AS k FROM DUAL) s
       ON (c.NOMBRE_TABLA = s.t AND c.NOMBRE_CAMPO = s.f AND c.CODIGO_ESTADO = s.k)
     WHEN MATCHED THEN UPDATE SET c.DESCRIPCION = p_descripcion, c.ORDEN = p_orden
     WHEN NOT MATCHED THEN INSERT (NOMBRE_TABLA, NOMBRE_CAMPO, CODIGO_ESTADO, DESCRIPCION, ORDEN)
                           VALUES (p_tabla, p_campo, p_codigo, p_descripcion, p_orden);
  END;

  PROCEDURE opcion(p_tipo VARCHAR2, p_codigo VARCHAR2, p_descripcion VARCHAR2, p_orden NUMBER) IS
  BEGIN
    MERGE INTO CATALOGO_NEGOCIO c
    USING (SELECT p_tipo AS t, p_codigo AS k FROM DUAL) s
       ON (c.TIPO_CATALOGO = s.t AND c.CODIGO = s.k)
     WHEN MATCHED THEN UPDATE SET c.DESCRIPCION = p_descripcion, c.ORDEN = p_orden
     WHEN NOT MATCHED THEN INSERT (TIPO_CATALOGO, CODIGO, DESCRIPCION, ORDEN)
                           VALUES (p_tipo, p_codigo, p_descripcion, p_orden);
  END;

  PROCEDURE rol(p_nombre VARCHAR2, p_descripcion VARCHAR2) IS
  BEGIN
    MERGE INTO ROL_GRUPO r
    USING (SELECT p_nombre AS n FROM DUAL) s
       ON (r.NOMBRE_ROL = s.n)
     WHEN MATCHED THEN UPDATE SET r.DESCRIPCION = p_descripcion
     WHEN NOT MATCHED THEN INSERT (NOMBRE_ROL, DESCRIPCION) VALUES (p_nombre, p_descripcion);
  END;
BEGIN
  -- -------------------------------------------------------------------
  -- Estados por tabla/campo
  -- -------------------------------------------------------------------
  estado('USUARIO', 'ESTADO', 'ACTIVO',    'Activo',    1);
  estado('USUARIO', 'ESTADO', 'INACTIVO',  'Inactivo',  2);
  estado('USUARIO', 'ESTADO', 'BLOQUEADO', 'Bloqueado', 3);

  estado('GRUPO_FAMILIAR', 'ESTADO', 'ACTIVO',   'Activo',   1);
  estado('GRUPO_FAMILIAR', 'ESTADO', 'INACTIVO', 'Inactivo', 2);

  estado('INTEGRANTE_GRUPO', 'ESTADO', 'ACTIVO',           'Activo',           1);
  estado('INTEGRANTE_GRUPO', 'ESTADO', 'INACTIVO',         'Inactivo',         2);
  estado('INTEGRANTE_GRUPO', 'ESTADO', 'SALIDA_PENDIENTE', 'Salida pendiente', 3);

  estado('PROPUESTA_SUCESION', 'ESTADO', 'PENDIENTE',  'Pendiente',  1);
  estado('PROPUESTA_SUCESION', 'ESTADO', 'ACEPTADA',   'Aceptada',   2);
  estado('PROPUESTA_SUCESION', 'ESTADO', 'RECHAZADA',  'Rechazada',  3);
  estado('PROPUESTA_SUCESION', 'ESTADO', 'CANCELADA',  'Cancelada',  4);
  estado('PROPUESTA_SUCESION', 'ESTADO', 'COMPLETADA', 'Completada', 5);

  estado('ADULTO_MAYOR', 'ESTADO', 'ACTIVO',   'Activo',   1);
  estado('ADULTO_MAYOR', 'ESTADO', 'INACTIVO', 'Inactivo', 2);

  estado('CONTACTO_EMERGENCIA', 'ESTADO', 'ACTIVO',   'Activo',   1);
  estado('CONTACTO_EMERGENCIA', 'ESTADO', 'INACTIVO', 'Inactivo', 2);

  estado('PLAN_MEDICAMENTOS', 'ESTADO', 'ACTIVO',   'Activo',   1);
  estado('PLAN_MEDICAMENTOS', 'ESTADO', 'INACTIVO', 'Inactivo', 2);

  estado('DETALLE_PLAN', 'ESTADO', 'ACTIVO',   'Activo',   1);
  estado('DETALLE_PLAN', 'ESTADO', 'INACTIVO', 'Inactivo', 2);

  -- INC-07: HISTORIAL_ESTADO_TAREA usa también este dominio.
  estado('TAREA_CUIDADO', 'ESTADO', 'PENDIENTE',    'Pendiente',    1);
  estado('TAREA_CUIDADO', 'ESTADO', 'EN_CURSO',     'En curso',     2);
  estado('TAREA_CUIDADO', 'ESTADO', 'COMPLETADA',   'Completada',   3);
  estado('TAREA_CUIDADO', 'ESTADO', 'NO_REALIZADA', 'No realizada', 4);
  estado('TAREA_CUIDADO', 'ESTADO', 'CANCELADA',    'Cancelada',    5);

  estado('OCURRENCIA_PLANIFICADA', 'ESTADO', 'PLANIFICADA',  'Planificada',  1);
  estado('OCURRENCIA_PLANIFICADA', 'ESTADO', 'TAREA_CREADA', 'Tarea creada', 2);
  estado('OCURRENCIA_PLANIFICADA', 'ESTADO', 'CANCELADA',    'Cancelada',    3);
  estado('OCURRENCIA_PLANIFICADA', 'ESTADO', 'ERROR',        'Error',        4);

  -- INC-21
  estado('EJECUCION_PROCESO', 'ESTADO', 'INICIADA', 'Iniciada', 1);
  estado('EJECUCION_PROCESO', 'ESTADO', 'EXITOSA',  'Exitosa',  2);
  estado('EJECUCION_PROCESO', 'ESTADO', 'PARCIAL',  'Parcial',  3);
  estado('EJECUCION_PROCESO', 'ESTADO', 'FALLIDA',  'Fallida',  4);

  estado('ASIGNACION_TAREA', 'ESTADO', 'ACTIVO',      'Activo',      1);
  estado('ASIGNACION_TAREA', 'ESTADO', 'REEMPLAZADA', 'Reemplazada', 2);
  estado('ASIGNACION_TAREA', 'ESTADO', 'CANCELADA',   'Cancelada',   3);

  estado('ALERTA_CUIDADO', 'ESTADO', 'ABIERTA', 'Abierta', 1);
  estado('ALERTA_CUIDADO', 'ESTADO', 'CERRADA', 'Cerrada', 2);

  estado('INTENTO_NOTIFICACION', 'ESTADO_ENVIO', 'PENDIENTE', 'Pendiente', 1);
  estado('INTENTO_NOTIFICACION', 'ESTADO_ENVIO', 'ENVIADO',   'Enviado',   2);
  estado('INTENTO_NOTIFICACION', 'ESTADO_ENVIO', 'FALLIDO',   'Fallido',   3);

  estado('TRASPASO_FAMILIAR', 'ESTADO', 'BORRADOR',   'Borrador',   1);
  estado('TRASPASO_FAMILIAR', 'ESTADO', 'COMPLETADA', 'Completada', 2);
  estado('TRASPASO_FAMILIAR', 'ESTADO', 'CANCELADA',  'Cancelada',  3);

  -- INC-17: el campo se cataloga como RESULTADO.
  estado('EVENTO_AUDITORIA', 'RESULTADO', 'EXITO',     'Éxito',     1);
  estado('EVENTO_AUDITORIA', 'RESULTADO', 'FALLO',     'Fallo',     2);
  estado('EVENTO_AUDITORIA', 'RESULTADO', 'RECHAZADA', 'Rechazada', 3);

  -- -------------------------------------------------------------------
  -- Roles por grupo (INC-02: ADMINISTRADOR es global, vive en USUARIO)
  -- -------------------------------------------------------------------
  rol('COORDINADOR', 'Administra el grupo, sus integrantes y la coordinación del cuidado');
  rol('COLABORADOR', 'Consulta información autorizada y ejecuta las tareas asignadas');

  -- -------------------------------------------------------------------
  -- Opciones de negocio (INC-16)
  -- -------------------------------------------------------------------
  opcion('PRESENTACION_MEDICAMENTO', 'COMPRIMIDO',    'Comprimidos',   1);
  opcion('PRESENTACION_MEDICAMENTO', 'CAPSULA',       'Cápsulas',      2);
  opcion('PRESENTACION_MEDICAMENTO', 'GOTAS',         'Gotas',         3);
  opcion('PRESENTACION_MEDICAMENTO', 'JARABE',        'Jarabe',        4);
  opcion('PRESENTACION_MEDICAMENTO', 'INYECTABLE',    'Inyectable',    5);
  opcion('PRESENTACION_MEDICAMENTO', 'SOLUCION_ORAL', 'Solución oral', 6);

  opcion('UNIDAD_DOSIS', 'COMPRIMIDO', 'Comprimido',       1);
  opcion('UNIDAD_DOSIS', 'CAPSULA',    'Cápsula',          2);
  opcion('UNIDAD_DOSIS', 'GOTA',       'Gota',             3);
  opcion('UNIDAD_DOSIS', 'ML',         'Mililitro (ml)',   4);
  opcion('UNIDAD_DOSIS', 'UNIDAD',     'Unidad',           5);
  opcion('UNIDAD_DOSIS', 'MG',         'Miligramo (mg)',   6);
  opcion('UNIDAD_DOSIS', 'MCG',        'Microgramo (mcg)', 7);
  opcion('UNIDAD_DOSIS', 'UI',         'Unidad internacional (UI)', 8);

  opcion('DIA_SEMANA', 'LUNES',     'Lunes',     1);
  opcion('DIA_SEMANA', 'MARTES',    'Martes',    2);
  opcion('DIA_SEMANA', 'MIERCOLES', 'Miércoles', 3);
  opcion('DIA_SEMANA', 'JUEVES',    'Jueves',    4);
  opcion('DIA_SEMANA', 'VIERNES',   'Viernes',   5);
  opcion('DIA_SEMANA', 'SABADO',    'Sábado',    6);
  opcion('DIA_SEMANA', 'DOMINGO',   'Domingo',   7);

  COMMIT;
END;
/
