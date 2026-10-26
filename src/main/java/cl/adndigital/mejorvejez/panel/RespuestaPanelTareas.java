package cl.adndigital.mejorvejez.panel;

import java.time.LocalDate;
import java.util.List;

import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

/**
 * Resumen de tareas de hoy del panel familiar ({@code GET
 * /api/grupos/{idGrupo}/panel/tareas}, HU-10, regla 2 de la Fase 6): los
 * conteos por estado del día en curso (America/Santiago, {@code [00:00 de
 * hoy, 00:00 de mañana)}) y las tareas atrasadas (PENDIENTE o EN_CURSO,
 * vencidas, de hasta {@value CodigosPanel#DIAS_RETROSPECTIVA_ATRASADAS} días
 * atrás), hasta {@value CodigosPanel#LIMITE_LISTAS}, las más antiguas
 * primero.
 */
public record RespuestaPanelTareas(LocalDate fecha, int pendientes, int enCurso, int completadas, int noRealizadas,
        int canceladas, int atrasadas, List<RespuestaTarea> tareasAtrasadas) {
}
