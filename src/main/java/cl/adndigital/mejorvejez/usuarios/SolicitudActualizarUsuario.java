package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;

/**
 * Datos para editar un usuario desde la administración global de cuentas
 * ({@code PATCH /api/usuarios/{idUsuario}}). El correo no se edita: viene
 * de Firebase. {@code telefono} es opcional; {@code null} o vacío lo borra
 * (igual que {@link SolicitudActualizarPerfil}). La regla del teléfono
 * ({@link TelefonoChileno}) la aplica {@code ServicioAdministracionUsuarios},
 * que deja pasar el teléfono ya guardado sin cambios.
 */
public record SolicitudActualizarUsuario(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        String telefono,
        @NotNull(message = "El rol de administrador es obligatorio.")
        Boolean esAdministrador) {

    public SolicitudActualizarUsuario {
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
