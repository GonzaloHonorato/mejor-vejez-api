package cl.adndigital.mejorvejez.tareas;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Procesos programados vistos por el administrador: lanzar a mano la
 * generación de tareas y consultar las últimas corridas. La autorización
 * (solo administradores) la exige {@link ServicioGeneracionTareas} en cada
 * operación.
 */
@RestController
@RequestMapping("/api/administracion/procesos")
public class ControladorProcesos {

    private final ServicioGeneracionTareas servicio;

    public ControladorProcesos(ServicioGeneracionTareas servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/generacion-tareas")
    public RespuestaEjecucion generarTareas() {
        return servicio.generarManual();
    }

    @GetMapping
    public List<RespuestaEjecucion> listar(@RequestParam String tipo) {
        return servicio.listarEjecuciones(tipo);
    }
}
