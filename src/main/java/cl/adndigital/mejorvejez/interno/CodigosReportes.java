package cl.adndigital.mejorvejez.interno;

/**
 * Valores exactos (nombres de tabla/dominio y códigos de estado) de {@code
 * ENVIO_REPORTE} y {@code ENVIO_REPORTE_DESTINATARIO} (Fase 9, TSA-61, V5).
 * Pública: {@link ServicioInternoReportes} y sus pruebas la comparten como
 * única fuente de verdad, igual que {@code tareas.CodigosTareas} / {@code
 * alertas.CodigosAlertas} / {@code relevos.CodigosRelevos}.
 */
public final class CodigosReportes {

    public static final String TABLA_ENVIO_REPORTE = "ENVIO_REPORTE";
    public static final String TABLA_ENVIO_REPORTE_DESTINATARIO = "ENVIO_REPORTE_DESTINATARIO";
    public static final String CAMPO_ESTADO = "ESTADO";

    public static final String ESTADO_ENVIO_PENDIENTE = "PENDIENTE";
    public static final String ESTADO_ENVIO_ENVIADO = "ENVIADO";
    public static final String ESTADO_ENVIO_FALLIDO = "FALLIDO";

    /** Máximo de intentos (rondas de {@code PUT .../resultado}) antes de fijar el envío en FALLIDO. */
    public static final int MAXIMO_INTENTOS = 3;

    private CodigosReportes() {
    }
}
