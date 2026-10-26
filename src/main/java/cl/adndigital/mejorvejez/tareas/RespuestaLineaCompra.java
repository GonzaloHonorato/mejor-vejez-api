package cl.adndigital.mejorvejez.tareas;

import java.math.BigDecimal;

/** Línea de medicamento de una tarea de {@code COMPRA_RETIRO_MEDICAMENTOS} (HU-05). */
public record RespuestaLineaCompra(long idDetalleCompra, long idMedicamento, String nombreMedicamento,
        long idPresentacion, String dosisIndicada, BigDecimal cantidadSolicitada, long idUnidadCantidad,
        String observaciones) {
}
