package cl.adndigital.mejorvejez.interno;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Rutas internas de ejecuciones (Fase 9, TSA-61, {@code
 * /api/interno/ejecuciones/**}): los microservicios de Alertas y de Reportes
 * registran el inicio y el cierre de sus propias corridas. Sin
 * servicio propio: delega directo en {@link RegistroEjecuciones}, que ya es
 * público y transaccional (una transacción por operación). Protegidas SOLO
 * por la cadena interna ({@code FiltroClaveServicio}).
 */
@RestController
@RequestMapping("/api/interno/ejecuciones")
public class ControladorInternoEjecuciones {

    private final RegistroEjecuciones registro;

    public ControladorInternoEjecuciones(RegistroEjecuciones registro) {
        this.registro = registro;
    }

    @PostMapping
    public ResponseEntity<RespuestaIdEjecucion> iniciar(@RequestBody SolicitudIniciarEjecucion solicitud) {
        long idEjecucion = registro.iniciar(solicitud.tipoEjecucion(), solicitud.inicioVentana(),
                solicitud.finVentana());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RespuestaIdEjecucion(idEjecucion));
    }

    @PutMapping("/{idEjecucion}")
    public RespuestaEjecucion finalizar(@PathVariable long idEjecucion,
            @RequestBody SolicitudFinalizarEjecucion solicitud) {
        registro.finalizar(idEjecucion, solicitud.codigoEstado(), solicitud.creadas(), solicitud.errores(),
                solicitud.resumen());
        return registro.respuesta(idEjecucion);
    }
}
