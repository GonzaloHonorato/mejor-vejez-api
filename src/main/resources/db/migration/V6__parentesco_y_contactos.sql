-- =====================================================================
-- V6__parentesco_y_contactos.sql
-- Correcciones de datos del adulto mayor (D3): el parentesco del contacto
-- de emergencia deja de ser texto libre y se elige del catálogo
-- CATALOGO_NEGOCIO, tipo PARENTESCO.
--
-- - CK_OPCION_TIPO (V1) admite el tipo nuevo PARENTESCO.
-- - Siembra los 15 valores (formas femeninas separadas) con el mismo MERGE
--   de V2 (reejecutable).
-- - CONTACTO_EMERGENCIA.ID_PARENTESCO, nula y con FK al catálogo: los
--   contactos antiguos conservan su texto en PARENTESCO; los nuevos o
--   editados guardan el ID y, en PARENTESCO, la descripción.
-- - Completa ID_PARENTESCO de las filas antiguas cuyo texto coincide con
--   una descripción del catálogo (sin tildes, mayúsculas ni espacios de
--   borde). Las que no coinciden quedan tal cual. Con las formas femeninas
--   del catálogo, textos antiguos como "Hija" o "Nieta" ahora también
--   enlazan (antes quedaban sin ID_PARENTESCO).
--
-- Aplica limpio sobre V1–V5 sin editarlas.
-- =====================================================================

ALTER TABLE CATALOGO_NEGOCIO DROP CONSTRAINT CK_OPCION_TIPO;

ALTER TABLE CATALOGO_NEGOCIO ADD CONSTRAINT CK_OPCION_TIPO
  CHECK (TIPO_CATALOGO IN ('PRESENTACION_MEDICAMENTO','UNIDAD_DOSIS','DIA_SEMANA','PARENTESCO'));

DECLARE
  PROCEDURE opcion(p_tipo VARCHAR2, p_codigo VARCHAR2, p_descripcion VARCHAR2, p_orden NUMBER) IS
  BEGIN
    MERGE INTO CATALOGO_NEGOCIO c
    USING (SELECT p_tipo AS t, p_codigo AS k FROM DUAL) s
       ON (c.TIPO_CATALOGO = s.t AND c.CODIGO = s.k)
     WHEN MATCHED THEN UPDATE SET c.DESCRIPCION = p_descripcion, c.ORDEN = p_orden
     WHEN NOT MATCHED THEN INSERT (TIPO_CATALOGO, CODIGO, DESCRIPCION, ORDEN)
                           VALUES (p_tipo, p_codigo, p_descripcion, p_orden);
  END;
BEGIN
  opcion('PARENTESCO', 'PADRE',   'Padre',   1);
  opcion('PARENTESCO', 'MADRE',   'Madre',   2);
  opcion('PARENTESCO', 'HIJO',    'Hijo',    3);
  opcion('PARENTESCO', 'HIJA',    'Hija',    4);
  opcion('PARENTESCO', 'NIETO',   'Nieto',   5);
  opcion('PARENTESCO', 'NIETA',   'Nieta',   6);
  opcion('PARENTESCO', 'HERMANO', 'Hermano', 7);
  opcion('PARENTESCO', 'HERMANA', 'Hermana', 8);
  opcion('PARENTESCO', 'TIO',     'Tío',     9);
  opcion('PARENTESCO', 'TIA',     'Tía',     10);
  opcion('PARENTESCO', 'SOBRINO', 'Sobrino', 11);
  opcion('PARENTESCO', 'SOBRINA', 'Sobrina', 12);
  opcion('PARENTESCO', 'CONYUGE', 'Cónyuge', 13);
  opcion('PARENTESCO', 'YERNO',   'Yerno',   14);
  opcion('PARENTESCO', 'NUERA',   'Nuera',   15);
END;
/

ALTER TABLE CONTACTO_EMERGENCIA ADD (ID_PARENTESCO NUMBER);

ALTER TABLE CONTACTO_EMERGENCIA ADD CONSTRAINT FK_CONTACTO_PARENTESCO
  FOREIGN KEY (ID_PARENTESCO) REFERENCES CATALOGO_NEGOCIO(ID_OPCION);

UPDATE CONTACTO_EMERGENCIA ce
   SET ce.ID_PARENTESCO = (
         SELECT cn.ID_OPCION
           FROM CATALOGO_NEGOCIO cn
          WHERE cn.TIPO_CATALOGO = 'PARENTESCO'
            AND UPPER(TRANSLATE(cn.DESCRIPCION, 'áéíóúüÁÉÍÓÚÜ', 'aeiouuAEIOUU'))
              = UPPER(TRANSLATE(TRIM(ce.PARENTESCO), 'áéíóúüÁÉÍÓÚÜ', 'aeiouuAEIOUU')))
 WHERE ce.ID_PARENTESCO IS NULL
   AND ce.PARENTESCO IS NOT NULL
   AND EXISTS (
         SELECT 1
           FROM CATALOGO_NEGOCIO cn
          WHERE cn.TIPO_CATALOGO = 'PARENTESCO'
            AND UPPER(TRANSLATE(cn.DESCRIPCION, 'áéíóúüÁÉÍÓÚÜ', 'aeiouuAEIOUU'))
              = UPPER(TRANSLATE(TRIM(ce.PARENTESCO), 'áéíóúüÁÉÍÓÚÜ', 'aeiouuAEIOUU')));
