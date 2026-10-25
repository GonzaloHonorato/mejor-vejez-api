package cl.adndigital.mejorvejez.grupos;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crear, listar, ver y editar grupos familiares. La autorización la exige
 * {@link ServicioGrupos} en cada operación.
 */
@RestController
@RequestMapping("/api/grupos")
public class ControladorGrupos {

    private final ServicioGrupos servicio;

    public ControladorGrupos(ServicioGrupos servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<RespuestaGrupo> crear(@Valid @RequestBody SolicitudCrearGrupo solicitud) {
        RespuestaGrupo creado = servicio.crear(solicitud.nombreGrupo());
        return ResponseEntity.created(URI.create("/api/grupos/" + creado.idGrupo())).body(creado);
    }

    @GetMapping
    public List<RespuestaGrupo> listar(@RequestParam(defaultValue = "false") boolean todos) {
        return servicio.listar(todos);
    }

    @GetMapping("/{idGrupo}")
    public RespuestaGrupo obtener(@PathVariable long idGrupo) {
        return servicio.obtener(idGrupo);
    }

    @PatchMapping("/{idGrupo}")
    public RespuestaGrupo actualizar(@PathVariable long idGrupo, @Valid @RequestBody SolicitudActualizarGrupo solicitud) {
        return servicio.actualizar(idGrupo, solicitud.nombreGrupo(), solicitud.notificaCoordinador());
    }
}
