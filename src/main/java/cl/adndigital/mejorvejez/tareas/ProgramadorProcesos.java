package cl.adndigital.mejorvejez.tareas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara los procesos programados del backend. Hoy solo la generación
 * diaria de tareas de medicamentos (HU-05), a las 00:05 en America/Santiago
 * ({@code mejorvejez.tareas.cron-generacion}). Se desactiva con {@code
 * mejorvejez.procesos.habilitados=false} (p. ej. en una réplica que no debe
 * correrlos; aun si corren dos, el generador es idempotente).
 */
@Component
@ConditionalOnProperty(name = "mejorvejez.procesos.habilitados", havingValue = "true", matchIfMissing = true)
public class ProgramadorProcesos {

    private static final Logger LOG = LoggerFactory.getLogger(ProgramadorProcesos.class);

    private final ServicioGeneracionTareas servicioGeneracion;

    public ProgramadorProcesos(ServicioGeneracionTareas servicioGeneracion) {
        this.servicioGeneracion = servicioGeneracion;
    }

    /** Corre la generación diaria y registra el resultado en el log; nunca propaga excepciones al planificador. */
    @Scheduled(cron = "${mejorvejez.tareas.cron-generacion}", zone = "America/Santiago")
    public void generarTareas() {
        try {
            RespuestaEjecucion r = servicioGeneracion.generarProgramado();
            LOG.info("Generación de tareas {}: ejecución {} (estado {}), {} creadas, {} errores", r.tipoEjecucion(),
                    r.idEjecucion(), r.idEstado(), r.cantidadCreada(), r.cantidadErrores());
        } catch (RuntimeException ex) {
            LOG.error("Generación de tareas: la corrida programada falló", ex);
        }
    }
}
