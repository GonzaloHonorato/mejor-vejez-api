package cl.adndigital.mejorvejez.usuarios;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Datos de un usuario para la administración global de cuentas ({@code
 * GET /api/usuarios}, {@code GET /api/usuarios/{id}}, {@code PATCH
 * /api/usuarios/{id}} y {@code PUT /api/usuarios/{id}/estado}).
 */
public record RespuestaUsuario(long idUsuario, String nombre, String email, String telefono,
        boolean esAdministrador, long idEstado, OffsetDateTime fechaCreacion, OffsetDateTime fechaActualizacion) {

    /** Mismo criterio que el resto de la API: los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaUsuario {
        fechaCreacion = ZonaNegocio.enZona(fechaCreacion);
        fechaActualizacion = ZonaNegocio.enZona(fechaActualizacion);
    }

    public static RespuestaUsuario desde(Usuario usuario) {
        return new RespuestaUsuario(usuario.getIdUsuario(), usuario.getNombre(), usuario.getEmail(),
                usuario.getTelefono(), Boolean.TRUE.equals(usuario.getEsAdministrador()), usuario.getIdEstado(),
                usuario.getFechaCreacion(), usuario.getFechaActualizacion());
    }
}
