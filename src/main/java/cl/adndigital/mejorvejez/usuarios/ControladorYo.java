package cl.adndigital.mejorvejez.usuarios;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;

/**
 * Expone el usuario autenticado. El front lo llama tras el login: en el
 * primer ingreso, {@link ServicioUsuarioActual} lo da de alta. {@code PATCH}
 * permite editar el perfil (nombre y teléfono); el correo no se edita, viene
 * de Firebase.
 */
@RestController
public class ControladorYo {

    private final ServicioUsuarioActual servicioUsuarioActual;
    private final ServicioPerfil servicioPerfil;

    public ControladorYo(ServicioUsuarioActual servicioUsuarioActual, ServicioPerfil servicioPerfil) {
        this.servicioUsuarioActual = servicioUsuarioActual;
        this.servicioPerfil = servicioPerfil;
    }

    @GetMapping("/api/yo")
    public RespuestaYo yo() {
        return RespuestaYo.desde(servicioUsuarioActual.obtener());
    }

    @PatchMapping("/api/yo")
    public RespuestaYo actualizarPerfil(@Valid @RequestBody SolicitudActualizarPerfil solicitud) {
        Usuario usuario = servicioUsuarioActual.obtener();
        Usuario actualizado = servicioPerfil.actualizar(usuario, solicitud.nombre(), solicitud.telefono());
        return RespuestaYo.desde(actualizado);
    }
}
