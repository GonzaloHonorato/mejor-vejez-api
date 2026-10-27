package cl.adndigital.mejorvejez.sucesion;

import jakarta.validation.constraints.NotNull;

/**
 * Datos para proponer un sucesor de la coordinación (HU-13, {@code POST
 * .../sucesion}). Quién puede proponer y la elegibilidad del candidato las
 * exige {@link ServicioSucesion} (403/409/422): esta clase solo valida la
 * forma.
 */
public record SolicitudProponerSucesion(
        @NotNull(message = "El candidato es obligatorio.")
        Long idUsuarioCandidato) {
}
