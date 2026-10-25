package cl.adndigital.mejorvejez.usuarios;

/** Datos del usuario autenticado que devuelve {@code GET /api/yo}. */
public record RespuestaYo(long idUsuario, String nombre, String email, boolean esAdministrador) {

    public static RespuestaYo desde(Usuario usuario) {
        return new RespuestaYo(usuario.getIdUsuario(), usuario.getNombre(), usuario.getEmail(),
                Boolean.TRUE.equals(usuario.getEsAdministrador()));
    }
}
