package cl.adndigital.mejorvejez.planificacion;

import java.util.List;

/**
 * Cambio en la planificación de medicamentos que obliga a recalcular las
 * tareas futuras (HU-05, regla 8 de la Fase 4). Lo publican {@link
 * ServicioPlanes} y {@link ServicioDetallesPlan} después de guardar y antes
 * de auditar, dentro de su transacción y con el plan ya bloqueado; lo escucha
 * de forma síncrona {@code tareas.ServicioRecalculoTareas}, así que una
 * excepción del recálculo revierte el cambio que lo originó.
 *
 * @param idGrupo        grupo del plan.
 * @param idPlan         plan modificado.
 * @param idsDetalle     detalles afectados; vacío = todos los detalles del plan.
 * @param idUsuarioActor quien hizo el cambio (actor del historial y de la auditoría).
 * @param accesoAdministrador el actor mutó como administrador sin membresía en el grupo
 *                       ({@code DetallesAuditoria.esAccesoAdministrador}): la auditoría
 *                       derivada del recálculo lo marca con {@code "accesoAdministrador": true}.
 */
public record EventoCambioPlanificacion(long idGrupo, long idPlan, List<Long> idsDetalle, long idUsuarioActor,
        boolean accesoAdministrador) {

    public EventoCambioPlanificacion {
        idsDetalle = List.copyOf(idsDetalle);
    }
}
