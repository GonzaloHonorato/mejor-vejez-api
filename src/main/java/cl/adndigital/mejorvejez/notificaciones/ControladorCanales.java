package cl.adndigital.mejorvejez.notificaciones;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canales de notificación del usuario autenticado ({@code /api/yo/canales},
 * regla 17 de la Fase 5). {@link ServicioCanales} resuelve el usuario
 * actual internamente (vía {@code ServicioUsuarioActual}).
 */
@RestController
public class ControladorCanales {

    private final ServicioCanales servicioCanales;

    public ControladorCanales(ServicioCanales servicioCanales) {
        this.servicioCanales = servicioCanales;
    }

    @GetMapping("/api/yo/canales")
    public RespuestaCanales misCanales() {
        return servicioCanales.misCanales();
    }

    @PutMapping("/api/yo/canales")
    public RespuestaCanales actualizarMisCanales(@RequestBody SolicitudActualizarCanales solicitud) {
        return servicioCanales.actualizarMisCanales(solicitud);
    }
}
