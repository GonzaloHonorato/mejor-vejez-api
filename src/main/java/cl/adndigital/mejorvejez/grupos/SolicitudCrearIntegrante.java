package cl.adndigital.mejorvejez.grupos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos para que un coordinador (o el administrador) cree un integrante con
 * una cuenta nueva ({@code POST .../integrantes/nuevo}).
 */
public record SolicitudCrearIntegrante(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 254, message = "El correo no puede superar los 254 caracteres.")
        String email,
        long idRol) {

    /** Recorta espacios del correo antes de validar (Bean Validation corre sobre el valor ya normalizado). */
    public SolicitudCrearIntegrante {
        email = email == null ? null : email.trim();
    }
}
