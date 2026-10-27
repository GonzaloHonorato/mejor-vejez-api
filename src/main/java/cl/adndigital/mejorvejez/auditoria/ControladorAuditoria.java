package cl.adndigital.mejorvejez.auditoria;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.comun.RespuestaPagina;

/**
 * Consulta de auditoría, de solo lectura (HU-12, TSA-52): el coordinador ve
 * la de su grupo ({@code /api/grupos/{idGrupo}/auditoria}, el administrador
 * sin membresía también puede) y el administrador la de todos los grupos
 * ({@code /api/auditoria}, incluidos los eventos sin grupo). La autorización
 * la exige {@link ServicioConsultaAuditoria} en cada operación. No hay
 * ningún {@code PUT}, {@code PATCH} ni {@code DELETE}: Spring responde 405
 * para esos métodos en estas rutas.
 *
 * <p>{@code @Validated} habilita la validación de {@code pagina}/{@code
 * tamano} como parámetros de método, igual que {@code
 * alertas.ControladorAlertas}.
 */
@RestController
@Validated
public class ControladorAuditoria {

    private static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ServicioConsultaAuditoria servicio;

    public ControladorAuditoria(ServicioConsultaAuditoria servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/api/grupos/{idGrupo}/auditoria")
    public RespuestaPagina<RespuestaEventoAuditoria> listarDelGrupo(@PathVariable long idGrupo,
            @RequestParam(required = false) String tipoEntidad, @RequestParam(required = false) String idEntidad,
            @RequestParam(required = false) String codigoAccion,
            @RequestParam(required = false) String codigoResultado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa.") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "El tamaño debe ser al menos 1.") int tamano) {
        int tamanoLimitado = Math.min(tamano, TAMANO_MAXIMO_PAGINA);
        FiltroConsultaAuditoria filtro =
                new FiltroConsultaAuditoria(null, tipoEntidad, idEntidad, codigoAccion, codigoResultado, desde, hasta);
        return servicio.consultarGrupo(idGrupo, filtro, pagina, tamanoLimitado);
    }

    @GetMapping("/api/auditoria")
    public RespuestaPagina<RespuestaEventoAuditoria> listarGlobal(@RequestParam(required = false) Long idGrupo,
            @RequestParam(required = false) String tipoEntidad, @RequestParam(required = false) String idEntidad,
            @RequestParam(required = false) String codigoAccion,
            @RequestParam(required = false) String codigoResultado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa.") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "El tamaño debe ser al menos 1.") int tamano) {
        int tamanoLimitado = Math.min(tamano, TAMANO_MAXIMO_PAGINA);
        FiltroConsultaAuditoria filtro = new FiltroConsultaAuditoria(idGrupo, tipoEntidad, idEntidad, codigoAccion,
                codigoResultado, desde, hasta);
        return servicio.consultarGlobal(filtro, pagina, tamanoLimitado);
    }
}
