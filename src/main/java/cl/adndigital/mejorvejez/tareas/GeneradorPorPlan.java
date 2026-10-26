package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.planificacion.CatalogoDiasSemana;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;

/**
 * Fase A del generador diario (regla 7 de la Fase 4): reserva, en una
 * transacción propia por plan y con el plan bloqueado (regla 6), las
 * ocurrencias PLANIFICADA que faltan en la ventana. No crea tareas: eso es
 * la fase B ({@link CreadorTareas#crearDesdeOcurrencia}).
 */
@Component
public class GeneradorPorPlan {

    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final RepositorioDetallePlan repositorioDetalles;
    private final VerificadorVigencia verificador;
    private final CreadorTareas creadorTareas;
    private final PlanificadorOcurrencias planificador;
    private final CatalogoDiasSemana catalogoDias;
    private final Clock reloj;

    public GeneradorPorPlan(RepositorioPlanMedicamentos repositorioPlanes, RepositorioDetallePlan repositorioDetalles,
            VerificadorVigencia verificador, CreadorTareas creadorTareas, PlanificadorOcurrencias planificador,
            CatalogoDiasSemana catalogoDias, Clock reloj) {
        this.repositorioPlanes = repositorioPlanes;
        this.repositorioDetalles = repositorioDetalles;
        this.verificador = verificador;
        this.creadorTareas = creadorTareas;
        this.planificador = planificador;
        this.catalogoDias = catalogoDias;
        this.reloj = reloj;
    }

    /**
     * Bloquea el plan y, si plan, adulto y grupo siguen ACTIVO, reserva con
     * {@link CreadorTareas#reservarSiLibre} cada horario de {@code
     * [max(inicio, ahora), fin)} de cada detalle ACTIVO: nunca turnos ya
     * pasados (ruling final de F4 sobre la regla 4). La corrida de las 00:05
     * no pierde los turnos de {@code [00:00, 00:05)}: los reservó la corrida
     * del día anterior, porque la ventana es de varios días. Un detalle sin
     * días conocidos u horas no detiene a los demás: se informa como error y
     * se sigue.
     *
     * @return los errores por detalle ({@code "Detalle <id>: sin días u horas
     *         válidos"}); vacío si no hubo, o si el plan ya no corresponde.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> planificar(long idPlan, OffsetDateTime inicio, OffsetDateTime fin) {
        PlanMedicamentos plan = repositorioPlanes.bloquearPorId(idPlan).orElse(null);
        if (plan == null || !verificador.planAdultoYGrupoActivos(plan)) {
            return List.of();
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime desde = inicio.isAfter(ahora) ? inicio : ahora;
        List<String> errores = new ArrayList<>();
        for (DetallePlan detalle : repositorioDetalles.findByIdPlanAndIdEstadoOrderByIdDetallePlan(idPlan,
                verificador.idDetalleActivo())) {
            Optional<Programacion> programacion = ProgramacionDetalle.de(detalle, plan, catalogoDias);
            if (programacion.isEmpty()) {
                errores.add("Detalle " + detalle.getIdDetallePlan() + ": sin días u horas válidos");
                continue;
            }
            for (OffsetDateTime horario : planificador.horarios(programacion.get(), desde, fin)) {
                creadorTareas.reservarSiLibre(detalle, plan, horario, ahora);
            }
        }
        return errores;
    }
}
