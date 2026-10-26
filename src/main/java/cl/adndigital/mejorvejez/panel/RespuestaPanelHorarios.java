package cl.adndigital.mejorvejez.panel;

import java.util.List;

import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

/**
 * Próximos horarios del panel familiar ({@code GET
 * /api/grupos/{idGrupo}/panel/proximos-horarios}, HU-10, regla 4 de la Fase
 * 6): tareas de MEDICAMENTOS o COMPRA_RETIRO_MEDICAMENTOS, PENDIENTE o
 * EN_CURSO, con {@code ahora ≤ FECHA_HORARIO < ahora +}
 * {@value CodigosPanel#HORAS_PROXIMOS_HORARIOS} {@code h}, hasta
 * {@value CodigosPanel#LIMITE_LISTAS}, las más próximas primero.
 */
public record RespuestaPanelHorarios(List<RespuestaTarea> tareas) {
}
