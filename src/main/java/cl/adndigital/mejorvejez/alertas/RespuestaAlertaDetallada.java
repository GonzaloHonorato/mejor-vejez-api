package cl.adndigital.mejorvejez.alertas;

import java.util.List;

/** El detalle de una alerta ({@code GET} | {@code POST .../alertas/{idAlerta}[/cierre]}), con su historial de intentos. */
public record RespuestaAlertaDetallada(RespuestaAlerta alerta, List<RespuestaIntento> intentos) {
}
