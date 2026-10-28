package cl.adndigital.mejorvejez.interno;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rutas internas de reportes (Fase 9, TSA-61, {@code
 * /api/interno/reportes/**}): las consume el microservicio de Reportes.
 * Protegidas SOLO por la cadena interna ({@code
 * ConfiguracionSeguridad#cadenaInterna}, {@code FiltroClaveServicio});
 * nunca por un JWT de usuario.
 */
@RestController
@RequestMapping("/api/interno/reportes")
public class ControladorInternoReportes {

    private final ServicioInternoReportes servicio;

    public ControladorInternoReportes(ServicioInternoReportes servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/grupos")
    public List<GrupoReporte> grupos() {
        return servicio.grupos();
    }

    @GetMapping("/grupos/{idGrupo}/semana")
    public DatosSemana semana(@PathVariable long idGrupo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio) {
        return servicio.semana(idGrupo, inicio);
    }

    @PostMapping("/envios")
    public RespuestaReservaEnvio reservarEnvio(@Valid @RequestBody SolicitudReservarEnvio solicitud) {
        return servicio.reservarEnvio(solicitud);
    }

    @PutMapping("/envios/{idEnvio}/resultado")
    public RespuestaResultadoEnvio registrarResultado(@PathVariable long idEnvio,
            @Valid @RequestBody SolicitudResultadoEnvio solicitud) {
        return servicio.registrarResultado(idEnvio, solicitud);
    }
}
