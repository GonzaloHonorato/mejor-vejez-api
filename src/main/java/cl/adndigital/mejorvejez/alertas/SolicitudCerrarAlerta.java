package cl.adndigital.mejorvejez.alertas;

/**
 * Datos para cerrar una alerta de cuidado (TSA-47, TSA-9, {@code POST
 * .../alertas/{idAlerta}/cierre}). Sin anotaciones de validación (regla 13
 * de la Fase 5): la observación se valida en {@link ServicioCierreAlertas},
 * siempre después de exigir el permiso, para que un colaborador sin permiso
 * reciba 403 y no 422.
 */
public record SolicitudCerrarAlerta(String observacion) {
}
