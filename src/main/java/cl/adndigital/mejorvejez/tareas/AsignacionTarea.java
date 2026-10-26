package cl.adndigital.mejorvejez.tareas;

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
 * Asignación (o reasignación) del responsable de una {@link TareaCuidado}
 * ({@code ASIGNACION_TAREA}, HU-06). Una sola asignación {@code ACTIVO} por
 * tarea ({@code UX_ASIGNACION_ACTIVA}, V3). Para reemplazarla hay que
 * guardar y hacer flush de la anterior ({@code REEMPLAZADA} o {@code
 * CANCELADA}, con {@link #terminar}) ANTES de insertar la nueva: Hibernate
 * ejecuta los INSERT antes que los UPDATE al hacer flush, y si no se
 * respeta ese orden el índice único rechaza el INSERT (restricción global de
 * la Fase 4).
 */
@Entity
@Table(name = "ASIGNACION_TAREA")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AsignacionTarea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ASIGNACION")
    private Long idAsignacion;

    @Column(name = "ID_TAREA", nullable = false)
    private Long idTarea;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_USUARIO_ASIGNADO", nullable = false)
    private Long idUsuarioAsignado;

    @Column(name = "ID_USUARIO_ASIGNADOR", nullable = false)
    private Long idUsuarioAsignador;

    @Column(name = "FECHA_ASIGNACION", nullable = false)
    private OffsetDateTime fechaAsignacion;

    @Column(name = "FECHA_TERMINO")
    private OffsetDateTime fechaTermino;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "MOTIVO", length = 500)
    private String motivo;

    /** Asignación nueva (ACTIVO) de la tarea indicada, con quién la asigna y el motivo. */
    public AsignacionTarea(long idTarea, long idGrupo, long idUsuarioAsignado, long idUsuarioAsignador,
            String motivo, long idEstadoActivo, OffsetDateTime ahora) {
        this.idTarea = idTarea;
        this.idGrupo = idGrupo;
        this.idUsuarioAsignado = idUsuarioAsignado;
        this.idUsuarioAsignador = idUsuarioAsignador;
        this.motivo = motivo;
        this.idEstado = idEstadoActivo;
        this.fechaAsignacion = ahora;
    }

    /** Termina la asignación ({@code REEMPLAZADA} | {@code CANCELADA}) y fija {@code FECHA_TERMINO}. */
    public void terminar(long idEstado, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.fechaTermino = ahora;
    }
}
