package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Datos de un medicamento dentro de un plan ({@code DETALLE_PLAN}), para
 * quien consulta el plan detallado ({@code GET .../planes/{idPlan}}) y como
 * respuesta de cada mutación de detalle ({@link ServicioDetallesPlan}).
 *
 * <p><b>{@code diasSemana} ordenados por {@code ORDEN}.</b> {@link
 * DetallePlan#diasSemana()} solo expone los ids del catálogo {@code
 * DIA_SEMANA}; su {@code ORDEN} vive en {@code CATALOGO_NEGOCIO}. Por eso
 * {@link #desde} recibe {@link CatalogoDiasSemana} (catálogo cacheado) y
 * ordena por {@code ORDEN}, nunca por id: así el contrato no depende de que
 * la identidad de Oracle haya asignado los ids en el mismo orden.
 *
 * @param diasSemana ids de {@code DIA_SEMANA}, ordenados por {@code ORDEN}.
 * @param horas      horas de administración en formato {@code "HH:mm"}, ordenadas.
 */
public record RespuestaDetallePlan(long idDetallePlan, long idPlan, long idMedicamento, String nombreMedicamento,
        long idPresentacion, String concentracion, String instrucciones, BigDecimal dosisCantidad,
        long idUnidadDosis, List<Long> diasSemana, List<String> horas, LocalDate fechaInicio, LocalDate fechaFin,
        BigDecimal cantidadInicial, BigDecimal cantidadDisponible, LocalDate fechaEstimadaTermino,
        String modoCalculoTermino, Long idResponsablePredeterminado, long idEstado, OffsetDateTime fechaCreacion,
        OffsetDateTime fechaActualizacion) {

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Debe llamarse dentro de la transacción que cargó {@code detalle}
     * ({@code spring.jpa.open-in-view=false}): lee sus colecciones LAZY.
     */
    public static RespuestaDetallePlan desde(DetallePlan detalle, CatalogoDiasSemana catalogoDias) {
        List<Long> diasOrdenados = catalogoDias.ordenar(detalle.diasSemana());
        List<String> horasFormateadas = detalle.horas().stream().map(FORMATO_HORA::format).toList();
        return new RespuestaDetallePlan(detalle.getIdDetallePlan(), detalle.getIdPlan(), detalle.getIdMedicamento(),
                detalle.getNombreMedicamento(), detalle.getIdPresentacion(), detalle.getConcentracion(),
                detalle.getInstrucciones(), detalle.getDosisCantidad(), detalle.getIdUnidadDosis(), diasOrdenados,
                horasFormateadas, detalle.getFechaInicio(), detalle.getFechaFin(), detalle.getCantidadInicial(),
                detalle.getCantidadDisponible(), detalle.getFechaEstimadaTermino(), detalle.getModoCalculoTermino(),
                detalle.getIdResponsablePredeterminado(), detalle.getIdEstado(), detalle.getFechaCreacion(),
                detalle.getFechaActualizacion());
    }
}
