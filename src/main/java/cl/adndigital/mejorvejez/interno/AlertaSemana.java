package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una alerta detectada dentro del período (Fase 9, TSA-61): parte de {@link
 * DatosSemana#alertas}. {@code codigoEstadoAlCierre} es {@code CERRADA} si
 * {@code FECHA_REVISION} es anterior al cierre, {@code ABIERTA} en cualquier
 * otro caso (sin revisar, o revisada después del cierre); {@code
 * fechaRevision} es la real de la alerta (puede ser posterior al cierre o
 * {@code null}), no una "al cierre".
 */
public record AlertaSemana(long idAlerta, long idAdulto, String tipoAlerta, OffsetDateTime fechaDeteccion,
        String codigoEstadoAlCierre, OffsetDateTime fechaRevision) {

    /** Los instantes salen siempre con el offset de America/Santiago (mismo ruling de F4). */
    public AlertaSemana {
        fechaDeteccion = ZonaNegocio.enZona(fechaDeteccion);
        fechaRevision = ZonaNegocio.enZona(fechaRevision);
    }
}
