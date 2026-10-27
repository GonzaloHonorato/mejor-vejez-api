package cl.adndigital.mejorvejez.sucesion;

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
 * Propuesta de sucesión de la coordinación de un grupo ({@code
 * PROPUESTA_SUCESION}, HU-13, TSA-53): el coordinador saliente ({@code
 * ID_USUARIO_SALIENTE}) propone a un integrante como sucesor ({@code
 * ID_USUARIO_CANDIDATO}). {@code CK_PROPUESTA_DISTINTOS} impide que sean la
 * misma persona, y {@code UX_PROPUESTA_ACTIVA_GRUPO} que el grupo tenga más
 * de una {@code PENDIENTE} o {@code ACEPTADA} a la vez.
 *
 * <p>Transiciones (regla 16 de la Fase 7): {@code PENDIENTE} → {@code
 * ACEPTADA} | {@code RECHAZADA} ({@link #aceptar} / {@link #rechazar}, con
 * {@code FECHA_RESPUESTA}); {@code PENDIENTE} | {@code ACEPTADA} → {@code
 * CANCELADA} ({@link #cancelar}, con {@code FECHA_CANCELACION} y el motivo);
 * {@code ACEPTADA} → {@code COMPLETADA} ({@link #completar}, con {@code
 * FECHA_COMPLETADO}, la usa la Tarea 4). Estos métodos no validan el estado
 * de origen ni el actor: lo hace el servicio, después de bloquear el grupo.
 */
@Entity
@Table(name = "PROPUESTA_SUCESION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropuestaSucesion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_PROPUESTA")
    private Long idPropuesta;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_USUARIO_SALIENTE", nullable = false)
    private Long idUsuarioSaliente;

    @Column(name = "ID_USUARIO_CANDIDATO", nullable = false)
    private Long idUsuarioCandidato;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_RESPUESTA")
    private OffsetDateTime fechaRespuesta;

    @Column(name = "FECHA_CANCELACION")
    private OffsetDateTime fechaCancelacion;

    @Column(name = "FECHA_COMPLETADO")
    private OffsetDateTime fechaCompletado;

    @Column(name = "MOTIVO_CANCELACION", length = CodigosSucesion.MAXIMO_MOTIVO_CANCELACION)
    private String motivoCancelacion;

    private PropuestaSucesion(long idGrupo, long idUsuarioSaliente, long idUsuarioCandidato, long idEstado,
            OffsetDateTime fechaCreacion) {
        this.idGrupo = idGrupo;
        this.idUsuarioSaliente = idUsuarioSaliente;
        this.idUsuarioCandidato = idUsuarioCandidato;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /** Propuesta nueva, {@code PENDIENTE}, con {@code FECHA_CREACION = ahora} (Clock de negocio). */
    static PropuestaSucesion nueva(long idGrupo, long idUsuarioSaliente, long idUsuarioCandidato,
            long idEstadoPendiente, OffsetDateTime ahora) {
        return new PropuestaSucesion(idGrupo, idUsuarioSaliente, idUsuarioCandidato, idEstadoPendiente, ahora);
    }

    /** {@code PENDIENTE} → {@code ACEPTADA}: no cambia roles (regla 20); solo registra la respuesta. */
    void aceptar(long idEstadoAceptada, OffsetDateTime fecha) {
        this.idEstado = idEstadoAceptada;
        this.fechaRespuesta = fecha;
    }

    /** {@code PENDIENTE} → {@code RECHAZADA}: la propuesta se cierra y queda en el historial. */
    void rechazar(long idEstadoRechazada, OffsetDateTime fecha) {
        this.idEstado = idEstadoRechazada;
        this.fechaRespuesta = fecha;
    }

    /**
     * {@code PENDIENTE} | {@code ACEPTADA} → {@code CANCELADA}, con el motivo
     * opcional ya recortado ({@code null} si no hay); {@code FECHA_RESPUESTA}
     * se conserva si la había.
     */
    void cancelar(long idEstadoCancelada, OffsetDateTime fecha, String motivo) {
        this.idEstado = idEstadoCancelada;
        this.fechaCancelacion = fecha;
        this.motivoCancelacion = motivo;
    }

    /** {@code ACEPTADA} → {@code COMPLETADA} (regla 21, la usa el servicio de completar de la Tarea 4). */
    void completar(long idEstadoCompletada, OffsetDateTime fecha) {
        this.idEstado = idEstadoCompletada;
        this.fechaCompletado = fecha;
    }
}
