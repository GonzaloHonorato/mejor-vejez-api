package cl.adndigital.mejorvejez.notificaciones;

/**
 * Un canal dentro de {@code PUT /api/yo/canales} (regla 17 de la Fase 5).
 * La validación de los valores exactos (canal admitido, formato del
 * destino, duplicados) se hace en {@link ServicioCanales}, para controlar
 * el texto exacto de cada mensaje de error (422).
 */
public record SolicitudCanal(String canal, String destino, boolean habilitado) {
}
