package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una tarea {@code CITAS_CONTROLES_MEDICOS} candidata a una alerta de cita
 * próxima (Fase 9, TSA-61), parte de {@link CandidatosPreventivos}.
 */
public record CandidatoCita(long idTarea, OffsetDateTime fechaHorario) {

    /** Los instantes salen siempre con el offset de America/Santiago (mismo ruling de F4). */
    public CandidatoCita {
        fechaHorario = ZonaNegocio.enZona(fechaHorario);
    }
}
