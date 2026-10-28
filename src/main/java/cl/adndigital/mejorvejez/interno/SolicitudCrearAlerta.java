package cl.adndigital.mejorvejez.interno;

/**
 * Cuerpo de {@code POST /api/interno/alertas} (Fase 9, TSA-61): el
 * microservicio de Alertas pide crear una alerta de un tipo, con el origen
 * que corresponda ({@code idTarea} para {@code ATRASO_TAREA} y {@code
 * CITA_PROXIMA}; {@code idDetallePlan} para {@code
 * REPOSICION_MEDICAMENTO}). {@link ServicioInternoAlertas} revalida todo con
 * bloqueo antes de crear (regla 5 de la Fase 5): es idempotente por clave.
 */
public record SolicitudCrearAlerta(String tipoAlerta, Long idTarea, Long idDetallePlan) {
}
