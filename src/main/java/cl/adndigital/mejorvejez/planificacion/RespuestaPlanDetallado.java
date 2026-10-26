package cl.adndigital.mejorvejez.planificacion;

import java.util.List;

/** Plan con todos sus detalles, activos primero ({@code GET .../planes/{idPlan}}). */
public record RespuestaPlanDetallado(RespuestaPlan plan, List<RespuestaDetallePlan> detalles) {
}
