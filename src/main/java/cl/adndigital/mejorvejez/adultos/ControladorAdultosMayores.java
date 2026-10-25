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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfiles de adultos mayores de un grupo (HU-03). La autorización la exige
 * {@link ServicioAdultosMayores} en cada operación.
 *
 * <p>Contrato de respuesta: {@code POST .../adultos} devuelve 201 con
 * {@link RespuestaAdultoMayor} (y {@code Location}); {@code PUT
 * .../adultos/{idAdulto}}, {@code POST .../desactivar} y {@code POST
 * .../reactivar} devuelven 200 con {@link RespuestaAdultoMayor} actualizado;
 * {@code DELETE} devuelve 204.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/adultos")
public class ControladorAdultosMayores {

    private final ServicioAdultosMayores servicio;

    public ControladorAdultosMayores(ServicioAdultosMayores servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<RespuestaAdultoMayor> listar(@PathVariable long idGrupo,
            @RequestParam(defaultValue = "false") boolean incluirInactivos) {
        return servicio.listar(idGrupo, incluirInactivos);
    }

    @GetMapping("/{idAdulto}")
    public RespuestaAdultoMayor obtener(@PathVariable long idGrupo, @PathVariable long idAdulto) {
        return servicio.obtener(idGrupo, idAdulto);
    }

    @PostMapping
    public ResponseEntity<RespuestaAdultoMayor> crear(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudGuardarAdultoMayor solicitud) {
        RespuestaAdultoMayor creado = servicio.crear(idGrupo, solicitud);
        return ResponseEntity.created(URI.create("/api/grupos/" + idGrupo + "/adultos/" + creado.idAdultoMayor()))
                .body(creado);
    }

    @PutMapping("/{idAdulto}")
    public RespuestaAdultoMayor actualizar(@PathVariable long idGrupo, @PathVariable long idAdulto,
            @Valid @RequestBody SolicitudGuardarAdultoMayor solicitud) {
        return servicio.actualizar(idGrupo, idAdulto, solicitud);
    }

    @PostMapping("/{idAdulto}/desactivar")
    public RespuestaAdultoMayor desactivar(@PathVariable long idGrupo, @PathVariable long idAdulto) {
        return servicio.desactivar(idGrupo, idAdulto);
    }

    @PostMapping("/{idAdulto}/reactivar")
    public RespuestaAdultoMayor reactivar(@PathVariable long idGrupo, @PathVariable long idAdulto) {
        return servicio.reactivar(idGrupo, idAdulto);
    }

    @DeleteMapping("/{idAdulto}")
    public ResponseEntity<Void> eliminar(@PathVariable long idGrupo, @PathVariable long idAdulto) {
        servicio.eliminar(idGrupo, idAdulto);
        return ResponseEntity.noContent().build();
    }
}
