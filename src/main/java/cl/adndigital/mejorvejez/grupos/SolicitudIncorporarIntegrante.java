package cl.adndigital.mejorvejez.grupos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Datos para incorporar (o reactivar) un integrante por correo ({@code POST .../integrantes}). */
public record SolicitudIncorporarIntegrante(
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 254, message = "El correo no puede superar los 254 caracteres.")
        String email,
        @NotNull(message = "El rol es obligatorio.")
        Long idRol) {

    /** Recorta espacios del correo antes de validar (Bean Validation corre sobre el valor ya normalizado). */
    public SolicitudIncorporarIntegrante {
        email = email == null ? null : email.trim();
    }
}
