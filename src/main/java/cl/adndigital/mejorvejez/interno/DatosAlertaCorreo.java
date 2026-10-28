package cl.adndigital.mejorvejez.interno;

/**
 * Datos de la alerta que necesita el microservicio de Alertas para redactar
 * y enviar el correo (Fase 9, TSA-61): van siempre en {@code
 * RespuestaReservaIntentos}, aunque {@code intentos} venga vacía (el origen
 * ya no está vigente o no hay ningún par elegible), para que quien llama
 * sepa de qué alerta se trataba.
 */
public record DatosAlertaCorreo(Long idAlerta, Long idGrupo, String tipoAlerta, String mensaje) {
}
