package cl.adndigital.mejorvejez.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responde siempre 200 mientras el proceso viva; el estado de la BD va en el
 * cuerpo. Así el healthcheck del contenedor no entra en bucle de reinicios
 * cuando Oracle se cae, y el front igual puede mostrar si la BD está arriba.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public HealthResponse health() {
        return healthService.check();
    }
}
