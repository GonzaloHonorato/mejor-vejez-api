package cl.adndigital.mejorvejez.salud;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responde siempre 200 mientras el proceso viva; el estado de la BD va en el
 * cuerpo. Así el healthcheck del contenedor no entra en bucle de reinicios
 * cuando Oracle se cae, y el front igual puede mostrar si la BD está arriba.
 */
@RestController
@RequestMapping("/api/salud")
public class ControladorSalud {

    private final ServicioSalud servicioSalud;

    public ControladorSalud(ServicioSalud servicioSalud) {
        this.servicioSalud = servicioSalud;
    }

    @GetMapping
    public RespuestaSalud salud() {
        return servicioSalud.verificar();
    }
}
