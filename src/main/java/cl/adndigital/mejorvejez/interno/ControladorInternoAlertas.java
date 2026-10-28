package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rutas internas de alertas (Fase 9, TSA-61, {@code /api/interno/alertas/**}):
 * las consume el microservicio de Alertas. Protegidas SOLO por la cadena
 * interna ({@code ConfiguracionSeguridad#cadenaInterna}, {@code
 * FiltroClaveServicio}); nunca por un JWT de usuario.
 *
 * <p>{@code @Validated} habilita la validación de {@code horasCita}/{@code
 * diasReposicion}, mismo patrón que {@code ControladorAlertas}: un valor
 * negativo da 400 {@code ProblemDetail} por {@code
 * ManejadorErrores#manejarViolacionDeRestriccion}, nunca una ventana
 * invertida silenciosa.
 */
@RestController
@RequestMapping("/api/interno/alertas")
@Validated
public class ControladorInternoAlertas {

    private final ServicioInternoAlertas servicio;
    private final ServicioReservaIntentos servicioReserva;

    public ControladorInternoAlertas(ServicioInternoAlertas servicio, ServicioReservaIntentos servicioReserva) {
        this.servicio = servicio;
        this.servicioReserva = servicioReserva;
    }

    @GetMapping("/candidatos-atraso")
    public List<CandidatoAtraso> candidatosAtraso(@RequestParam OffsetDateTime desde,
            @RequestParam OffsetDateTime hasta) {
        return servicio.candidatosAtraso(desde, hasta);
    }

    @GetMapping("/candidatos-preventivos")
    public CandidatosPreventivos candidatosPreventivos(@RequestParam OffsetDateTime ahora,
            @RequestParam @Min(value = 0, message = "Las horas de cita no pueden ser negativas.") int horasCita,
            @RequestParam @Min(value = 0,
                    message = "Los días de reposición no pueden ser negativos.") int diasReposicion) {
        return servicio.candidatosPreventivos(ahora, horasCita, diasReposicion);
    }

    @PostMapping
    public RespuestaCrearAlerta crearAlerta(@RequestBody SolicitudCrearAlerta solicitud) {
        return servicio.crearAlerta(solicitud);
    }

    @GetMapping("/envios-pendientes")
    public List<Long> enviosPendientes(@RequestParam OffsetDateTime desde) {
        return servicioReserva.enviosPendientes(desde);
    }

    @PostMapping("/{idAlerta}/intentos/reservar")
    public RespuestaReservaIntentos reservarIntentos(@PathVariable long idAlerta) {
        return servicioReserva.reservarIntentos(idAlerta);
    }
}
