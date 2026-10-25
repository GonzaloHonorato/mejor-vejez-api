package cl.adndigital.mejorvejez.grupos;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Integrantes y membresías de un grupo familiar (HU-02). La autorización la
 * exige {@link ServicioIntegrantes} en cada operación.
 *
 * <p>Contrato de respuesta: {@code POST .../integrantes} devuelve 201 con
 * {@link RespuestaIntegrante} (y {@code Location}); {@code PUT .../rol},
 * {@code POST .../desactivar} y {@code POST .../reactivar} devuelven 200 con
 * {@link RespuestaIntegrante} actualizado; {@code POST .../salir} y
 * {@code DELETE} devuelven 204.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}")
public class ControladorIntegrantes {

    private final ServicioIntegrantes servicio;

    public ControladorIntegrantes(ServicioIntegrantes servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/integrantes")
    public List<RespuestaIntegrante> listar(@PathVariable long idGrupo) {
        return servicio.listar(idGrupo);
    }

    @PostMapping("/integrantes")
    public ResponseEntity<RespuestaIntegrante> incorporar(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudIncorporarIntegrante solicitud) {
        RespuestaIntegrante creado = servicio.incorporar(idGrupo, solicitud.email(), solicitud.idRol());
        return ResponseEntity
                .created(URI.create("/api/grupos/" + idGrupo + "/integrantes/" + creado.idIntegrante()))
                .body(creado);
    }

    @PutMapping("/integrantes/{idIntegrante}/rol")
    public RespuestaIntegrante cambiarRol(@PathVariable long idGrupo, @PathVariable long idIntegrante,
            @Valid @RequestBody SolicitudCambiarRol solicitud) {
        return servicio.cambiarRol(idGrupo, idIntegrante, solicitud.idRol());
    }

    @PostMapping("/integrantes/{idIntegrante}/desactivar")
    public RespuestaIntegrante desactivar(@PathVariable long idGrupo, @PathVariable long idIntegrante) {
        return servicio.desactivar(idGrupo, idIntegrante);
    }

    @PostMapping("/integrantes/{idIntegrante}/reactivar")
    public RespuestaIntegrante reactivar(@PathVariable long idGrupo, @PathVariable long idIntegrante) {
        return servicio.reactivar(idGrupo, idIntegrante);
    }

    @PostMapping("/salir")
    public ResponseEntity<Void> salir(@PathVariable long idGrupo) {
        servicio.salir(idGrupo);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/integrantes/{idIntegrante}")
    public ResponseEntity<Void> eliminar(@PathVariable long idGrupo, @PathVariable long idIntegrante) {
        servicio.eliminar(idGrupo, idIntegrante);
        return ResponseEntity.noContent().build();
    }
}
