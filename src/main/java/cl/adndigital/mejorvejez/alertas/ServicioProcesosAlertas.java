package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.interno.ResumenCorrida;
import cl.adndigital.mejorvejez.interno.ResumenReportes;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Corridas manuales de los procesos que ahora viven en los microservicios de
 * Alertas y de Reportes (Fase 9): exige administrador, reenvía la llamada
 * con {@link ClienteServicios} y audita el resultado con {@link
 * CorridaAlertas}.
 */
@Service
class ServicioProcesosAlertas {

    private final ClienteServicios cliente;
    private final ServicioAutorizacion servicioAutorizacion;
    private final CorridaAlertas corridaAlertas;

    ServicioProcesosAlertas(ClienteServicios cliente, ServicioAutorizacion servicioAutorizacion,
            CorridaAlertas corridaAlertas) {
        this.cliente = cliente;
        this.servicioAutorizacion = servicioAutorizacion;
        this.corridaAlertas = corridaAlertas;
    }

    /** {@code POST /api/administracion/procesos/deteccion-atrasos}: reenvía a Alertas. */
    ResumenCorrida deteccionAtrasos() {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        return corridaAlertas.reenviarAuditado(administrador, AccionesAlertas.EJECUTAR_DETECCION_ATRASOS,
                cliente::deteccionAtrasos, ResumenCorrida::idEjecucion, ServicioProcesosAlertas::detallesCorrida);
    }

    /** {@code POST /api/administracion/procesos/alertas-preventivas}: reenvía a Alertas. */
    ResumenCorrida alertasPreventivas() {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        return corridaAlertas.reenviarAuditado(administrador, AccionesAlertas.EJECUTAR_ALERTAS_PREVENTIVAS,
                cliente::alertasPreventivas, ResumenCorrida::idEjecucion, ServicioProcesosAlertas::detallesCorrida);
    }

    /** {@code POST /api/administracion/procesos/reporte-semanal}: reenvía a Reportes, con {@code inicio} si viene. */
    ResumenReportes reporteSemanal(LocalDate inicio) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        return corridaAlertas.reenviarAuditado(administrador, AccionesAlertas.EJECUTAR_REPORTE_SEMANAL,
                () -> cliente.reporteSemanal(inicio), ResumenReportes::idEjecucion,
                ServicioProcesosAlertas::detallesReportes);
    }

    private static Map<String, Object> detallesCorrida(ResumenCorrida resumen) {
        return Map.of("creadas", resumen.creadas(), "errores", resumen.errores());
    }

    private static Map<String, Object> detallesReportes(ResumenReportes resumen) {
        return Map.of("gruposProcesados", resumen.gruposProcesados(), "enviados", resumen.enviados(), "fallidos",
                resumen.fallidos(), "omitidos", resumen.omitidos());
    }
}
