package cl.adndigital.mejorvejez.grupos;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import cl.adndigital.mejorvejez.comun.ConvertidorSiNo;

/**
 * Grupo familiar ({@code GRUPO_FAMILIAR}): la unidad de aislamiento de los
 * datos de cuidado. Estado en el catálogo {@code GRUPO_FAMILIAR.ESTADO}
 * (ACTIVO / INACTIVO). El acceso de un usuario a un grupo lo decide
 * {@code ServicioAutorizacion}.
 */
@Entity
@Table(name = "GRUPO_FAMILIAR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GrupoFamiliar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_GRUPO")
    private Long idGrupo;

    @Column(name = "NOMBRE_GRUPO", nullable = false, length = 120)
    private String nombreGrupo;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "NOTIFICA_COORDINADOR", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean notificaCoordinador;

    @Column(name = "ID_USUARIO_CREADOR", nullable = false)
    private Long idUsuarioCreador;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /** Grupo nuevo, con el creador indicado ({@code POST /api/grupos}). */
    public GrupoFamiliar(String nombreGrupo, boolean notificaCoordinador, long idUsuarioCreador, long idEstado,
            OffsetDateTime fechaCreacion) {
        this.nombreGrupo = nombreGrupo;
        this.notificaCoordinador = notificaCoordinador;
        this.idUsuarioCreador = idUsuarioCreador;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /** Actualiza nombre y notificación al coordinador ({@code PATCH /api/grupos/{idGrupo}}). */
    public void actualizar(String nombreGrupo, boolean notificaCoordinador, OffsetDateTime fecha) {
        this.nombreGrupo = nombreGrupo;
        this.notificaCoordinador = notificaCoordinador;
        this.fechaActualizacion = fecha;
    }
}
