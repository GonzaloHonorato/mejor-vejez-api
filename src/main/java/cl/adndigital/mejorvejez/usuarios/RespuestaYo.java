package cl.adndigital.mejorvejez.usuarios;

/** Datos del usuario autenticado que devuelve {@code GET /api/yo} y {@code PATCH /api/yo}. */
public record RespuestaYo(long idUsuario, String nombre, String email, String telefono, boolean esAdministrador) {

    public static RespuestaYo desde(Usuario usuario) {
        return new RespuestaYo(usuario.getIdUsuario(), usuario.getNombre(), usuario.getEmail(),
                usuario.getTelefono(), Boolean.TRUE.equals(usuario.getEsAdministrador()));
    }
}
