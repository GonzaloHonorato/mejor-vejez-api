-- =====================================================================
-- datos_demo.sql
-- Datos de demostración para probar los flujos del MVP. OPCIONAL.
--
-- Todos los datos son ficticios. Medicamentos y horarios son ejemplos de
-- estructura, NO prescripciones ni indicaciones clínicas.
--
-- ANTES DE EJECUTAR: reemplazar los UID_FIREBASE de abajo por los uid reales
-- de las cuentas de prueba creadas en Firebase Auth (consola de Firebase →
-- Authentication → Users). Sin eso, esos usuarios no podrán iniciar sesión.
--
-- Protegido contra doble ejecución: si ya existe el admin demo, no hace nada.
-- Las tareas usan fechas relativas a SYSTIMESTAMP para probar estados.
--
-- Nota técnica: las funciones locales de un bloque PL/SQL no se pueden usar
-- dentro de sentencias SQL (PLS-00231), por eso todos los IDs de catálogo se
-- resuelven primero en variables.
-- =====================================================================

SET SERVEROUTPUT ON;

DECLARE
  -- uid de Firebase de las cuentas de prueba (REEMPLAZAR)
  c_uid_admin  CONSTANT VARCHAR2(128) := 'REEMPLAZAR_UID_ADMIN';
  c_uid_coord  CONSTANT VARCHAR2(128) := 'REEMPLAZAR_UID_COORDINADORA';
  c_uid_colab  CONSTANT VARCHAR2(128) := 'REEMPLAZAR_UID_COLABORADOR';

  v_existe  NUMBER;

  -- Estados
  e_usuario_activo      NUMBER;
  e_grupo_activo        NUMBER;
  e_integrante_activo   NUMBER;
  e_adulto_activo       NUMBER;
  e_contacto_activo     NUMBER;
  e_plan_activo         NUMBER;
  e_detalle_activo      NUMBER;
  e_tarea_pendiente     NUMBER;
  e_tarea_en_curso      NUMBER;
  e_tarea_completada    NUMBER;
  e_ocurrencia_creada   NUMBER;
  e_asignacion_activa   NUMBER;
  e_asignacion_reempl   NUMBER;
  e_alerta_abierta      NUMBER;
  e_envio_fallido       NUMBER;
  e_propuesta_pendiente NUMBER;
  e_traspaso_borrador   NUMBER;
  e_ejecucion_exitosa   NUMBER;
  e_auditoria_exito     NUMBER;

  -- Opciones de catálogo y roles
  o_pres_comprimido     NUMBER;
  o_pres_capsula        NUMBER;
  o_pres_solucion_oral  NUMBER;
  o_unid_comprimido     NUMBER;
  o_unid_capsula        NUMBER;
  o_unid_ml             NUMBER;
  o_dia_lunes           NUMBER;
  o_dia_miercoles       NUMBER;
  o_dia_viernes         NUMBER;
  r_coordinador         NUMBER;
  r_colaborador         NUMBER;

  -- Horas de administración (INC-03: TIMESTAMP con fecha fija 1970-01-01)
  h_0800  TIMESTAMP := TIMESTAMP '1970-01-01 08:00:00';
  h_0900  TIMESTAMP := TIMESTAMP '1970-01-01 09:00:00';
  h_1600  TIMESTAMP := TIMESTAMP '1970-01-01 16:00:00';
  h_2000  TIMESTAMP := TIMESTAMP '1970-01-01 20:00:00';
  h_2200  TIMESTAMP := TIMESTAMP '1970-01-01 22:00:00';

  -- Registros creados
  v_admin       NUMBER;
  v_coord       NUMBER;
  v_colab       NUMBER;
  v_grupo       NUMBER;
  v_adulto1     NUMBER;
  v_adulto2     NUMBER;
  v_med1        NUMBER;
  v_med2        NUMBER;
  v_med3        NUMBER;
  v_plan1       NUMBER;
  v_plan2       NUMBER;
  v_det1        NUMBER;
  v_det2        NUMBER;
  v_det3        NUMBER;
  v_tarea1      NUMBER;
  v_tarea2      NUMBER;
  v_tarea3      NUMBER;
  v_tarea_cita  NUMBER;
  v_alerta      NUMBER;
  v_traspaso    NUMBER;

  FUNCTION est(p_tabla VARCHAR2, p_codigo VARCHAR2, p_campo VARCHAR2 DEFAULT 'ESTADO') RETURN NUMBER IS
    v_id NUMBER;
  BEGIN
    SELECT ID_ESTADO INTO v_id FROM CATALOGO_ESTADOS
     WHERE NOMBRE_TABLA = p_tabla AND NOMBRE_CAMPO = p_campo AND CODIGO_ESTADO = p_codigo;
    RETURN v_id;
  END;

  FUNCTION opc(p_tipo VARCHAR2, p_codigo VARCHAR2) RETURN NUMBER IS
    v_id NUMBER;
  BEGIN
    SELECT ID_OPCION INTO v_id FROM CATALOGO_NEGOCIO
     WHERE TIPO_CATALOGO = p_tipo AND CODIGO = p_codigo;
    RETURN v_id;
  END;

  FUNCTION rol(p_nombre VARCHAR2) RETURN NUMBER IS
    v_id NUMBER;
  BEGIN
    SELECT ID_ROL INTO v_id FROM ROL_GRUPO WHERE NOMBRE_ROL = p_nombre;
    RETURN v_id;
  END;

  -- Inserta todos los días de la semana para un detalle.
  PROCEDURE todos_los_dias(p_detalle NUMBER) IS
  BEGIN
    INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA)
    SELECT p_detalle, ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = 'DIA_SEMANA';
  END;

  PROCEDURE agregar_hora(p_detalle NUMBER, p_hora TIMESTAMP) IS
  BEGIN
    INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) VALUES (p_detalle, p_hora);
  END;
BEGIN
  SELECT COUNT(*) INTO v_existe FROM USUARIO WHERE EMAIL = 'admin.demo@mejorvejez.cl';
  IF v_existe > 0 THEN
    DBMS_OUTPUT.PUT_LINE('datos_demo.sql: los datos demo ya existen; no se hace nada.');
    RETURN;
  END IF;

  -- -------------------------------------------------------------------
  -- 0. Resolver IDs de catálogo.
  -- -------------------------------------------------------------------
  e_usuario_activo      := est('USUARIO', 'ACTIVO');
  e_grupo_activo        := est('GRUPO_FAMILIAR', 'ACTIVO');
  e_integrante_activo   := est('INTEGRANTE_GRUPO', 'ACTIVO');
  e_adulto_activo       := est('ADULTO_MAYOR', 'ACTIVO');
  e_contacto_activo     := est('CONTACTO_EMERGENCIA', 'ACTIVO');
  e_plan_activo         := est('PLAN_MEDICAMENTOS', 'ACTIVO');
  e_detalle_activo      := est('DETALLE_PLAN', 'ACTIVO');
  e_tarea_pendiente     := est('TAREA_CUIDADO', 'PENDIENTE');
  e_tarea_en_curso      := est('TAREA_CUIDADO', 'EN_CURSO');
  e_tarea_completada    := est('TAREA_CUIDADO', 'COMPLETADA');
  e_ocurrencia_creada   := est('OCURRENCIA_PLANIFICADA', 'TAREA_CREADA');
  e_asignacion_activa   := est('ASIGNACION_TAREA', 'ACTIVO');
  e_asignacion_reempl   := est('ASIGNACION_TAREA', 'REEMPLAZADA');
  e_alerta_abierta      := est('ALERTA_CUIDADO', 'ABIERTA');
  e_envio_fallido       := est('INTENTO_NOTIFICACION', 'FALLIDO', 'ESTADO_ENVIO');
  e_propuesta_pendiente := est('PROPUESTA_SUCESION', 'PENDIENTE');
  e_traspaso_borrador   := est('TRASPASO_FAMILIAR', 'BORRADOR');
  e_ejecucion_exitosa   := est('EJECUCION_PROCESO', 'EXITOSA');
  e_auditoria_exito     := est('EVENTO_AUDITORIA', 'EXITO', 'RESULTADO');

  o_pres_comprimido     := opc('PRESENTACION_MEDICAMENTO', 'COMPRIMIDO');
  o_pres_capsula        := opc('PRESENTACION_MEDICAMENTO', 'CAPSULA');
  o_pres_solucion_oral  := opc('PRESENTACION_MEDICAMENTO', 'SOLUCION_ORAL');
  o_unid_comprimido     := opc('UNIDAD_DOSIS', 'COMPRIMIDO');
  o_unid_capsula        := opc('UNIDAD_DOSIS', 'CAPSULA');
  o_unid_ml             := opc('UNIDAD_DOSIS', 'ML');
  o_dia_lunes           := opc('DIA_SEMANA', 'LUNES');
  o_dia_miercoles       := opc('DIA_SEMANA', 'MIERCOLES');
  o_dia_viernes         := opc('DIA_SEMANA', 'VIERNES');
  r_coordinador         := rol('COORDINADOR');
  r_colaborador         := rol('COLABORADOR');

  -- -------------------------------------------------------------------
  -- 1. Usuarios: superadmin (sin grupo), coordinadora y colaborador.
  -- -------------------------------------------------------------------
  INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, TELEFONO, ES_ADMINISTRADOR, ID_ESTADO)
  VALUES (c_uid_admin, 'admin.demo@mejorvejez.cl', 'Administración Demo', '+56911110001', 'S', e_usuario_activo)
  RETURNING ID_USUARIO INTO v_admin;

  INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, TELEFONO, ES_ADMINISTRADOR, ID_ESTADO)
  VALUES (c_uid_coord, 'camila.demo@mejorvejez.cl', 'Camila Coordinadora Demo', '+56911110002', 'N', e_usuario_activo)
  RETURNING ID_USUARIO INTO v_coord;

  INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, TELEFONO, ES_ADMINISTRADOR, ID_ESTADO)
  VALUES (c_uid_colab, 'diego.demo@mejorvejez.cl', 'Diego Colaborador Demo', '+56911110003', 'N', e_usuario_activo)
  RETURNING ID_USUARIO INTO v_colab;

  -- Canales de notificación (INC-04).
  INSERT INTO CANAL_NOTIFICACION_USUARIO (ID_USUARIO, CANAL, DESTINO) VALUES (v_coord, 'CORREO', 'camila.demo@mejorvejez.cl');
  INSERT INTO CANAL_NOTIFICACION_USUARIO (ID_USUARIO, CANAL, DESTINO) VALUES (v_colab, 'CORREO', 'diego.demo@mejorvejez.cl');
  INSERT INTO CANAL_NOTIFICACION_USUARIO (ID_USUARIO, CANAL, DESTINO, HABILITADO) VALUES (v_colab, 'WHATSAPP', '+56911110003', 'N');

  -- -------------------------------------------------------------------
  -- 2. Grupo e integrantes.
  -- -------------------------------------------------------------------
  INSERT INTO GRUPO_FAMILIAR (NOMBRE_GRUPO, NOTIFICA_COORDINADOR, ID_USUARIO_CREADOR, ID_ESTADO)
  VALUES ('Familia Demo Los Aromos', 'S', v_coord, e_grupo_activo)
  RETURNING ID_GRUPO INTO v_grupo;

  INSERT INTO INTEGRANTE_GRUPO (ID_GRUPO, ID_USUARIO, ID_ROL, ID_ESTADO) VALUES (v_grupo, v_coord, r_coordinador, e_integrante_activo);
  INSERT INTO INTEGRANTE_GRUPO (ID_GRUPO, ID_USUARIO, ID_ROL, ID_ESTADO) VALUES (v_grupo, v_colab, r_colaborador, e_integrante_activo);

  -- -------------------------------------------------------------------
  -- 3. Adultos mayores y contactos de emergencia.
  -- -------------------------------------------------------------------
  INSERT INTO ADULTO_MAYOR (ID_GRUPO, CODIGO_INTERNO, NOMBRE_COMPLETO, FECHA_NACIMIENTO, NOTAS_EMERGENCIA,
                            OBSERVACIONES_GENERALES, ID_ESTADO, ID_USUARIO_CREADOR)
  VALUES (v_grupo, 'AM-DEMO-001', 'Elena Ejemplo Soto', DATE '1942-05-14',
          'Dato de demostración: confirmar contacto antes de actuar.', 'Registro ficticio para pruebas.',
          e_adulto_activo, v_coord)
  RETURNING ID_ADULTO_MAYOR INTO v_adulto1;

  INSERT INTO ADULTO_MAYOR (ID_GRUPO, CODIGO_INTERNO, NOMBRE_COMPLETO, FECHA_NACIMIENTO, NOTAS_EMERGENCIA,
                            OBSERVACIONES_GENERALES, ID_ESTADO, ID_USUARIO_CREADOR)
  VALUES (v_grupo, 'AM-DEMO-002', 'Héctor Ejemplo Rojas', DATE '1939-11-02',
          'Dato de demostración: confirmar contacto antes de actuar.', 'Registro ficticio para pruebas.',
          e_adulto_activo, v_coord)
  RETURNING ID_ADULTO_MAYOR INTO v_adulto2;

  INSERT INTO CONTACTO_EMERGENCIA (ID_ADULTO_MAYOR, NOMBRE_CONTACTO, PARENTESCO, TELEFONO, ES_PRINCIPAL, ID_ESTADO, ID_USUARIO_CREADOR)
  VALUES (v_adulto1, 'Contacto Demo Elena', 'Hija', '+56911112001', 'S', e_contacto_activo, v_coord);
  INSERT INTO CONTACTO_EMERGENCIA (ID_ADULTO_MAYOR, NOMBRE_CONTACTO, PARENTESCO, TELEFONO, ES_PRINCIPAL, ID_ESTADO, ID_USUARIO_CREADOR)
  VALUES (v_adulto1, 'Segundo Contacto Demo', 'Vecino', '+56911112003', 'N', e_contacto_activo, v_coord);
  INSERT INTO CONTACTO_EMERGENCIA (ID_ADULTO_MAYOR, NOMBRE_CONTACTO, PARENTESCO, TELEFONO, ES_PRINCIPAL, ID_ESTADO, ID_USUARIO_CREADOR)
  VALUES (v_adulto2, 'Contacto Demo Héctor', 'Hijo', '+56911112002', 'S', e_contacto_activo, v_coord);

  -- -------------------------------------------------------------------
  -- 4. Medicamentos (catálogo global, INC-19).
  -- -------------------------------------------------------------------
  INSERT INTO MEDICAMENTO (NOMBRE, PRINCIPIO_ACTIVO, ID_PRESENTACION, CONCENTRACION, DESCRIPCION)
  VALUES ('Medicamento Demo A', 'Principio activo ficticio A', o_pres_comprimido, '50 mg',
          'Registro ficticio; no usar como indicación clínica.')
  RETURNING ID_MEDICAMENTO INTO v_med1;

  INSERT INTO MEDICAMENTO (NOMBRE, PRINCIPIO_ACTIVO, ID_PRESENTACION, CONCENTRACION, DESCRIPCION)
  VALUES ('Medicamento Demo B', 'Principio activo ficticio B', o_pres_capsula, '20 mg',
          'Registro ficticio; no usar como indicación clínica.')
  RETURNING ID_MEDICAMENTO INTO v_med2;

  INSERT INTO MEDICAMENTO (NOMBRE, PRINCIPIO_ACTIVO, ID_PRESENTACION, CONCENTRACION, DESCRIPCION)
  VALUES ('Medicamento Demo C', 'Principio activo ficticio C', o_pres_solucion_oral, '5 mg/ml',
          'Registro ficticio; no usar como indicación clínica.')
  RETURNING ID_MEDICAMENTO INTO v_med3;

  -- -------------------------------------------------------------------
  -- 5. Planes, detalles, días y horas.
  -- -------------------------------------------------------------------
  INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, VIGENTE_DESDE, ID_USUARIO_CREADOR)
  VALUES (v_grupo, v_adulto1, 'Plan Demo Elena', e_plan_activo, TRUNC(SYSDATE), v_coord)
  RETURNING ID_PLAN INTO v_plan1;

  INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, VIGENTE_DESDE, ID_USUARIO_CREADOR)
  VALUES (v_grupo, v_adulto2, 'Plan Demo Héctor', e_plan_activo, TRUNC(SYSDATE), v_coord)
  RETURNING ID_PLAN INTO v_plan2;

  -- Detalle 1: todos los días a las 08:00, 16:00 y 22:00 (horas fijas).
  INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, CONCENTRACION, INSTRUCCIONES,
                            DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, CANTIDAD_INICIAL, CANTIDAD_DISPONIBLE, FECHA_INICIO,
                            ID_RESPONSABLE_PREDETERMINADO, ID_ESTADO)
  VALUES (v_plan1, v_med1, 'Medicamento Demo A', o_pres_comprimido, '50 mg', 'Instrucción ficticia de prueba.',
          1, o_unid_comprimido, 30, 12, TRUNC(SYSDATE), v_colab, e_detalle_activo)
  RETURNING ID_DETALLE_PLAN INTO v_det1;
  todos_los_dias(v_det1);
  agregar_hora(v_det1, h_0800);
  agregar_hora(v_det1, h_1600);
  agregar_hora(v_det1, h_2200);

  -- Detalle 2: todos los días cada 12 h desde las 08:00 (se guarda como 08:00 y 20:00).
  INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, CONCENTRACION, INSTRUCCIONES,
                            DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, CANTIDAD_INICIAL, CANTIDAD_DISPONIBLE, FECHA_INICIO,
                            ID_RESPONSABLE_PREDETERMINADO, ID_ESTADO)
  VALUES (v_plan1, v_med2, 'Medicamento Demo B', o_pres_capsula, '20 mg', 'Instrucción ficticia de prueba.',
          1, o_unid_capsula, 28, 20, TRUNC(SYSDATE), v_coord, e_detalle_activo)
  RETURNING ID_DETALLE_PLAN INTO v_det2;
  todos_los_dias(v_det2);
  agregar_hora(v_det2, h_0800);
  agregar_hora(v_det2, h_2000);

  -- Detalle 3: lunes, miércoles y viernes a las 09:00.
  INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, CONCENTRACION, INSTRUCCIONES,
                            DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, CANTIDAD_INICIAL, CANTIDAD_DISPONIBLE, FECHA_INICIO,
                            ID_RESPONSABLE_PREDETERMINADO, ID_ESTADO)
  VALUES (v_plan2, v_med3, 'Medicamento Demo C', o_pres_solucion_oral, '5 mg/ml', 'Instrucción ficticia de prueba.',
          5, o_unid_ml, 200, 150, TRUNC(SYSDATE), v_coord, e_detalle_activo)
  RETURNING ID_DETALLE_PLAN INTO v_det3;
  INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (v_det3, o_dia_lunes);
  INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (v_det3, o_dia_miercoles);
  INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (v_det3, o_dia_viernes);
  agregar_hora(v_det3, h_0900);

  -- -------------------------------------------------------------------
  -- 6. Tareas en distintos estados, con ocurrencias, historial y asignaciones.
  -- -------------------------------------------------------------------
  -- Tarea 1: PENDIENTE, dentro de 1 hora.
  INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, ID_DETALLE_PLAN, TITULO_TAREA,
                             DESCRIPCION_TAREA, FECHA_HORARIO, ID_ESTADO)
  VALUES ('MEDICAMENTOS', 'DEMO-OC-001', v_grupo, v_adulto1, v_det1, 'Administrar Medicamento Demo A',
          'Tarea demo pendiente.', SYSTIMESTAMP + NUMTODSINTERVAL(60, 'MINUTE'), e_tarea_pendiente)
  RETURNING ID_TAREA INTO v_tarea1;

  -- Tarea 2: EN_CURSO hace 45 minutos: supera la tolerancia (alerta de atraso).
  INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, ID_DETALLE_PLAN, TITULO_TAREA,
                             DESCRIPCION_TAREA, FECHA_HORARIO, ID_ESTADO)
  VALUES ('MEDICAMENTOS', 'DEMO-OC-002', v_grupo, v_adulto1, v_det2, 'Administrar Medicamento Demo B',
          'Tarea demo atrasada para probar la alerta.', SYSTIMESTAMP - NUMTODSINTERVAL(45, 'MINUTE'), e_tarea_en_curso)
  RETURNING ID_TAREA INTO v_tarea2;

  -- Tarea 3: COMPLETADA hace 2 horas.
  INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, ID_DETALLE_PLAN, TITULO_TAREA,
                             DESCRIPCION_TAREA, FECHA_HORARIO, ID_ESTADO, OBSERVACION_CIERRE, ID_USUARIO_CONFIRMADOR,
                             FECHA_CONFIRMACION)
  VALUES ('MEDICAMENTOS', 'DEMO-OC-003', v_grupo, v_adulto2, v_det3, 'Administrar Medicamento Demo C',
          'Tarea demo completada.', SYSTIMESTAMP - NUMTODSINTERVAL(120, 'MINUTE'), e_tarea_completada,
          'Cierre de prueba.', v_colab, SYSTIMESTAMP - NUMTODSINTERVAL(110, 'MINUTE'))
  RETURNING ID_TAREA INTO v_tarea3;

  -- Tarea 4: cita médica mañana (tarea manual, sin ocurrencia: INC-08).
  INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, DESCRIPCION_TAREA, FECHA_HORARIO, ID_ESTADO)
  VALUES ('CITAS_CONTROLES_MEDICOS', v_grupo, v_adulto1, 'Control médico de Elena', 'Cita demo.',
          SYSTIMESTAMP + NUMTODSINTERVAL(1, 'DAY'), e_tarea_pendiente)
  RETURNING ID_TAREA INTO v_tarea_cita;
  INSERT INTO DETALLE_CITA_MEDICA (ID_TAREA, NOMBRE_MEDICO, LUGAR_ATENCION)
  VALUES (v_tarea_cita, 'Dra. Demo Pérez', 'CESFAM Demo, consulta 3');

  INSERT INTO OCURRENCIA_PLANIFICADA (ID_DETALLE_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, FECHA_HORARIO, CLAVE_OCURRENCIA, ID_ESTADO, ID_TAREA)
  SELECT ID_DETALLE_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, FECHA_HORARIO, CLAVE_OCURRENCIA, e_ocurrencia_creada, ID_TAREA
    FROM TAREA_CUIDADO
   WHERE ID_TAREA IN (v_tarea1, v_tarea2, v_tarea3);

  -- Historial (INC-07: estados del dominio TAREA_CUIDADO).
  INSERT INTO HISTORIAL_ESTADO_TAREA (ID_TAREA, ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, ID_USUARIO_CAMBIO, OBSERVACION)
  VALUES (v_tarea2, e_tarea_pendiente, e_tarea_en_curso, v_coord, 'Inicio de la administración.');
  INSERT INTO HISTORIAL_ESTADO_TAREA (ID_TAREA, ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, ID_USUARIO_CAMBIO, OBSERVACION)
  VALUES (v_tarea3, e_tarea_pendiente, e_tarea_completada, v_colab, 'Cierre de prueba.');

  -- Asignaciones: una activa por tarea; la tarea 1 muestra una reasignación.
  INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, FECHA_TERMINO, ID_ESTADO, MOTIVO)
  VALUES (v_tarea1, v_grupo, v_coord, v_coord, SYSTIMESTAMP, e_asignacion_reempl, 'Asignación inicial');
  INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, ID_ESTADO, MOTIVO)
  VALUES (v_tarea1, v_grupo, v_colab, v_coord, e_asignacion_activa, 'Reasignación demo: cambio de turno');
  INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, ID_ESTADO)
  VALUES (v_tarea2, v_grupo, v_coord, v_coord, e_asignacion_activa);
  INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, ID_ESTADO)
  VALUES (v_tarea3, v_grupo, v_colab, v_coord, e_asignacion_activa);
  INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, ID_ESTADO)
  VALUES (v_tarea_cita, v_grupo, v_colab, v_coord, e_asignacion_activa);

  -- -------------------------------------------------------------------
  -- 7. Alerta de atraso y un intento de notificación fallido.
  -- -------------------------------------------------------------------
  INSERT INTO ALERTA_CUIDADO (CLAVE_ALERTA, TIPO_ALERTA, ID_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, MENSAJE_ALERTA, ID_ESTADO)
  VALUES ('ATRASO_TAREA-' || v_tarea2, 'ATRASO_TAREA', v_tarea2, v_grupo, v_adulto1,
          'La tarea "Administrar Medicamento Demo B" sigue en curso 30 minutos después de su horario.',
          e_alerta_abierta)
  RETURNING ID_ALERTA INTO v_alerta;

  INSERT INTO INTENTO_NOTIFICACION (ID_ALERTA, ID_USUARIO_DESTINATARIO, CANAL, NUMERO_INTENTO, ID_ESTADO_ENVIO, DETALLE_ERROR)
  VALUES (v_alerta, v_coord, 'CORREO', 1, e_envio_fallido, 'Simulación: proveedor de correo no invocado.');

  -- -------------------------------------------------------------------
  -- 8. Sucesión pendiente y relevo en borrador.
  -- -------------------------------------------------------------------
  INSERT INTO PROPUESTA_SUCESION (ID_GRUPO, ID_USUARIO_SALIENTE, ID_USUARIO_CANDIDATO, ID_ESTADO)
  VALUES (v_grupo, v_coord, v_colab, e_propuesta_pendiente);

  INSERT INTO TRASPASO_FAMILIAR (ID_GRUPO, ID_ADULTO_MAYOR, ID_USUARIO_SALIENTE, ID_USUARIO_ENTRANTE, NOTAS,
                                 ID_USUARIO_CREADOR, ID_ESTADO)
  VALUES (v_grupo, v_adulto1, v_coord, v_colab, 'Relevo demo de la tarde.', v_coord, e_traspaso_borrador)
  RETURNING ID_TRASPASO INTO v_traspaso;

  INSERT INTO DETALLE_TRASPASO (ID_TRASPASO, TIPO_ELEMENTO, ID_REFERENCIA, RESUMEN)
  VALUES (v_traspaso, 'TAREA', v_tarea1, 'Tarea pendiente incluida en el relevo.');
  INSERT INTO DETALLE_TRASPASO (ID_TRASPASO, TIPO_ELEMENTO, ID_REFERENCIA, RESUMEN)
  VALUES (v_traspaso, 'ALERTA', v_alerta, 'Alerta abierta incluida en el relevo.');

  -- -------------------------------------------------------------------
  -- 9. Corrida de proceso y auditoría.
  -- -------------------------------------------------------------------
  INSERT INTO EJECUCION_PROCESO (TIPO_EJECUCION, INICIO_VENTANA, FIN_VENTANA, ID_ESTADO, FECHA_FIN, CANTIDAD_CREADA, RESUMEN_ERRORES)
  VALUES ('GENERACION_TAREAS', SYSTIMESTAMP - NUMTODSINTERVAL(1, 'DAY'), SYSTIMESTAMP + NUMTODSINTERVAL(1, 'DAY'),
          e_ejecucion_exitosa, SYSTIMESTAMP, 3, 'Corrida demo.');

  INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, ID_USUARIO_ACTOR, CODIGO_ACCION,
                                ID_RESULTADO, ID_CORRELACION, DETALLES_JSON)
  VALUES ('grupos', 'GRUPO_FAMILIAR', TO_CHAR(v_grupo), v_grupo, v_coord, 'CREAR_GRUPO',
          e_auditoria_exito, 'DEMO-CORR-001', '{"origen":"datos_demo.sql"}');
  INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, ID_USUARIO_ACTOR, CODIGO_ACCION,
                                ID_RESULTADO, ID_CORRELACION, DETALLES_JSON)
  VALUES ('tareas', 'ASIGNACION_TAREA', TO_CHAR(v_tarea1), v_grupo, v_coord, 'REASIGNAR_TAREA',
          e_auditoria_exito, 'DEMO-CORR-002',
          '{"responsableAnterior":' || v_coord || ',"responsableNuevo":' || v_colab || '}');

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('datos_demo.sql: datos demo cargados. Grupo ' || v_grupo ||
                       ', admin ' || v_admin || ', coordinadora ' || v_coord || ', colaborador ' || v_colab || '.');
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
