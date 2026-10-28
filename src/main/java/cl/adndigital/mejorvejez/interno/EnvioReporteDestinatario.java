package cl.adndigital.mejorvejez.interno;

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
 * Resultado del envío del reporte semanal a un destinatario (Fase 9,
 * TSA-61, V5, {@code ENVIO_REPORTE_DESTINATARIO}): una fila por {@code
 * (ID_ENVIO, ID_USUARIO)}, la foto de destinatarios tomada al crear el
 * {@link EnvioReporte} (regla de {@code POST /api/interno/reportes/envios}).
 * Nace siempre {@code PENDIENTE}; {@link #marcarResultado} es la única
 * forma de pasar a {@code ENVIADO} o {@code FALLIDO} ({@code PUT
 * .../resultado}).
 */
@Entity
@Table(name = "ENVIO_REPORTE_DESTINATARIO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnvioReporteDestinatario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ENVIO_DESTINATARIO")
    private Long idEnvioDestinatario;

    @Column(name = "ID_ENVIO", nullable = false)
    private Long idEnvio;

    @Column(name = "ID_USUARIO", nullable = false)
    private Long idUsuario;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "DETALLE_ERROR", length = 500)
    private String detalleError;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /** Destinatario nuevo de la foto del envío indicado, en {@code idEstadoPendiente}. */
    public EnvioReporteDestinatario(long idEnvio, long idUsuario, long idEstadoPendiente) {
        this.idEnvio = idEnvio;
        this.idUsuario = idUsuario;
        this.idEstado = idEstadoPendiente;
    }

    /**
     * Pasa a un estado final ({@code ENVIADO} | {@code FALLIDO}) con su
     * detalle de error, ya recortado. Solo tiene sentido llamarlo desde
     * {@code PENDIENTE} o desde {@code FALLIDO} (un reintento que termina
     * en {@code ENVIADO} o en otro {@code FALLIDO}): el llamador nunca debe
     * invocarlo sobre un destinatario que ya está {@code ENVIADO}, porque
     * {@code ENVIADO} es un estado final de éxito y esto lo sobrescribiría
     * sin control.
     */
    public void marcarResultado(long idEstadoResultado, String detalleError, OffsetDateTime ahora) {
        this.idEstado = idEstadoResultado;
        this.detalleError = detalleError;
        this.fechaActualizacion = ahora;
    }
}
