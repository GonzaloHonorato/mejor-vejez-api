package cl.adndigital.mejorvejez.alertas;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Corridas manuales de los procesos de alertas por el administrador (regla
 * 12 de la Fase 5), bajo el mismo prefijo que {@code
 * tareas.ControladorProcesos} (el listado {@code GET
 * /api/administracion/procesos?tipo=} vive allí). La autorización (solo
 * administradores) la exige el servicio.
 */
@RestController
@RequestMapping("/api/administracion/procesos")
public class ControladorProcesosAlertas {

    private final ServicioDeteccionAtrasos servicioDeteccion;
    private final ServicioAlertasPreventivas servicioAlertasPreventivas;

    public ControladorProcesosAlertas(ServicioDeteccionAtrasos servicioDeteccion,
            ServicioAlertasPreventivas servicioAlertasPreventivas) {
        this.servicioDeteccion = servicioDeteccion;
        this.servicioAlertasPreventivas = servicioAlertasPreventivas;
    }

    @PostMapping("/deteccion-atrasos")
    public RespuestaEjecucion detectarAtrasos() {
        return servicioDeteccion.ejecutarManual();
    }

    @PostMapping("/alertas-preventivas")
    public RespuestaEjecucion alertasPreventivas() {
        return servicioAlertasPreventivas.ejecutarManual();
    }
}
