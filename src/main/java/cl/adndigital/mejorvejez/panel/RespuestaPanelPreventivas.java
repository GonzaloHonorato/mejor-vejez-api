package cl.adndigital.mejorvejez.panel;

import java.util.List;

import cl.adndigital.mejorvejez.alertas.RespuestaAlerta;

/**
 * Alertas preventivas del panel familiar ({@code GET
 * /api/grupos/{idGrupo}/panel/alertas-preventivas}, HU-10, regla 6 de la
 * Fase 6; Tarea 2): ABIERTA de tipo REPOSICION_MEDICAMENTO o CITA_PROXIMA,
 * hasta {@value CodigosPanel#LIMITE_LISTAS}, por {@code fechaProgramada} y
 * luego {@code idAlerta}, ascendentes.
 */
public record RespuestaPanelPreventivas(List<RespuestaAlerta> alertas) {
}
