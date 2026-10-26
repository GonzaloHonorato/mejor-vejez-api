package cl.adndigital.mejorvejez.alertas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Dispara los procesos programados de alertas (regla 12 de la Fase 5), en
 * America/Santiago: la detección de atrasos (TSA-43), cada 5 minutos
 * ({@code mejorvejez.alertas.cron-deteccion}), y las alertas preventivas de
 * cita y reposición (TSA-44), cada hora ({@code
 * mejorvejez.alertas.cron-preventivas}). Se apaga con {@code
 * mejorvejez.procesos.habilitados=false} ({@code PROCESOS_HABILITADOS}),
 * igual que {@code tareas.ProgramadorProcesos}; aun si corren dos réplicas,
 * los dos detectores son idempotentes.
 */
@Component
@ConditionalOnProperty(name = "mejorvejez.procesos.habilitados", havingValue = "true", matchIfMissing = true)
public class ProgramadorAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(ProgramadorAlertas.class);

    private final ServicioDeteccionAtrasos servicioDeteccion;
    private final ServicioAlertasPreventivas servicioAlertasPreventivas;

    public ProgramadorAlertas(ServicioDeteccionAtrasos servicioDeteccion,
            ServicioAlertasPreventivas servicioAlertasPreventivas) {
        this.servicioDeteccion = servicioDeteccion;
        this.servicioAlertasPreventivas = servicioAlertasPreventivas;
    }

    /** Corre la detección de atrasos y registra el resultado en el log; nunca propaga excepciones al planificador. */
    @Scheduled(cron = "${mejorvejez.alertas.cron-deteccion}", zone = "America/Santiago")
    public void detectarAtrasos() {
        try {
            RespuestaEjecucion r = servicioDeteccion.ejecutarProgramado();
            LOG.info("Detección de atrasos {}: ejecución {} (estado {}), {} creadas, {} errores", r.tipoEjecucion(),
                    r.idEjecucion(), r.idEstado(), r.cantidadCreada(), r.cantidadErrores());
        } catch (RuntimeException ex) {
            LOG.error("Detección de atrasos: la corrida programada falló", ex);
        }
    }

    /** Corre las alertas preventivas y registra el resultado en el log; nunca propaga excepciones al planificador. */
    @Scheduled(cron = "${mejorvejez.alertas.cron-preventivas}", zone = "America/Santiago")
    public void generarAlertasPreventivas() {
        try {
            RespuestaEjecucion r = servicioAlertasPreventivas.ejecutarProgramado();
            LOG.info("Alertas preventivas {}: ejecución {} (estado {}), {} creadas, {} errores", r.tipoEjecucion(),
                    r.idEjecucion(), r.idEstado(), r.cantidadCreada(), r.cantidadErrores());
        } catch (RuntimeException ex) {
            LOG.error("Alertas preventivas: la corrida programada falló", ex);
        }
    }
}
