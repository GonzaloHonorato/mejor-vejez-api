package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta manual de una tarea de cuidado por el coordinador (HU-05, TSA-41):
 * compra/retiro de medicamentos o cita/control médico. La autorización y las
 * reglas de negocio las exige {@link ServicioTareasManuales}.
 *
 * <p>Siempre 201 (sin {@code Location}, como {@code
 * ControladorMedicamentos}: no existe un {@code GET} de una tarea
 * individual distinto del ya expuesto por {@link ControladorTareas}). Mismo
 * {@code @RequestMapping} que {@link ControladorTareas}; Spring distingue
 * por método HTTP ({@code GET} allá, {@code POST} aquí).
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/tareas")
public class ControladorTareasManuales {

    private final ServicioTareasManuales servicio;

    public ControladorTareasManuales(ServicioTareasManuales servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<RespuestaTarea> crear(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudCrearTareaManual solicitud) {
        RespuestaTarea respuesta = servicio.crear(idGrupo, solicitud);
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}
