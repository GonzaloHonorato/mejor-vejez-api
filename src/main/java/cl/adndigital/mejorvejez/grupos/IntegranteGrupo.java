package cl.adndigital.mejorvejez.grupos;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Membresía de un usuario en un grupo ({@code INTEGRANTE_GRUPO}), única por
 * (grupo, usuario) ({@code UQ_INTEGRANTE_GRUPO}): la reincorporación
 * reactiva la misma fila (INC-22). Estado en el catálogo {@code
 * INTEGRANTE_GRUPO.ESTADO} (ACTIVO / INACTIVO / SALIDA_PENDIENTE); solo una
 * membresía ACTIVA da acceso al grupo.
 */
@Entity
@Table(name = "INTEGRANTE_GRUPO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IntegranteGrupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_INTEGRANTE")
    private Long idIntegrante;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_USUARIO", nullable = false)
    private Long idUsuario;

    @Column(name = "ID_ROL", nullable = false)
    private Long idRol;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_INCORPORACION", nullable = false)
    private OffsetDateTime fechaIncorporacion;

    @Column(name = "FECHA_DESACTIVACION")
    private OffsetDateTime fechaDesactivacion;

    /** Nueva membresía ACTIVA de {@code idUsuario} en {@code idGrupo} con el rol indicado. */
    public IntegranteGrupo(long idGrupo, long idUsuario, long idRol, long idEstado, OffsetDateTime fechaIncorporacion) {
        this.idGrupo = idGrupo;
        this.idUsuario = idUsuario;
        this.idRol = idRol;
        this.idEstado = idEstado;
        this.fechaIncorporacion = fechaIncorporacion;
    }

    /** Cambia el rol de la membresía, en la misma fila ({@code PUT .../integrantes/{id}/rol}). */
    public void cambiarRol(long idRol) {
        this.idRol = idRol;
    }

    /** Desactiva la membresía (lógica): deja {@code ID_ESTADO} y la fecha de desactivación. */
    public void desactivar(long idEstadoInactivo, OffsetDateTime fechaDesactivacion) {
        this.idEstado = idEstadoInactivo;
        this.fechaDesactivacion = fechaDesactivacion;
    }

    /**
     * Reactiva la membresía con el rol indicado, en la misma fila (INC-22):
     * limpia {@code FECHA_DESACTIVACION}. Para reactivar sin cambiar el rol
     * (p.ej. {@code POST .../reactivar}), el llamador pasa el rol actual.
     */
    public void reactivar(long idRol, long idEstadoActivo) {
        this.idRol = idRol;
        this.idEstado = idEstadoActivo;
        this.fechaDesactivacion = null;
    }
}
