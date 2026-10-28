package cl.adndigital.mejorvejez.interno;

/**
 * Resultado de una corrida del reporte semanal, tal como lo devuelve el
 * microservicio de Reportes (Fase 9): el mismo contrato, con un record
 * propio del Core. {@code idEjecucion} puede venir {@code null} si el
 * servicio remoto no llegó a registrar la ejecución.
 */
public record ResumenReportes(Long idEjecucion, int gruposProcesados, int enviados, int fallidos, int omitidos) {
}
