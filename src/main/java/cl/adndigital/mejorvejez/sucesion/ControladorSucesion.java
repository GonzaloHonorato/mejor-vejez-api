package cl.adndigital.mejorvejez.sucesion;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sucesión de la coordinación (HU-13, TSA-53, regla 15 de la Fase 7):
 * proponer, consultar, aceptar, rechazar, cancelar y completar. La
 * autorización y las reglas de negocio las exigen {@link ServicioSucesion} y,
 * para completar, {@link ServicioCompletarSucesion} en cada operación.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/sucesion")
public class ControladorSucesion {

    private final ServicioSucesion servicio;
    private final ServicioCompletarSucesion servicioCompletar;

    public ControladorSucesion(ServicioSucesion servicio, ServicioCompletarSucesion servicioCompletar) {
        this.servicio = servicio;
        this.servicioCompletar = servicioCompletar;
    }

    @PostMapping
    public ResponseEntity<RespuestaPropuesta> proponer(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudProponerSucesion solicitud) {
        RespuestaPropuesta creada = servicio.proponer(idGrupo, solicitud.idUsuarioCandidato());
        return ResponseEntity.created(URI.create("/api/grupos/" + idGrupo + "/sucesion/" + creada.idPropuesta()))
                .body(creada);
    }

    /** Historial del grupo, de la más reciente a la más antigua, sin condiciones. */
    @GetMapping
    public List<RespuestaPropuesta> historial(@PathVariable long idGrupo) {
        return servicio.historial(idGrupo);
    }

    /** 200 con la propuesta {@code PENDIENTE} o {@code ACEPTADA}, o 204 si no hay. */
    @GetMapping("/activa")
    public ResponseEntity<RespuestaPropuesta> activa(@PathVariable long idGrupo) {
        return servicio.activa(idGrupo).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{idPropuesta}")
    public RespuestaPropuesta obtener(@PathVariable long idGrupo, @PathVariable long idPropuesta) {
        return servicio.obtener(idGrupo, idPropuesta);
    }

    @PostMapping("/{idPropuesta}/aceptar")
    public RespuestaPropuesta aceptar(@PathVariable long idGrupo, @PathVariable long idPropuesta) {
        return servicio.aceptar(idGrupo, idPropuesta);
    }

    @PostMapping("/{idPropuesta}/rechazar")
    public RespuestaPropuesta rechazar(@PathVariable long idGrupo, @PathVariable long idPropuesta) {
        return servicio.rechazar(idGrupo, idPropuesta);
    }

    /** El cuerpo es opcional: sin él (o sin {@code motivo}) se cancela sin motivo. */
    @PostMapping("/{idPropuesta}/cancelar")
    public RespuestaPropuesta cancelar(@PathVariable long idGrupo, @PathVariable long idPropuesta,
            @Valid @RequestBody(required = false) SolicitudCancelarSucesion solicitud) {
        return servicio.cancelar(idGrupo, idPropuesta, solicitud == null ? null : solicitud.motivo());
    }

    /**
     * El cuerpo es opcional: sin él, con {@code null} o sin {@code
     * reasignaciones}, se completa sin reasignaciones.
     */
    @PostMapping("/{idPropuesta}/completar")
    public RespuestaPropuesta completar(@PathVariable long idGrupo, @PathVariable long idPropuesta,
            @Valid @RequestBody(required = false) SolicitudCompletarSucesion solicitud) {
        return servicioCompletar.completar(idGrupo, idPropuesta,
                solicitud == null ? SolicitudCompletarSucesion.sinReasignaciones() : solicitud);
    }
}
