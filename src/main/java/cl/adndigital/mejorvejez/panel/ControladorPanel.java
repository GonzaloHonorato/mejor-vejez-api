package cl.adndigital.mejorvejez.panel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel familiar de solo lectura (HU-10, Fase 6): tareas de hoy, próximos
 * horarios y citas (Tarea 1); alertas de atraso y alertas preventivas
 * (Tarea 2). La autorización la exige {@link ServicioPanel} en cada
 * operación.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/panel")
public class ControladorPanel {

    private final ServicioPanel servicio;

    public ControladorPanel(ServicioPanel servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/tareas")
    public RespuestaPanelTareas tareas(@PathVariable long idGrupo, @RequestParam(required = false) Long idAdulto) {
        return servicio.tareas(idGrupo, idAdulto);
    }

    @GetMapping("/proximos-horarios")
    public RespuestaPanelHorarios proximosHorarios(@PathVariable long idGrupo,
            @RequestParam(required = false) Long idAdulto) {
        return servicio.proximosHorarios(idGrupo, idAdulto);
    }

    @GetMapping("/citas")
    public RespuestaPanelCitas citas(@PathVariable long idGrupo, @RequestParam(required = false) Long idAdulto) {
        return servicio.citas(idGrupo, idAdulto);
    }

    @GetMapping("/alertas")
    public RespuestaPanelAlertas alertas(@PathVariable long idGrupo, @RequestParam(required = false) Long idAdulto) {
        return servicio.alertas(idGrupo, idAdulto);
    }

    @GetMapping("/alertas-preventivas")
    public RespuestaPanelPreventivas alertasPreventivas(@PathVariable long idGrupo,
            @RequestParam(required = false) Long idAdulto) {
        return servicio.alertasPreventivas(idGrupo, idAdulto);
    }
}
