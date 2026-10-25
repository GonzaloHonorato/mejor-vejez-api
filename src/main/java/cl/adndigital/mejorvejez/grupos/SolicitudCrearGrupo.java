package cl.adndigital.mejorvejez.grupos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Datos para crear un grupo familiar ({@code POST /api/grupos}). */
public record SolicitudCrearGrupo(
        @NotBlank(message = "El nombre del grupo es obligatorio.")
        @Size(max = 120, message = "El nombre del grupo no puede superar los 120 caracteres.")
        String nombreGrupo) {
}
