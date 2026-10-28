package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.interno.ResumenCorrida;
import cl.adndigital.mejorvejez.interno.ResumenReportes;

/**
 * Corridas manuales de los procesos de alertas y del reporte semanal, por el
 * administrador (Fase 9): reenvían la llamada al microservicio
 * correspondiente con la clave de servicio y siguen auditando quién la
 * pidió. La autorización y la auditoría las exige {@link
 * ServicioProcesosAlertas}.
 */
@RestController
@RequestMapping("/api/administracion/procesos")
public class ControladorProcesosAlertas {

    private final ServicioProcesosAlertas servicio;

    public ControladorProcesosAlertas(ServicioProcesosAlertas servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/deteccion-atrasos")
    public ResumenCorrida detectarAtrasos() {
        return servicio.deteccionAtrasos();
    }

    @PostMapping("/alertas-preventivas")
    public ResumenCorrida alertasPreventivas() {
        return servicio.alertasPreventivas();
    }

    /** {@code inicio} es opcional: si no viene, el servicio de Reportes usa el lunes de la semana anterior. */
    @PostMapping("/reporte-semanal")
    public ResumenReportes reporteSemanal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio) {
        return servicio.reporteSemanal(inicio);
    }
}
