package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;

/**
 * Cuerpo de {@code POST /api/interno/ejecuciones} (Fase 9, TSA-61): el
 * microservicio de Alertas o el de Reportes registra el inicio de una
 * corrida propia ({@code tipoEjecucion}: por ejemplo {@code
 * DETECCION_ATRASOS} o {@code ALERTAS_PREVENTIVAS}), con su ventana. Vía
 * {@code RegistroEjecuciones#iniciar}.
 */
public record SolicitudIniciarEjecucion(String tipoEjecucion, OffsetDateTime inicioVentana,
        OffsetDateTime finVentana) {
}
