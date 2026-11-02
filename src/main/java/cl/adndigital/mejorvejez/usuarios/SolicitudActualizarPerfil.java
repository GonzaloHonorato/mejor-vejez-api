package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;

/**
 * Datos para actualizar el perfil del usuario autenticado ({@code PATCH
 * /api/yo}). El correo no se edita: viene de Firebase. {@code telefono} es
 * opcional; {@code null} o vacío lo borra. Si es válido llega ya
 * normalizado ({@link TelefonoChileno}); la regla la aplica {@code
 * ServicioPerfil}, que deja pasar el teléfono ya guardado sin cambios.
 */
public record SolicitudActualizarPerfil(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        String telefono) {

    public SolicitudActualizarPerfil {
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
