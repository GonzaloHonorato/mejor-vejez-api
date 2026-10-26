package cl.adndigital.mejorvejez.planificacion;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Medicamentos de un plan (HU-04). Todas las operaciones son de coordinador;
 * la autorización la exige {@link ServicioDetallesPlan}.
 *
 * <p>Contrato de respuesta: {@code POST} devuelve 201 con {@link
 * RespuestaDetallePlan} (y {@code Location}); los {@code PUT} y {@code POST
 * .../desactivar|reactivar} devuelven 200 con {@link RespuestaDetallePlan};
 * {@code DELETE} devuelve 204.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/planes/{idPlan}/detalles")
public class ControladorDetallesPlan {

    private final ServicioDetallesPlan servicio;

    public ControladorDetallesPlan(ServicioDetallesPlan servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<RespuestaDetallePlan> crear(@PathVariable long idGrupo, @PathVariable long idPlan,
            @Valid @RequestBody SolicitudGuardarDetalle solicitud) {
        RespuestaDetallePlan creado = servicio.crear(idGrupo, idPlan, solicitud);
        return ResponseEntity.created(URI.create(
                "/api/grupos/" + idGrupo + "/planes/" + idPlan + "/detalles/" + creado.idDetallePlan()))
                .body(creado);
    }

    @PutMapping("/{idDetalle}")
    public RespuestaDetallePlan actualizar(@PathVariable long idGrupo, @PathVariable long idPlan,
            @PathVariable long idDetalle, @Valid @RequestBody SolicitudGuardarDetalle solicitud) {
        return servicio.actualizar(idGrupo, idPlan, idDetalle, solicitud);
    }

    @PutMapping("/{idDetalle}/cantidad")
    public RespuestaDetallePlan actualizarCantidad(@PathVariable long idGrupo, @PathVariable long idPlan,
            @PathVariable long idDetalle, @Valid @RequestBody SolicitudActualizarCantidad solicitud) {
        return servicio.actualizarCantidad(idGrupo, idPlan, idDetalle, solicitud);
    }

    @PostMapping("/{idDetalle}/desactivar")
    public RespuestaDetallePlan desactivar(@PathVariable long idGrupo, @PathVariable long idPlan,
            @PathVariable long idDetalle) {
        return servicio.desactivar(idGrupo, idPlan, idDetalle);
    }

    @PostMapping("/{idDetalle}/reactivar")
    public RespuestaDetallePlan reactivar(@PathVariable long idGrupo, @PathVariable long idPlan,
            @PathVariable long idDetalle) {
        return servicio.reactivar(idGrupo, idPlan, idDetalle);
    }

    @DeleteMapping("/{idDetalle}")
    public ResponseEntity<Void> eliminar(@PathVariable long idGrupo, @PathVariable long idPlan,
            @PathVariable long idDetalle) {
        servicio.eliminar(idGrupo, idPlan, idDetalle);
        return ResponseEntity.noContent().build();
    }
}
