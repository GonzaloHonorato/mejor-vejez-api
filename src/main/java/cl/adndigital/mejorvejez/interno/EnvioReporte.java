package cl.adndigital.mejorvejez.interno;

import java.time.LocalDate;
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
 * Registro idempotente (Fase 9, TSA-61, V5) del envío del reporte PDF
 * semanal de un grupo para un período ({@code ENVIO_REPORTE}): una fila por
 * {@code (ID_GRUPO, INICIO_PERIODO)} ({@code UQ_ENVIO_REPORTE_GRUPO_PERIODO},
 * la clave de idempotencia de {@code POST /api/interno/reportes/envios}).
 * Nunca guarda el PDF, solo el resultado del envío a cada destinatario
 * ({@link EnvioReporteDestinatario}).
 *
 * <p>{@link #registrarResultado} es la única forma de cambiar {@code
 * ID_ESTADO} tras la creación: suma 1 a {@code INTENTOS} en cada llamada
 * (regla de {@code PUT .../resultado}), sea cual sea el estado resultante.
 */
@Entity
@Table(name = "ENVIO_REPORTE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnvioReporte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ENVIO")
    private Long idEnvio;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "INICIO_PERIODO", nullable = false)
    private LocalDate inicioPeriodo;

    @Column(name = "FIN_PERIODO", nullable = false)
    private LocalDate finPeriodo;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "INTENTOS", nullable = false)
    private Integer intentos;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    @Column(name = "DETALLE_ERROR", length = 500)
    private String detalleError;

    /** Envío nuevo, en {@code idEstadoPendiente}, sin intentos todavía. */
    public EnvioReporte(long idGrupo, LocalDate inicioPeriodo, LocalDate finPeriodo, long idEstadoPendiente,
            OffsetDateTime ahora) {
        this.idGrupo = idGrupo;
        this.inicioPeriodo = inicioPeriodo;
        this.finPeriodo = finPeriodo;
        this.idEstado = idEstadoPendiente;
        this.intentos = 0;
        this.fechaCreacion = ahora;
    }

    /**
     * Registra el resultado de una ronda de {@code PUT .../resultado}: suma
     * 1 a {@code INTENTOS} y deja el estado resultante (que decide el
     * llamador: {@code ENVIADO} si no quedan pendientes, {@code FALLIDO} si
     * {@code INTENTOS ≥ 3} y quedan pendientes, {@code PENDIENTE} en otro
     * caso) y el resumen del error (sin direcciones, {@code null} cuando
     * queda {@code ENVIADO}).
     */
    public void registrarResultado(long idEstadoResultante, String detalleError, OffsetDateTime ahora) {
        this.idEstado = idEstadoResultante;
        this.intentos = this.intentos + 1;
        this.detalleError = detalleError;
        this.fechaActualizacion = ahora;
    }

    /**
     * Deja el envío {@code idEstadoEnviado} con {@code detalleError}, sin
     * sumar a {@code INTENTOS}: no hubo ningún intento de envío (la foto de
     * destinatarios quedó vacía al crearse), así que nunca queda {@code
     * PENDIENTE} esperando un destinatario que nunca existió.
     */
    public void marcarSinDestinatarios(long idEstadoEnviado, String detalleError, OffsetDateTime ahora) {
        this.idEstado = idEstadoEnviado;
        this.detalleError = detalleError;
        this.fechaActualizacion = ahora;
    }
}
