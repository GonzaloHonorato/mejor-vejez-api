-- =====================================================================
-- V7__elimina_parentesco_texto.sql
-- Elimina CONTACTO_EMERGENCIA.PARENTESCO (texto libre de V1). Desde V6 el
-- parentesco vive solo en ID_PARENTESCO (FK al catálogo CATALOGO_NEGOCIO,
-- tipo PARENTESCO) y la API lee la descripción desde el catálogo.
--
-- ID_PARENTESCO sigue nula: los contactos antiguos cuyo texto no coincidió
-- con el catálogo en V6 quedan sin parentesco y se completan al editarlos.
-- Esos textos se pierden.
--
-- Aplica limpio sobre V1–V6 sin editarlas.
-- =====================================================================

ALTER TABLE CONTACTO_EMERGENCIA DROP COLUMN PARENTESCO;
