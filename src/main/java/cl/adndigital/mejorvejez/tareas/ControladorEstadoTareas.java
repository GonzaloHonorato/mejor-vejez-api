package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmación de la ejecución de una tarea de cuidado, o su cancelación
 * (HU-07, TSA-40). La autorización y las reglas de negocio las exige {@link
 * ServicioEstadoTareas}.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/tareas")
public class ControladorEstadoTareas {

    private final ServicioEstadoTareas servicio;

    public ControladorEstadoTareas(ServicioEstadoTareas servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/{idTarea}/estado")
    public RespuestaTarea cambiarEstado(@PathVariable long idGrupo, @PathVariable long idTarea,
            @Valid @RequestBody SolicitudCambiarEstadoTarea solicitud) {
        return servicio.cambiarEstado(idGrupo, idTarea, solicitud);
    }
}
