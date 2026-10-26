package cl.adndigital.mejorvejez.alertas;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cierre de una alerta de cuidado (TSA-47, TSA-9). La autorización y las
 * reglas de negocio las exige {@link ServicioCierreAlertas}. Sin
 * {@code @Valid}: {@link SolicitudCerrarAlerta} no lleva anotaciones de
 * validación a propósito (regla 13 de la Fase 5), para que la observación se
 * valide en el servicio, después del permiso. Por lo mismo el cuerpo es
 * opcional: sin cuerpo (o con un {@code null} JSON) equivale a una
 * observación nula, así un colaborador recibe 403 y un coordinador 422,
 * nunca 400 ni 500.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/alertas")
public class ControladorCierreAlertas {

    private final ServicioCierreAlertas servicio;

    public ControladorCierreAlertas(ServicioCierreAlertas servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/{idAlerta}/cierre")
    public RespuestaAlertaDetallada cerrar(@PathVariable long idGrupo, @PathVariable long idAlerta,
            @RequestBody(required = false) SolicitudCerrarAlerta solicitud) {
        return servicio.cerrar(idGrupo, idAlerta, solicitud == null ? new SolicitudCerrarAlerta(null) : solicitud);
    }
}
