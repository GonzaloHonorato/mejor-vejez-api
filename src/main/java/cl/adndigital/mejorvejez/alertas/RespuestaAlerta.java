package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una alerta de cuidado (HU-08, TSA-43/44/47), tal como la ve la bandeja del
 * grupo y su detalle. {@code tituloTarea}/{@code idEstadoTarea} vienen de la
 * tarea cuando la alerta nació de una ({@code idTarea} no nulo);
 * {@code nombreMedicamento} viene de {@code DetallePlan.nombreMedicamento}
 * cuando nació de un detalle del plan ({@code idDetallePlan} no nulo). Nunca
 * incluye destinos (correo, teléfono).
 */
public record RespuestaAlerta(long idAlerta, String tipoAlerta, Long idTarea, String tituloTarea,
        Long idEstadoTarea, Long idDetallePlan, String nombreMedicamento, long idAdultoMayor, String nombreAdulto,
        OffsetDateTime fechaProgramada, String mensajeAlerta, long idEstado, OffsetDateTime fechaDeteccion,
        RespuestaRevisor revisor, OffsetDateTime fechaRevision, String observacionCierre) {

    /** Ruling final de F4 (reutilizado en F5): los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaAlerta {
        fechaProgramada = ZonaNegocio.enZona(fechaProgramada);
        fechaDeteccion = ZonaNegocio.enZona(fechaDeteccion);
        fechaRevision = ZonaNegocio.enZona(fechaRevision);
    }
}
