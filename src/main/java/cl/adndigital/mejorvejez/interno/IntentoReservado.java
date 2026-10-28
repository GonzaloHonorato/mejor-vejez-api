package cl.adndigital.mejorvejez.interno;

/**
 * Un par (destinatario, canal) con su intento ya reservado en {@code
 * PENDIENTE} (Fase 9, TSA-61): el microservicio de Alertas lo envía y
 * confirma el resultado con {@code PUT /api/interno/intentos/{idIntento}}.
 */
public record IntentoReservado(Long idIntento, Long idUsuario, String nombre, String canal, String destino,
        Integer numeroIntento) {
}
