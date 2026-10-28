package cl.adndigital.mejorvejez.interno;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de {@code POST /api/interno/reportes/envios} (Fase 9, TSA-61):
 * {@code inicioPeriodo} debe ser un lunes (el inicio del período) y no
 * puede faltar.
 */
public record SolicitudReservarEnvio(long idGrupo,
        @NotNull(message = "El inicio del período es obligatorio.") LocalDate inicioPeriodo) {
}
