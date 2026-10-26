package cl.adndigital.mejorvejez.alertas;

import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.comun.RespuestaPagina;

/**
 * Bandeja de alertas del grupo y detalle de una alerta (HU-08, TSA-47). La
 * autorización la exige {@link ServicioConsultaAlertas} en cada operación.
 *
 * <p>{@code @Validated} habilita la validación de {@code pagina}/{@code
 * tamano} como parámetros de método (mismo patrón que {@code
 * usuarios.ControladorUsuarios}).
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/alertas")
@Validated
public class ControladorAlertas {

    private static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ServicioConsultaAlertas servicio;

    public ControladorAlertas(ServicioConsultaAlertas servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public RespuestaPagina<RespuestaAlerta> listar(@PathVariable long idGrupo,
            @RequestParam(required = false) Long idEstado, @RequestParam(required = false) String tipoAlerta,
            @RequestParam(required = false) Long idAdulto,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa.") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "El tamaño debe ser al menos 1.") int tamano) {
        int tamanoLimitado = Math.min(tamano, TAMANO_MAXIMO_PAGINA);
        return servicio.listar(idGrupo, idEstado, tipoAlerta, idAdulto, pagina, tamanoLimitado);
    }

    @GetMapping("/{idAlerta}")
    public RespuestaAlertaDetallada obtener(@PathVariable long idGrupo, @PathVariable long idAlerta) {
        return servicio.obtener(idGrupo, idAlerta);
    }
}
