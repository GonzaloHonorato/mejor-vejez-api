package cl.adndigital.mejorvejez.alertas;

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
 * Alerta de cuidado ({@code ALERTA_CUIDADO}, HU-08): atraso de una tarea
 * (TSA-43), cita próxima o reposición de un medicamento (TSA-44). Nace
 * siempre de una {@code TareaCuidado} ({@link #deTarea}, {@code ID_TAREA} no
 * nulo) o de un {@code DetallePlan} ({@link #deDetalle}, {@code
 * ID_DETALLE_PLAN} no nulo), nunca de ambos ni de ninguno ({@code
 * CK_ALERTA_ORIGEN}).
 *
 * <p>{@code CLAVE_ALERTA} es la clave de idempotencia (regla 1 de la Fase 5:
 * {@code ATR-}, {@code CIT-} o {@code REP-} seguido del id de origen) y
 * {@code UQ_ALERTA_CLAVE} es la última barrera contra duplicados, igual que
 * {@code CLAVE_OCURRENCIA} en {@code tareas.TareaCuidado}.
 *
 * <p>{@link #cerrar} es la única forma de pasar a {@code CERRADA} (regla 13):
 * ni el recálculo, ni la cancelación de la tarea ni el envío de una
 * notificación tocan {@code ID_ESTADO}.
 */
@Entity
@Table(name = "ALERTA_CUIDADO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertaCuidado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ALERTA")
    private Long idAlerta;

    @Column(name = "CLAVE_ALERTA", nullable = false, length = 220)
    private String claveAlerta;

    @Column(name = "TIPO_ALERTA", nullable = false, length = 40)
    private String tipoAlerta;

    @Column(name = "ID_TAREA")
    private Long idTarea;

    @Column(name = "ID_DETALLE_PLAN")
    private Long idDetallePlan;

    @Column(name = "FECHA_PROGRAMADA")
    private OffsetDateTime fechaProgramada;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "MENSAJE_ALERTA", nullable = false, length = 1000)
    private String mensajeAlerta;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_DETECCION", nullable = false)
    private OffsetDateTime fechaDeteccion;

    @Column(name = "ID_USUARIO_REVISOR")
    private Long idUsuarioRevisor;

    @Column(name = "FECHA_REVISION")
    private OffsetDateTime fechaRevision;

    @Column(name = "OBSERVACION_CIERRE", length = 1000)
    private String observacionCierre;

    private AlertaCuidado(String claveAlerta, String tipoAlerta, Long idTarea, Long idDetallePlan,
            OffsetDateTime fechaProgramada, long idGrupo, long idAdulto, String mensajeAlerta, long idEstadoAbierta,
            OffsetDateTime ahora) {
        this.claveAlerta = claveAlerta;
        this.tipoAlerta = tipoAlerta;
        this.idTarea = idTarea;
        this.idDetallePlan = idDetallePlan;
        this.fechaProgramada = fechaProgramada;
        this.idGrupo = idGrupo;
        this.idAdultoMayor = idAdulto;
        this.mensajeAlerta = mensajeAlerta;
        this.idEstado = idEstadoAbierta;
        this.fechaDeteccion = ahora;
    }

    /**
     * Alerta nacida de una tarea ({@code ATRASO_TAREA} | {@code CITA_PROXIMA},
     * reglas 1 a 3 de la Fase 5): {@code ID_TAREA} fijo, {@code
     * ID_DETALLE_PLAN} nulo.
     */
    public static AlertaCuidado deTarea(String clave, String tipo, long idTarea, OffsetDateTime fechaProgramada,
            long idGrupo, long idAdulto, String mensaje, long idEstadoAbierta, OffsetDateTime ahora) {
        return new AlertaCuidado(clave, tipo, idTarea, null, fechaProgramada, idGrupo, idAdulto, mensaje,
                idEstadoAbierta, ahora);
    }

    /**
     * Alerta nacida de un detalle del plan ({@code REPOSICION_MEDICAMENTO},
     * regla 4 de la Fase 5): {@code ID_DETALLE_PLAN} fijo, {@code ID_TAREA}
     * nulo. Nunca crea una tarea de compra.
     */
    public static AlertaCuidado deDetalle(String clave, String tipo, long idDetallePlan,
            OffsetDateTime fechaProgramada, long idGrupo, long idAdulto, String mensaje, long idEstadoAbierta,
            OffsetDateTime ahora) {
        return new AlertaCuidado(clave, tipo, null, idDetallePlan, fechaProgramada, idGrupo, idAdulto, mensaje,
                idEstadoAbierta, ahora);
    }

    /**
     * Cierra la alerta ({@code CERRADA}) con el revisor, la fecha y la
     * observación (regla 13 de la Fase 5). No toca la tarea de origen.
     */
    public void cerrar(long idEstadoCerrada, long idRevisor, String observacion, OffsetDateTime ahora) {
        this.idEstado = idEstadoCerrada;
        this.idUsuarioRevisor = idRevisor;
        this.observacionCierre = observacion;
        this.fechaRevision = ahora;
    }
}
