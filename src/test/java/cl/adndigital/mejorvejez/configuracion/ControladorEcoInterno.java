package cl.adndigital.mejorvejez.configuracion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador que SOLO existe para {@link FiltroClaveServicioTest}: expone
 * {@code /api/interno/eco} para comprobar que una llamada con la clave de
 * servicio correcta atraviesa {@link FiltroClaveServicio} y llega al
 * controlador, sin pasar por las rutas de negocio reales de {@code
 * /api/interno/}. Al estar en el paquete escaneado, este controlador se
 * registra en todos los contextos {@code @SpringBootTest}: es inofensivo
 * (una ruta extra que ningún otro slice usa), no hace falta limitarlo.
 */
@RestController
public class ControladorEcoInterno {

    @GetMapping("/api/interno/eco")
    public String eco() {
        return "eco";
    }
}
