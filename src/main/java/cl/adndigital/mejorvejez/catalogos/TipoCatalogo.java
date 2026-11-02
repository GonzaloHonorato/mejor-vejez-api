package cl.adndigital.mejorvejez.catalogos;

/**
 * Tipos del catálogo de negocio ({@code CATALOGO_NEGOCIO.TIPO_CATALOGO}).
 * Debe coincidir exactamente con los valores del CHECK
 * {@code CK_OPCION_TIPO} del esquema (V1__tablas.sql, ampliado en
 * V6__parentesco_y_contactos.sql): si se agrega un valor aquí, hay que
 * agregarlo también a ese CHECK con una migración nueva.
 */
public enum TipoCatalogo {
    PRESENTACION_MEDICAMENTO,
    UNIDAD_DOSIS,
    DIA_SEMANA,
    PARENTESCO
}
