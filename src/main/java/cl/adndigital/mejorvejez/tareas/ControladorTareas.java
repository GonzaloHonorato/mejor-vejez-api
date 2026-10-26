package cl.adndigital.mejorvejez.tareas;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agenda de tareas de cuidado y detalle de una tarea (HU-05/06/07). La
 * autorización la exige {@link ServicioConsultaTareas} en cada operación.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/tareas")
public class ControladorTareas {

    private final ServicioConsultaTareas servicio;

    public ControladorTareas(ServicioConsultaTareas servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<RespuestaTarea> listar(@PathVariable long idGrupo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long idAdulto, @RequestParam(required = false) Long idEstado,
            @RequestParam(defaultValue = "false") boolean soloMias) {
        return servicio.listar(idGrupo, new FiltroTareas(desde, hasta, idAdulto, idEstado, soloMias));
    }

    @GetMapping("/{idTarea}")
    public RespuestaTareaDetallada obtener(@PathVariable long idGrupo, @PathVariable long idTarea) {
        return servicio.obtener(idGrupo, idTarea);
    }
}
