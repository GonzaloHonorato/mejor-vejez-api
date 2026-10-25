package cl.adndigital.mejorvejez.catalogos;

/** Un estado del catálogo, tal como lo consume el front para armar un select. */
public record RespuestaEstado(Long idEstado, String codigo, String descripcion, Integer orden) {
}
