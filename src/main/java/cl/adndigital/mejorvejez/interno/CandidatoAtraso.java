package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una tarea candidata a una alerta de atraso (Fase 9, TSA-61, contrato
 * "Core → lo consume Alertas"), tal como la devuelve {@code GET
 * /api/interno/alertas/candidatos-atraso}. El Core NO aplica la regla de
 * atraso (ni estado, ni tolerancia): {@code codigoEstado} y {@code
 * generaAlerta} van crudos para que el microservicio de Alertas decida.
 */
public record CandidatoAtraso(long idTarea, long idGrupo, long idAdulto, OffsetDateTime fechaHorario,
        int minutosTolerancia, String codigoEstado, boolean generaAlerta) {

    /** Los instantes salen siempre con el offset de America/Santiago (mismo ruling de F4). */
    public CandidatoAtraso {
        fechaHorario = ZonaNegocio.enZona(fechaHorario);
    }
}
