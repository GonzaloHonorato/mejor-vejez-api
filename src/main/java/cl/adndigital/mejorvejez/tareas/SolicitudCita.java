package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos de una tarea manual de {@code CITAS_CONTROLES_MEDICOS} ({@code POST
 * .../grupos/{idGrupo}/tareas}, HU-05, TSA-41): médico y lugar de atención.
 * Sin {@code CLAVE_OCURRENCIA} (solo las tareas de {@code MEDICAMENTOS} la
 * llevan).
 *
 * <p>El constructor compacto recorta los tres campos y deja {@code
 * observaciones} en {@code null} si queda en blanco, antes de que corra Bean
 * Validation (mismo patrón que {@code SolicitudLineaCompra}).
 */
public record SolicitudCita(
        @NotBlank(message = "El nombre del médico es obligatorio.")
        @Size(max = 180, message = "El nombre del médico no puede superar los 180 caracteres.")
        String nombreMedico,
        @NotBlank(message = "El lugar de atención es obligatorio.")
        @Size(max = 250, message = "El lugar de atención no puede superar los 250 caracteres.")
        String lugarAtencion,
        @Size(max = 1000, message = "Las observaciones no pueden superar los 1000 caracteres.")
        String observaciones) {

    public SolicitudCita {
        nombreMedico = nombreMedico == null ? null : nombreMedico.trim();
        lugarAtencion = lugarAtencion == null ? null : lugarAtencion.trim();
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
