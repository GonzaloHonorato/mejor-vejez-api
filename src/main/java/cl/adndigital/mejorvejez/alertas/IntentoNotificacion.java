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

import cl.adndigital.mejorvejez.comun.TextoAcotado;

/**
 * Intento de envío de una {@link AlertaCuidado} a un destinatario por un
 * canal ({@code INTENTO_NOTIFICACION}, TSA-45). Un intento por alerta,
 * destinatario, canal y número ({@code UQ_INTENTO_ALERTA_DEST_CANAL_NUM}):
 * {@code NUMERO_INTENTO} es siempre el máximo del par (destinatario, canal)
 * más uno (regla 9 de la Fase 5). En producción nace siempre {@code
 * PENDIENTE} ({@link #pendiente}, reservado para que el microservicio de
 * Alertas lo envíe fuera de esta transacción y confirme el resultado con
 * {@link #pasarAEstadoFinal}); el constructor con el resultado ya puesto
 * ({@code ENVIADO} | {@code FALLIDO}) queda solo para las pruebas.
 *
 * <p>{@code DETALLE_ERROR} y {@code REFERENCIA_PROVEEDOR} se truncan siempre
 * a 500 y 200 bytes UTF-8 respectivamente ({@link TextoAcotado}), igual que
 * {@code EJECUCION_PROCESO.RESUMEN_ERRORES} en {@code tareas}.
 */
@Entity
@Table(name = "INTENTO_NOTIFICACION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IntentoNotificacion {

    /** Largo máximo de {@code DETALLE_ERROR}, en bytes ({@code VARCHAR2(500)}, semántica BYTE). */
    private static final int LARGO_MAXIMO_DETALLE_ERROR = 500;

    /** Largo máximo de {@code REFERENCIA_PROVEEDOR}, en bytes ({@code VARCHAR2(200)}, semántica BYTE). */
    private static final int LARGO_MAXIMO_REFERENCIA_PROVEEDOR = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_INTENTO")
    private Long idIntento;

    @Column(name = "ID_ALERTA", nullable = false)
    private Long idAlerta;

    @Column(name = "ID_USUARIO_DESTINATARIO", nullable = false)
    private Long idUsuarioDestinatario;

    @Column(name = "CANAL", nullable = false, length = 20)
    private String canal;

    @Column(name = "NUMERO_INTENTO", nullable = false)
    private Integer numeroIntento;

    @Column(name = "ID_ESTADO_ENVIO", nullable = false)
    private Long idEstadoEnvio;

    @Column(name = "FECHA_INTENTO", nullable = false)
    private OffsetDateTime fechaIntento;

    @Column(name = "DETALLE_ERROR", length = LARGO_MAXIMO_DETALLE_ERROR)
    private String detalleError;

    @Column(name = "REFERENCIA_PROVEEDOR", length = LARGO_MAXIMO_REFERENCIA_PROVEEDOR)
    private String referenciaProveedor;

    /** Intento nuevo, ya con su resultado ({@code idEstadoEnvio} ENVIADO | FALLIDO). */
    public IntentoNotificacion(long idAlerta, long idUsuarioDestinatario, String canal, int numeroIntento,
            long idEstadoEnvio, OffsetDateTime fecha, String detalleError, String referenciaProveedor) {
        this.idAlerta = idAlerta;
        this.idUsuarioDestinatario = idUsuarioDestinatario;
        this.canal = canal;
        this.numeroIntento = numeroIntento;
        this.idEstadoEnvio = idEstadoEnvio;
        this.fechaIntento = fecha;
        this.detalleError = TextoAcotado.truncarABytes(detalleError, LARGO_MAXIMO_DETALLE_ERROR);
        this.referenciaProveedor = TextoAcotado.truncarABytes(referenciaProveedor, LARGO_MAXIMO_REFERENCIA_PROVEEDOR);
    }

    private IntentoNotificacion(long idAlerta, long idUsuarioDestinatario, String canal, int numeroIntento,
            long idEstadoPendiente, OffsetDateTime fechaReserva) {
        this.idAlerta = idAlerta;
        this.idUsuarioDestinatario = idUsuarioDestinatario;
        this.canal = canal;
        this.numeroIntento = numeroIntento;
        this.idEstadoEnvio = idEstadoPendiente;
        this.fechaIntento = fechaReserva;
    }

    /**
     * Intento nuevo, reservado en {@code PENDIENTE} (Fase 9, TSA-61): nace
     * sin resultado, lo asigna {@code interno.ServicioReservaIntentos} al
     * entregarle el par (destinatario, canal) al microservicio de Alertas,
     * que lo envía fuera de esta transacción y confirma el resultado con
     * {@code PUT /api/interno/intentos/{idIntento}}.
     */
    public static IntentoNotificacion pendiente(long idAlerta, long idUsuarioDestinatario, String canal,
            int numeroIntento, long idEstadoPendiente, OffsetDateTime fechaReserva) {
        return new IntentoNotificacion(idAlerta, idUsuarioDestinatario, canal, numeroIntento, idEstadoPendiente,
                fechaReserva);
    }

    /**
     * Si el intento está {@code PENDIENTE} ({@code idEstadoPendiente} del
     * catálogo), para decidir si ya venció su ventana de confirmación o si
     * un par ya tiene una reserva vigente (Fase 9, TSA-61).
     */
    public boolean esPendiente(long idEstadoPendiente) {
        return idEstadoEnvio != null && idEstadoEnvio.longValue() == idEstadoPendiente;
    }

    /**
     * Pasa de {@code PENDIENTE} a un estado final ({@code ENVIADO} | {@code
     * FALLIDO}), con su detalle y referencia (Fase 9, TSA-61): lo usa tanto
     * la expiración de un {@code PENDIENTE} huérfano (más de 10 minutos sin
     * confirmación) como el resultado que entrega el microservicio de
     * Alertas. No cambia {@code FECHA_INTENTO}: sigue siendo el instante en
     * que se reservó.
     */
    public void pasarAEstadoFinal(long idEstadoFinal, String detalleError, String referenciaProveedor) {
        this.idEstadoEnvio = idEstadoFinal;
        this.detalleError = TextoAcotado.truncarABytes(detalleError, LARGO_MAXIMO_DETALLE_ERROR);
        this.referenciaProveedor = TextoAcotado.truncarABytes(referenciaProveedor, LARGO_MAXIMO_REFERENCIA_PROVEEDOR);
    }
}
