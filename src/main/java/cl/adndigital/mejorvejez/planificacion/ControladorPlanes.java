package cl.adndigital.mejorvejez.planificacion;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planes de medicamentos de un adulto mayor (HU-04). La autorización la
 * exige {@link ServicioPlanes} en cada operación.
 *
 * <p>Contrato de respuesta: {@code POST .../planes} devuelve 201 con
 * {@link RespuestaPlan} (y {@code Location}); {@code PUT .../planes/{idPlan}},
 * {@code POST .../desactivar} y {@code POST .../reactivar} devuelven 200 con
 * {@link RespuestaPlan} actualizado; {@code DELETE} devuelve 204.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/planes")
public class ControladorPlanes {

    private final ServicioPlanes servicio;

    public ControladorPlanes(ServicioPlanes servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<RespuestaPlan> listar(@PathVariable long idGrupo, @RequestParam(required = false) Long idAdulto,
            @RequestParam(defaultValue = "false") boolean incluirInactivos) {
        return servicio.listar(idGrupo, idAdulto, incluirInactivos);
    }

    @GetMapping("/{idPlan}")
    public RespuestaPlanDetallado obtener(@PathVariable long idGrupo, @PathVariable long idPlan) {
        return servicio.obtener(idGrupo, idPlan);
    }

    @PostMapping
    public ResponseEntity<RespuestaPlan> crear(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudCrearPlan solicitud) {
        RespuestaPlan creado = servicio.crear(idGrupo, solicitud);
        return ResponseEntity.created(URI.create("/api/grupos/" + idGrupo + "/planes/" + creado.idPlan()))
                .body(creado);
    }

    @PutMapping("/{idPlan}")
    public RespuestaPlan actualizar(@PathVariable long idGrupo, @PathVariable long idPlan,
            @Valid @RequestBody SolicitudActualizarPlan solicitud) {
        return servicio.actualizar(idGrupo, idPlan, solicitud);
    }

    @PostMapping("/{idPlan}/desactivar")
    public RespuestaPlan desactivar(@PathVariable long idGrupo, @PathVariable long idPlan) {
        return servicio.desactivar(idGrupo, idPlan);
    }

    @PostMapping("/{idPlan}/reactivar")
    public RespuestaPlan reactivar(@PathVariable long idGrupo, @PathVariable long idPlan) {
        return servicio.reactivar(idGrupo, idPlan);
    }

    @DeleteMapping("/{idPlan}")
    public ResponseEntity<Void> eliminar(@PathVariable long idGrupo, @PathVariable long idPlan) {
        servicio.eliminar(idGrupo, idPlan);
        return ResponseEntity.noContent().build();
    }
}
