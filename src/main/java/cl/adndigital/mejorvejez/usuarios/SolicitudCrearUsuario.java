package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;
import cl.adndigital.mejorvejez.comun.TelefonoChilenoValido;

/**
 * Datos para crear una cuenta nueva desde la administración global ({@code
 * POST /api/usuarios}). {@code telefono} es opcional; si viene, debe ser un
 * teléfono chileno ({@link TelefonoChileno}) y llega normalizado.
 */
public record SolicitudCrearUsuario(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 254, message = "El correo no puede superar los 254 caracteres.")
        String email,
        @TelefonoChilenoValido
        String telefono) {

    /** Recorta el correo y normaliza el teléfono antes de validar (Bean Validation corre sobre lo normalizado). */
    public SolicitudCrearUsuario {
        email = email == null ? null : email.trim();
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
