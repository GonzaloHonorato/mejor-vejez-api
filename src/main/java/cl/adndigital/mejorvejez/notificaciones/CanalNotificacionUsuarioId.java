package cl.adndigital.mejorvejez.notificaciones;

import java.io.Serializable;
import java.util.Objects;

/**
 * Clave compuesta de {@link CanalNotificacionUsuario} ({@code
 * PK_CANAL_NOTIFICACION_USUARIO}: {@code ID_USUARIO, CANAL}). Los nombres de
 * los campos deben coincidir con los de los atributos identificadores de la
 * entidad ({@code @IdClass}, JPA). Los ids se comparan por valor ({@link
 * Objects#equals}), nunca con {@code ==}.
 */
public class CanalNotificacionUsuarioId implements Serializable {

    private Long idUsuario;
    private String canal;

    public CanalNotificacionUsuarioId() {
    }

    public CanalNotificacionUsuarioId(Long idUsuario, String canal) {
        this.idUsuario = idUsuario;
        this.canal = canal;
    }

    @Override
    public boolean equals(Object otro) {
        if (this == otro) {
            return true;
        }
        if (!(otro instanceof CanalNotificacionUsuarioId that)) {
            return false;
        }
        return Objects.equals(idUsuario, that.idUsuario) && Objects.equals(canal, that.canal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idUsuario, canal);
    }
}
