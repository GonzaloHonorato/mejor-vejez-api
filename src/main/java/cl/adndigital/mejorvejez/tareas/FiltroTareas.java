package cl.adndigital.mejorvejez.tareas;

import java.time.LocalDate;

/**
 * Filtros de la agenda de tareas ({@code GET /api/grupos/{idGrupo}/tareas}).
 * {@code desde}/{@code hasta} nulos se normalizan en {@link
 * ServicioConsultaTareas} (regla de la Fase 4: {@code desde} nulo = hoy,
 * {@code hasta} nulo = {@code desde}). {@code soloMias} filtra por la
 * asignación {@code ACTIVO} del usuario actual.
 */
public record FiltroTareas(LocalDate desde, LocalDate hasta, Long idAdulto, Long idEstado, boolean soloMias) {
}
