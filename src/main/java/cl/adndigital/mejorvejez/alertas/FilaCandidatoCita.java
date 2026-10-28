package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;

/**
 * Una fila cruda de {@code TAREA_CUIDADO} candidata a una alerta de cita
 * próxima (Fase 9, TSA-61), leída por {@link
 * RepositorioConsultaAlertas#candidatosCitaProxima}. {@code
 * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} la convierte en
 * {@code CandidatoCita} (el contrato HTTP).
 */
public record FilaCandidatoCita(long idTarea, OffsetDateTime fechaHorario) {
}
