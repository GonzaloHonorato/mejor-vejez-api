package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asigna o reasigna el responsable de una tarea de cuidado (HU-06, TSA-39).
 * La autorización y las reglas de negocio las exige {@link
 * ServicioAsignacionTareas}.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/tareas")
public class ControladorAsignacionTareas {

    private final ServicioAsignacionTareas servicio;

    public ControladorAsignacionTareas(ServicioAsignacionTareas servicio) {
        this.servicio = servicio;
    }

    @PutMapping("/{idTarea}/asignacion")
    public RespuestaTarea asignar(@PathVariable long idGrupo, @PathVariable long idTarea,
            @Valid @RequestBody SolicitudAsignarTarea solicitud) {
        return servicio.asignar(idGrupo, idTarea, solicitud);
    }
}
