package cl.adndigital.mejorvejez.interno;

/**
 * Respuesta de {@code POST /api/interno/alertas} (Fase 9, TSA-61). {@code
 * idAlerta} es {@code null} cuando el origen ya no corresponde (la tarea se
 * confirmó, se canceló, se inactivó su adulto/grupo/plan/detalle...);
 * {@code creada} distingue "se creó ahora" de "ya existía" (idempotencia,
 * regla 1 de la Fase 5): en ambos casos con éxito {@code idAlerta} trae el
 * mismo id.
 */
public record RespuestaCrearAlerta(Long idAlerta, boolean creada) {
}
