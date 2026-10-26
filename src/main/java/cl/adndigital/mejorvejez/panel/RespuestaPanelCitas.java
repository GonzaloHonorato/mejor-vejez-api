package cl.adndigital.mejorvejez.panel;

import java.util.List;

import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

/**
 * Citas próximas del panel familiar ({@code GET
 * /api/grupos/{idGrupo}/panel/citas}, HU-10, regla 5 de la Fase 6): tareas
 * CITAS_CONTROLES_MEDICOS, PENDIENTE o EN_CURSO, con {@code ahora ≤
 * FECHA_HORARIO < ahora +} {@value CodigosPanel#DIAS_CITAS} {@code días},
 * hasta {@value CodigosPanel#LIMITE_LISTAS}, las más próximas primero. Cada
 * una trae médico y lugar, porque el ensamblador de F4 ya los incluye.
 */
public record RespuestaPanelCitas(List<RespuestaTarea> citas) {
}
