package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;

/**
 * Una fila cruda de {@code DETALLE_PLAN} candidata a una alerta de
 * reposición de medicamento (Fase 9, TSA-61), leída por {@link
 * RepositorioConsultaAlertas#candidatosReposicion}. {@code
 * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} la convierte en
 * {@code CandidatoReposicion} (el contrato HTTP).
 */
public record FilaCandidatoReposicion(long idDetallePlan, LocalDate fechaEstimadaTermino) {
}
