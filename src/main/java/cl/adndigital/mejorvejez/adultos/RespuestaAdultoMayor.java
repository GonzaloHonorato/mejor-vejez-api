package cl.adndigital.mejorvejez.adultos;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Datos del perfil de un adulto mayor para quien lo consulta ({@code GET
 * .../adultos}, {@code GET .../adultos/{idAdulto}}, {@code POST .../adultos},
 * {@code PUT .../adultos/{idAdulto}}, {@code POST .../desactivar} y {@code
 * POST .../reactivar}).
 *
 * @param contactoPrincipal el contacto ACTIVO marcado como principal, o
 *                           {@code null} si no tiene ninguno.
 */
public record RespuestaAdultoMayor(long idAdultoMayor, long idGrupo, String codigoInterno, String nombreCompleto,
        LocalDate fechaNacimiento, String notasEmergencia, String observacionesGenerales, long idEstado,
        OffsetDateTime fechaCreacion, OffsetDateTime fechaActualizacion, RespuestaContacto contactoPrincipal) {

    public static RespuestaAdultoMayor desde(AdultoMayor adulto, RespuestaContacto contactoPrincipal) {
        return new RespuestaAdultoMayor(adulto.getIdAdultoMayor(), adulto.getIdGrupo(), adulto.getCodigoInterno(),
                adulto.getNombreCompleto(), adulto.getFechaNacimiento(), adulto.getNotasEmergencia(),
                adulto.getObservacionesGenerales(), adulto.getIdEstado(), adulto.getFechaCreacion(),
                adulto.getFechaActualizacion(), contactoPrincipal);
    }
}
