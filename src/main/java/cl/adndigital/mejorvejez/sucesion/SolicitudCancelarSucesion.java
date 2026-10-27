package cl.adndigital.mejorvejez.sucesion;

import jakarta.validation.constraints.Size;

/**
 * Datos opcionales para cancelar una propuesta de sucesión (HU-13, {@code
 * POST .../sucesion/{id}/cancelar}); el cuerpo completo es opcional. El
 * constructor compacto recorta {@code motivo} y lo deja en {@code null} si
 * queda en blanco, antes de que corra Bean Validation (mismo patrón que
 * {@code relevos.SolicitudRegistrarRelevo}). El motivo es texto libre: se
 * guarda en {@code MOTIVO_CANCELACION}, pero nunca va a la auditoría.
 */
public record SolicitudCancelarSucesion(
        @Size(max = CodigosSucesion.MAXIMO_MOTIVO_CANCELACION,
                message = "El motivo no puede superar los 500 caracteres.")
        String motivo) {

    public SolicitudCancelarSucesion {
        motivo = recortarOpcional(motivo);
    }

    static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
