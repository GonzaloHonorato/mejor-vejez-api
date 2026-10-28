package cl.adndigital.mejorvejez.interno;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ruta interna del resultado de un intento de envío (Fase 9, TSA-61,
 * {@code PUT /api/interno/intentos/{idIntento}}): fuera de {@code
 * /api/interno/alertas/**} porque el recurso es el intento, no la alerta. El
 * microservicio de Alertas la llama tras enviar (o fallar al enviar) un par
 * reservado con {@code POST /api/interno/alertas/{idAlerta}/intentos/reservar}.
 * Protegida SOLO por la cadena interna ({@code FiltroClaveServicio}).
 */
@RestController
@RequestMapping("/api/interno/intentos")
public class ControladorInternoIntentos {

    private final ServicioReservaIntentos servicio;

    public ControladorInternoIntentos(ServicioReservaIntentos servicio) {
        this.servicio = servicio;
    }

    @PutMapping("/{idIntento}")
    public ResponseEntity<Void> registrarResultado(@PathVariable long idIntento,
            @RequestBody SolicitudResultadoIntento solicitud) {
        servicio.registrarResultado(idIntento, solicitud);
        return ResponseEntity.noContent().build();
    }
}
