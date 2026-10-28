package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;

/**
 * Una fila cruda de {@code TAREA_CUIDADO} candidata a una alerta de atraso
 * (Fase 9, TSA-61), leída por {@link RepositorioConsultaAlertas#candidatosAtraso},
 * con los tipos ya resueltos por JDBC (mismo estilo que {@code
 * auditoria.FilaEventoAuditoria}). Esta consulta NO aplica la regla de
 * atraso (ni estado ni tolerancia): trae {@code codigoEstado} y {@code
 * generaAlerta} crudos para que el servicio de Alertas decida. {@code
 * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} la convierte en
 * {@code CandidatoAtraso} (el contrato HTTP).
 */
public record FilaCandidatoAtraso(long idTarea, long idGrupo, long idAdulto, OffsetDateTime fechaHorario,
        int minutosTolerancia, String codigoEstado, boolean generaAlerta) {
}
