package cl.adndigital.mejorvejez.grupos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Datos para editar un grupo familiar ({@code PATCH /api/grupos/{idGrupo}}). */
public record SolicitudActualizarGrupo(
        @NotBlank(message = "El nombre del grupo es obligatorio.")
        @Size(max = 120, message = "El nombre del grupo no puede superar los 120 caracteres.")
        String nombreGrupo,
        @NotNull(message = "Debe indicar si notifica al coordinador.")
        Boolean notificaCoordinador) {
}
