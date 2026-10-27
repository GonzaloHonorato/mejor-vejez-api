package cl.adndigital.mejorvejez.relevos;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.comun.RespuestaPagina;

/**
 * Relevos del turno de cuidado (HU-11, TSA-51): registrar, listar, consultar
 * y cancelar. La autorización y las reglas de negocio las exige {@link
 * ServicioRelevos} en cada operación.
 *
 * <p>{@code @Validated} habilita la validación de {@code pagina}/{@code
 * tamano} como parámetros de método, igual que {@code
 * alertas.ControladorAlertas} / {@code auditoria.ControladorAuditoria}.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/relevos")
@Validated
public class ControladorRelevos {

    private static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ServicioRelevos servicio;

    public ControladorRelevos(ServicioRelevos servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<RespuestaRelevo> registrar(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudRegistrarRelevo solicitud) {
        RespuestaRelevo creado = servicio.registrar(idGrupo, solicitud);
        return ResponseEntity.created(URI.create("/api/grupos/" + idGrupo + "/relevos/" + creado.idRelevo()))
                .body(creado);
    }

    @GetMapping
    public RespuestaPagina<RespuestaRelevoResumen> listar(@PathVariable long idGrupo,
            @RequestParam(required = false) Long idAdulto,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa.") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "El tamaño debe ser al menos 1.") int tamano) {
        int tamanoLimitado = Math.min(tamano, TAMANO_MAXIMO_PAGINA);
        return servicio.listar(idGrupo, idAdulto, pagina, tamanoLimitado);
    }

    @GetMapping("/{idRelevo}")
    public RespuestaRelevo obtener(@PathVariable long idGrupo, @PathVariable long idRelevo) {
        return servicio.obtener(idGrupo, idRelevo);
    }

    @PostMapping("/{idRelevo}/cancelar")
    public RespuestaRelevo cancelar(@PathVariable long idGrupo, @PathVariable long idRelevo) {
        return servicio.cancelar(idGrupo, idRelevo);
    }
}
