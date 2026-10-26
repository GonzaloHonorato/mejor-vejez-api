package cl.adndigital.mejorvejez.adultos;

/**
 * Desactivación o reactivación de un adulto mayor, que obliga a recalcular
 * las tareas futuras de todos sus planes (HU-05, regla 8 de la Fase 4). Lo
 * publica {@link ServicioAdultosMayores} después de guardar y antes de
 * auditar, dentro de su transacción; lo escucha de forma síncrona {@code
 * tareas.ServicioRecalculoTareas}, así que una excepción del recálculo
 * revierte el cambio que lo originó.
 *
 * @param idGrupo        grupo del adulto.
 * @param idAdulto       adulto mayor desactivado o reactivado.
 * @param idUsuarioActor quien hizo el cambio (actor del historial y de la auditoría).
 * @param accesoAdministrador el actor mutó como administrador sin membresía en el grupo
 *                       ({@code DetallesAuditoria.esAccesoAdministrador}): la auditoría
 *                       derivada del recálculo lo marca con {@code "accesoAdministrador": true}.
 */
public record EventoCambioEstadoAdulto(long idGrupo, long idAdulto, long idUsuarioActor,
        boolean accesoAdministrador) {
}
