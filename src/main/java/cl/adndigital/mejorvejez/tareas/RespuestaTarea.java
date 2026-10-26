package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;
import java.util.List;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Tarea de cuidado lista para la agenda o el detalle (HU-05/06/07). {@code
 * responsable} es la asignación {@code ACTIVO} vigente ({@code null} si la
 * tarea no tiene una); {@code cita} solo se completa para {@code
 * CITAS_CONTROLES_MEDICOS} ({@code null} en cualquier otro tipo); {@code
 * compra} solo trae líneas para {@code COMPRA_RETIRO_MEDICAMENTOS} ({@code []}
 * en cualquier otro tipo). {@code atrasada} se calcula al responder (regla 12
 * de la Fase 4), con el {@code Clock} de negocio inyectado.
 */
public record RespuestaTarea(long idTarea, String tipoTarea, long idAdultoMayor, String nombreAdulto,
        Long idDetallePlan, String titulo, String descripcion, OffsetDateTime fechaHorario, int minutosTolerancia,
        long idEstado, boolean atrasada, RespuestaResponsable responsable, String observacionCierre,
        String nombreConfirmador, OffsetDateTime fechaConfirmacion, String motivoCancelacion,
        List<RespuestaLineaCompra> compra, RespuestaCita cita) {

    /** Ruling final de F4: los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaTarea {
        fechaHorario = ZonaNegocio.enZona(fechaHorario);
        fechaConfirmacion = ZonaNegocio.enZona(fechaConfirmacion);
    }
}
