package cl.adndigital.mejorvejez.interno;

import java.util.List;

/**
 * Respuesta de {@code GET /api/interno/alertas/candidatos-preventivos}
 * (Fase 9, TSA-61): las citas próximas y las reposiciones de medicamento
 * candidatas, ambas sin una alerta vigente todavía.
 */
public record CandidatosPreventivos(List<CandidatoCita> citas, List<CandidatoReposicion> reposiciones) {
}
