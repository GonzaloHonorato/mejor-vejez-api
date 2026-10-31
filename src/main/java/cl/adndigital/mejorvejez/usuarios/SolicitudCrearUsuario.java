package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear una cuenta nueva desde la administración global ({@code
 * POST /api/usuarios}). {@code telefono} es opcional.
 */
public record SolicitudCrearUsuario(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 254, message = "El correo no puede superar los 254 caracteres.")
        String email,
        @Size(max = 30, message = "El teléfono no puede superar los 30 caracteres.")
        @Pattern(regexp = "^$|^\\+?[0-9 ]{7,20}$", message = "El teléfono no es válido.")
        String telefono) {

    /** Recorta espacios del correo antes de validar (Bean Validation corre sobre el valor ya normalizado). */
    public SolicitudCrearUsuario {
        email = email == null ? null : email.trim();
    }
}
