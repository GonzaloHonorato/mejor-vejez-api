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
 * más uno (regla 9 de la Fase 5). El estado {@code PENDIENTE} del catálogo no
 * se usa: el intento nace ya con su resultado ({@code ENVIADO} | {@code
 * FALLIDO}).
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
}
