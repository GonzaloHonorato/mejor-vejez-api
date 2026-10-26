package cl.adndigital.mejorvejez.tareas;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;

/**
 * Única fuente de la verificación "plan, adulto y grupo están ACTIVO" (y
 * "el detalle está ACTIVO") que deciden si un plan sigue generando tareas:
 * la usan la fase A ({@link GeneradorPorPlan}), la fase B ({@link
 * CreadorTareas#crearDesdeOcurrencia}) y el recálculo tras un cambio de plan
 * (Tarea 4, regla 8). Solo lee (no bloquea adulto ni grupo): el llamador ya
 * tiene el plan bloqueado y el orden de bloqueo adulto → plan → tarea no se
 * altera.
 */
@Component
public class VerificadorVigencia {

    private static final String ACTIVO = "ACTIVO";

    private final RepositorioAdultoMayor repositorioAdultos;
    private final RepositorioGrupoFamiliar repositorioGrupos;
    private final ServicioCatalogos servicioCatalogos;

    public VerificadorVigencia(RepositorioAdultoMayor repositorioAdultos, RepositorioGrupoFamiliar repositorioGrupos,
            ServicioCatalogos servicioCatalogos) {
        this.repositorioAdultos = repositorioAdultos;
        this.repositorioGrupos = repositorioGrupos;
        this.servicioCatalogos = servicioCatalogos;
    }

    /** {@code true} si el plan, su adulto mayor y su grupo están ACTIVO. */
    public boolean planAdultoYGrupoActivos(PlanMedicamentos plan) {
        return plan.getIdEstado() == idActivo("PLAN_MEDICAMENTOS")
                && repositorioAdultos.findById(plan.getIdAdultoMayor())
                        .filter(adulto -> adulto.getIdEstado() == idActivo("ADULTO_MAYOR")).isPresent()
                && repositorioGrupos.findById(plan.getIdGrupo())
                        .filter(grupo -> grupo.getIdEstado() == idActivo("GRUPO_FAMILIAR")).isPresent();
    }

    /** {@code true} si el detalle está ACTIVO. */
    public boolean detalleActivo(DetallePlan detalle) {
        return detalle.getIdEstado() == idDetalleActivo();
    }

    /** Id del estado ACTIVO de {@code DETALLE_PLAN.ESTADO} (para filtrar los detalles de un plan). */
    public long idDetalleActivo() {
        return idActivo("DETALLE_PLAN");
    }

    private long idActivo(String tabla) {
        return servicioCatalogos.idEstado(tabla, CodigosTareas.CAMPO_ESTADO, ACTIVO);
    }
}
