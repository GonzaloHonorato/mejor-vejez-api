package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Historial de cambios de estado de una {@link TareaCuidado} ({@code
 * HISTORIAL_ESTADO_TAREA}, HU-07). Append-only: el trigger {@code
 * TRG_HISTORIAL_TAREA_INMUTABLE} (V3) impide modificarlo o eliminarlo,
 * incluso para el dueño del esquema. {@code @Immutable} evita además que
 * Hibernate intente actualizarlo si alguna fila gestionada se modificara por
 * error en memoria (no genera UPDATE para esta entidad); la aplicación solo
 * inserta. {@code ID_ESTADO_ANTERIOR} y {@code ID_ESTADO_NUEVO} comparten el
 * dominio {@code TAREA_CUIDADO.ESTADO} (INC-07).
 */
@Entity
@Table(name = "HISTORIAL_ESTADO_TAREA")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistorialEstadoTarea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_HISTORIAL")
    private Long idHistorial;

    @Column(name = "ID_TAREA", nullable = false)
    private Long idTarea;

    @Column(name = "ID_ESTADO_ANTERIOR")
    private Long idEstadoAnterior;

    @Column(name = "ID_ESTADO_NUEVO", nullable = false)
    private Long idEstadoNuevo;

    @Column(name = "ID_USUARIO_CAMBIO", nullable = false)
    private Long idUsuarioCambio;

    @Column(name = "FECHA_CAMBIO", nullable = false)
    private OffsetDateTime fechaCambio;

    @Column(name = "OBSERVACION", length = 1000)
    private String observacion;

    /**
     * Fila nueva de historial. {@code idEstadoAnterior} es {@code null}
     * cuando la tarea se crea (no hay estado previo); en cualquier otro
     * cambio va el estado del que viene la tarea.
     */
    public HistorialEstadoTarea(long idTarea, Long idEstadoAnterior, long idEstadoNuevo, long idUsuario,
            String observacion, OffsetDateTime fecha) {
        this.idTarea = idTarea;
        this.idEstadoAnterior = idEstadoAnterior;
        this.idEstadoNuevo = idEstadoNuevo;
        this.idUsuarioCambio = idUsuario;
        this.observacion = observacion;
        this.fechaCambio = fecha;
    }
}
