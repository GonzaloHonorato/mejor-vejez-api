package cl.adndigital.mejorvejez.usuarios;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;

/**
 * Expone el usuario autenticado. El front lo llama tras el login: en el
 * primer ingreso, {@link ServicioUsuarioActual} lo da de alta.
 */
@RestController
public class ControladorYo {

    private final ServicioUsuarioActual servicioUsuarioActual;

    public ControladorYo(ServicioUsuarioActual servicioUsuarioActual) {
        this.servicioUsuarioActual = servicioUsuarioActual;
    }

    @GetMapping("/api/yo")
    public RespuestaYo yo() {
        return RespuestaYo.desde(servicioUsuarioActual.obtener());
    }
}
