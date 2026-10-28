package cl.adndigital.mejorvejez.interno;

import java.time.LocalDate;

/**
 * Un detalle de plan candidato a una alerta de reposición de medicamento
 * (Fase 9, TSA-61), parte de {@link CandidatosPreventivos}.
 */
public record CandidatoReposicion(long idDetallePlan, LocalDate fechaEstimadaTermino) {
}
