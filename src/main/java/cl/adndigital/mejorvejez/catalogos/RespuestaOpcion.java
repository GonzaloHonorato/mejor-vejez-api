package cl.adndigital.mejorvejez.catalogos;

/** Una opción del catálogo de negocio, tal como la consume el front para armar un select. */
public record RespuestaOpcion(Long idOpcion, String codigo, String descripcion, Integer orden) {
}
