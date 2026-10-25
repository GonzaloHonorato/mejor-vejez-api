package cl.adndigital.mejorvejez.grupos;

import java.time.OffsetDateTime;

/**
 * Datos de un grupo familiar para quien lo consulta ({@code POST /api/grupos},
 * {@code GET /api/grupos}, {@code GET /api/grupos/{id}} y {@code PATCH
 * /api/grupos/{id}}).
 *
 * @param miRol rol de la membresía ACTIVA de quien consulta ({@code
 *              "COORDINADOR"} | {@code "COLABORADOR"}), o {@code null} si es
 *              un administrador sin membresía activa en el grupo.
 */
public record RespuestaGrupo(long idGrupo, String nombreGrupo, boolean notificaCoordinador, long idEstado,
        String miRol, OffsetDateTime fechaCreacion) {

    public static RespuestaGrupo desde(GrupoFamiliar grupo, String miRol) {
        return new RespuestaGrupo(grupo.getIdGrupo(), grupo.getNombreGrupo(),
                Boolean.TRUE.equals(grupo.getNotificaCoordinador()), grupo.getIdEstado(), miRol,
                grupo.getFechaCreacion());
    }
}
