package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Un relevo del turno de cuidado detectado dentro del período (Fase 9,
 * TSA-61): parte de {@link DatosSemana#relevos}. {@code totalTareas} y
 * {@code totalAlertas} son el conteo de {@code DETALLE_TRASPASO} de ese
 * relevo por tipo de elemento (la misma foto que guardó {@code
 * relevos.ServicioRelevos#registrar}). {@code codigoEstado} es el estado
 * actual del relevo ({@code COMPLETADA} | {@code CANCELADA}), no uno "al
 * cierre": {@code TRASPASO_FAMILIAR} no tiene esa noción.
 */
public record RelevoSemana(long idRelevo, long idAdulto, OffsetDateTime fechaTraspaso, Persona saliente,
        Persona entrante, int totalTareas, int totalAlertas, String codigoEstado) {

    /** Los instantes salen siempre con el offset de America/Santiago (mismo ruling de F4). */
    public RelevoSemana {
        fechaTraspaso = ZonaNegocio.enZona(fechaTraspaso);
    }
}
