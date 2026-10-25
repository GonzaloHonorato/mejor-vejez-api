package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos para editar un usuario desde la administración global de cuentas
 * ({@code PATCH /api/usuarios/{idUsuario}}). El correo no se edita: viene
 * de Firebase. {@code telefono} es opcional; {@code null} o vacío lo borra
 * (igual que {@link SolicitudActualizarPerfil}).
 */
public record SolicitudActualizarUsuario(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 160, message = "El nombre no puede superar los 160 caracteres.")
        String nombre,
        @Size(max = 30, message = "El teléfono no puede superar los 30 caracteres.")
        @Pattern(regexp = "^$|^\\+?[0-9 ]{7,20}$", message = "El teléfono no es válido.")
        String telefono,
        @NotNull(message = "El rol de administrador es obligatorio.")
        Boolean esAdministrador) {
}
