package cl.adndigital.mejorvejez.tareas;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Línea de medicamento de una tarea manual de {@code
 * COMPRA_RETIRO_MEDICAMENTOS} ({@code POST .../grupos/{idGrupo}/tareas},
 * HU-05, TSA-41). {@code idMedicamento} e {@code idPresentacion} los valida
 * {@link ServicioTareasManuales} contra el catálogo global de medicamentos y
 * {@code PRESENTACION_MEDICAMENTO} respectivamente; {@code idUnidadCantidad}
 * contra {@code UNIDAD_DOSIS}.
 *
 * <p>El constructor compacto recorta {@code dosisIndicada} y {@code
 * observaciones} y los deja en {@code null} si quedan en blanco, antes de
 * que corra Bean Validation (mismo patrón que {@code
 * planificacion.SolicitudGuardarDetalle}).
 */
public record SolicitudLineaCompra(
        @NotNull(message = "El medicamento es obligatorio.")
        Long idMedicamento,
        @NotNull(message = "La presentación es obligatoria.")
        Long idPresentacion,
        @Size(max = 200, message = "La dosis indicada no puede superar los 200 caracteres.")
        String dosisIndicada,
        @NotNull(message = "La cantidad solicitada es obligatoria.")
        @Positive(message = "La cantidad solicitada debe ser mayor que cero.")
        BigDecimal cantidadSolicitada,
        @NotNull(message = "La unidad de la cantidad es obligatoria.")
        Long idUnidadCantidad,
        @Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres.")
        String observaciones) {

    public SolicitudLineaCompra {
        dosisIndicada = recortarOpcional(dosisIndicada);
        observaciones = recortarOpcional(observaciones);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
