package cl.adndigital.mejorvejez.adultos;

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
 * Contactos de emergencia de un adulto mayor (HU-03). La autorización la
 * exige {@link ServicioContactos} en cada operación.
 *
 * <p>Contrato de respuesta: {@code POST .../contactos} devuelve 201 con
 * {@link RespuestaContacto} (y {@code Location}); {@code PUT
 * .../contactos/{idContacto}}, {@code POST .../principal}, {@code POST
 * .../desactivar} y {@code POST .../reactivar} devuelven 200 con el contacto
 * actualizado; {@code DELETE} devuelve 204.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/adultos/{idAdulto}/contactos")
public class ControladorContactos {

    private final ServicioContactos servicio;

    public ControladorContactos(ServicioContactos servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<RespuestaContacto> listar(@PathVariable long idGrupo, @PathVariable long idAdulto) {
        return servicio.listar(idGrupo, idAdulto);
    }

    @PostMapping
    public ResponseEntity<RespuestaContacto> crear(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @Valid @RequestBody SolicitudCrearContacto solicitud) {
        RespuestaContacto creado = servicio.crear(idGrupo, idAdulto, solicitud);
        URI ubicacion = URI.create(
                "/api/grupos/" + idGrupo + "/adultos/" + idAdulto + "/contactos/" + creado.idContacto());
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @PutMapping("/{idContacto}")
    public RespuestaContacto actualizar(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @PathVariable long idContacto, @Valid @RequestBody SolicitudActualizarContacto solicitud) {
        return servicio.actualizar(idGrupo, idAdulto, idContacto, solicitud);
    }

    @PostMapping("/{idContacto}/principal")
    public RespuestaContacto designarPrincipal(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @PathVariable long idContacto) {
        return servicio.designarPrincipal(idGrupo, idAdulto, idContacto);
    }

    @PostMapping("/{idContacto}/desactivar")
    public RespuestaContacto desactivar(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @PathVariable long idContacto, @RequestBody(required = false) SolicitudDesactivarContacto solicitud) {
        return servicio.desactivar(idGrupo, idAdulto, idContacto, solicitud);
    }

    @PostMapping("/{idContacto}/reactivar")
    public RespuestaContacto reactivar(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @PathVariable long idContacto) {
        return servicio.reactivar(idGrupo, idAdulto, idContacto);
    }

    @DeleteMapping("/{idContacto}")
    public ResponseEntity<Void> eliminar(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @PathVariable long idContacto) {
        servicio.eliminar(idGrupo, idAdulto, idContacto);
        return ResponseEntity.noContent().build();
    }
}
