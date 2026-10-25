package cl.adndigital.mejorvejez.catalogos;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone los catálogos de estados y de opciones de negocio para que el
 * front pueda armar selects sin hardcodear códigos. Ambos endpoints exigen
 * token (ver {@code ConfiguracionSeguridad}: no hay catálogo público).
 */
@RestController
@RequestMapping("/api/catalogos")
public class ControladorCatalogos {

    private final ServicioCatalogos servicioCatalogos;

    public ControladorCatalogos(ServicioCatalogos servicioCatalogos) {
        this.servicioCatalogos = servicioCatalogos;
    }

    @GetMapping("/estados")
    public List<RespuestaEstado> estados(@RequestParam String tabla, @RequestParam String campo) {
        return servicioCatalogos.listarEstados(tabla, campo);
    }

    @GetMapping("/opciones")
    public List<RespuestaOpcion> opciones(@RequestParam TipoCatalogo tipo) {
        return servicioCatalogos.listarOpciones(tipo);
    }
}
