package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.constraints.NotNull;

/** Nuevo estado global de un usuario ({@code PUT /api/usuarios/{idUsuario}/estado}). */
public record SolicitudCambiarEstado(@NotNull(message = "El estado es obligatorio.") Long idEstado) {
}
