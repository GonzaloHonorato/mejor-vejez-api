package cl.adndigital.mejorvejez.grupos;

import jakarta.validation.constraints.NotNull;

/** Datos para cambiar el rol de un integrante ({@code PUT .../integrantes/{id}/rol}). */
public record SolicitudCambiarRol(@NotNull(message = "El rol es obligatorio.") Long idRol) {
}
