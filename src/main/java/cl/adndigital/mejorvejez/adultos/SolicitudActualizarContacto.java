package cl.adndigital.mejorvejez.adultos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos para actualizar un contacto de emergencia ({@code PUT
 * .../contactos/{idContacto}}), con las mismas validaciones y normalización
 * que {@link SolicitudCrearContacto}.
 */
public record SolicitudActualizarContacto(
        @NotBlank(message = "El nombre del contacto es obligatorio.")
        @Size(max = 160, message = "El nombre del contacto no puede superar los 160 caracteres.")
        String nombreContacto,
        @Size(max = 80, message = "El parentesco no puede superar los 80 caracteres.")
        String parentesco,
        @NotBlank(message = "El teléfono es obligatorio.")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{5,29}$", message = "Ingresa un teléfono válido")
        String telefono) {

    public SolicitudActualizarContacto {
        nombreContacto = nombreContacto == null ? null : nombreContacto.trim();
        parentesco = normalizarOpcional(parentesco);
        telefono = telefono == null ? null : telefono.trim();
    }

    private static String normalizarOpcional(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
