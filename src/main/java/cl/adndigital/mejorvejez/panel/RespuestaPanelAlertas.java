package cl.adndigital.mejorvejez.panel;

import java.util.List;

import cl.adndigital.mejorvejez.alertas.RespuestaAlerta;

/**
 * Alertas de atraso del panel familiar ({@code GET
 * /api/grupos/{idGrupo}/panel/alertas}, HU-10, regla 3 de la Fase 6; Tarea
 * 2): solo alertas {@code ATRASO_TAREA} (las preventivas tienen su propia
 * sección, {@link RespuestaPanelPreventivas}). {@code abiertas} cuenta todas
 * las ABIERTA; {@code cerradasUltimos7Dias} cuenta las CERRADA con {@code
 * FECHA_REVISION ≥ ahora −} {@value CodigosPanel#DIAS_ALERTAS_CERRADAS}
 * {@code días}; {@code recientes} son las {@value
 * CodigosPanel#LIMITE_RECIENTES} ABIERTA más nuevas, por {@code
 * fechaDeteccion} y luego {@code idAlerta}, descendentes.
 */
public record RespuestaPanelAlertas(int abiertas, int cerradasUltimos7Dias, List<RespuestaAlerta> recientes) {
}
