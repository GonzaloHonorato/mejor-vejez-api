package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una tarea de cuidado del período, reconstruida AL CIERRE desde el
 * historial (Fase 9, TSA-61): parte de {@link DatosSemana#tareas}.
 *
 * <ul>
 * <li>{@code codigoEstadoAlCierre}: el {@code ID_ESTADO_NUEVO} de la última
 * entrada de {@code HISTORIAL_ESTADO_TAREA} con {@code FECHA_CAMBIO} antes
 * del cierre; {@code PENDIENTE} si no hay ninguna.</li>
 * <li>{@code fechaConfirmacion}/{@code confirmadoPor}: los de esa misma
 * entrada, solo cuando lleva a {@code COMPLETADA} o {@code NO_REALIZADA};
 * {@code null} en cualquier otro caso.</li>
 * <li>{@code responsableAlCierre}: la asignación vigente al cierre (antes
 * del cierre y sin terminar antes del cierre); {@code null} si no hay
 * ninguna.</li>
 * <li>{@code reasignada}: la tarea tiene más de una asignación antes del
 * cierre.</li>
 * </ul>
 */
public record TareaSemana(long idTarea, long idAdulto, String tipoTarea, String titulo, OffsetDateTime fechaHorario,
        int minutosTolerancia, String codigoEstadoAlCierre, OffsetDateTime fechaConfirmacion, Persona confirmadoPor,
        Persona responsableAlCierre, boolean reasignada) {

    /** Los instantes salen siempre con el offset de America/Santiago (mismo ruling de F4). */
    public TareaSemana {
        fechaHorario = ZonaNegocio.enZona(fechaHorario);
        fechaConfirmacion = ZonaNegocio.enZona(fechaConfirmacion);
    }
}
