package cl.adndigital.mejorvejez.grupos;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Datos de una membresía de grupo para quien la consulta ({@code GET
 * .../integrantes}, {@code POST .../integrantes}, {@code PUT .../rol},
 * {@code POST .../desactivar} y {@code POST .../reactivar}).
 *
 * @param fechaDesactivacion {@code null} si la membresía está ACTIVA.
 */
public record RespuestaIntegrante(long idIntegrante, long idUsuario, String nombre, String email, long idRol,
        String nombreRol, long idEstado, OffsetDateTime fechaIncorporacion, OffsetDateTime fechaDesactivacion) {

    public static RespuestaIntegrante desde(IntegranteGrupo integrante, Usuario usuario, RolGrupo rol) {
        return new RespuestaIntegrante(integrante.getIdIntegrante(), usuario.getIdUsuario(), usuario.getNombre(),
                usuario.getEmail(), rol.getIdRol(), rol.getNombreRol(), integrante.getIdEstado(),
                integrante.getFechaIncorporacion(), integrante.getFechaDesactivacion());
    }
}
