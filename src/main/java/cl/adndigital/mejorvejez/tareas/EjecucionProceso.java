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

import cl.adndigital.mejorvejez.comun.TextoAcotado;

/**
 * Corrida de uno de los procesos programados del backend ({@code
 * EJECUCION_PROCESO}, INC-21): hoy {@code GENERACION_TAREAS} ({@link
 * CodigosTareas#TIPO_EJECUCION_GENERACION}); {@code
 * DETECCION_ATRASOS} / {@code ALERTAS_PREVENTIVAS} son de F5. {@code
 * RESUMEN_ERRORES} se trunca siempre a 2000 BYTES en UTF-8 ({@link
 * #finalizar}), no a 2000 caracteres: la columna es {@code VARCHAR2(2000)}
 * con semántica BYTE y los mensajes llevan tildes ({@link TextoAcotado}).
 */
@Entity
@Table(name = "EJECUCION_PROCESO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EjecucionProceso {

    /** Largo máximo de {@code RESUMEN_ERRORES}, en bytes ({@code VARCHAR2(2000)}, semántica BYTE). */
    private static final int LARGO_MAXIMO_RESUMEN_ERRORES = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_EJECUCION")
    private Long idEjecucion;

    @Column(name = "TIPO_EJECUCION", nullable = false, length = 30)
    private String tipoEjecucion;

    @Column(name = "INICIO_VENTANA", nullable = false)
    private OffsetDateTime inicioVentana;

    @Column(name = "FIN_VENTANA", nullable = false)
    private OffsetDateTime finVentana;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_INICIO", nullable = false)
    private OffsetDateTime fechaInicio;

    @Column(name = "FECHA_FIN")
    private OffsetDateTime fechaFin;

    @Column(name = "CANTIDAD_CREADA", nullable = false)
    private Integer cantidadCreada;

    @Column(name = "CANTIDAD_ERRORES", nullable = false)
    private Integer cantidadErrores;

    @Column(name = "RESUMEN_ERRORES", length = LARGO_MAXIMO_RESUMEN_ERRORES)
    private String resumenErrores;

    /** Corrida nueva ({@code INICIADA}), sin creaciones ni errores todavía. */
    public EjecucionProceso(String tipo, OffsetDateTime inicioVentana, OffsetDateTime finVentana,
            long idEstadoIniciada, OffsetDateTime ahora) {
        this.tipoEjecucion = tipo;
        this.inicioVentana = inicioVentana;
        this.finVentana = finVentana;
        this.idEstado = idEstadoIniciada;
        this.fechaInicio = ahora;
        this.cantidadCreada = 0;
        this.cantidadErrores = 0;
    }

    /**
     * Cierra la corrida ({@code EXITOSA} | {@code PARCIAL} | {@code
     * FALLIDA}) con sus totales y el resumen de errores, truncado a {@value
     * #LARGO_MAXIMO_RESUMEN_ERRORES} bytes UTF-8 sin cortar un carácter
     * multibyte.
     */
    public void finalizar(long idEstado, int cantidadCreada, int cantidadErrores, String resumenErrores,
            OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.cantidadCreada = cantidadCreada;
        this.cantidadErrores = cantidadErrores;
        this.resumenErrores = TextoAcotado.truncarABytes(resumenErrores, LARGO_MAXIMO_RESUMEN_ERRORES);
        this.fechaFin = ahora;
    }
}
