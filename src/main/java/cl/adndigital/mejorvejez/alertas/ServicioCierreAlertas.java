package cl.adndigital.mejorvejez.alertas;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Cierra una alerta de cuidado (TSA-47, TSA-9): la pueden cerrar el
 * coordinador activo del grupo y el administrador (regla 13 de la Fase 5).
 * "Usuarios con permiso de gestión" no existe como permiso en el MVP, así
 * que queda fuera.
 *
 * <p><b>Orden de validaciones.</b> Permiso ({@code
 * exigirCoordinador}, 403, auditado) → bloquear la alerta y su pertenencia al
 * grupo (404 "La alerta no existe") → ya {@code CERRADA} (409) →
 * observación obligatoria y su largo (422). La observación se valida
 * siempre después del permiso, para que un colaborador sin permiso reciba
 * 403 y no 422.
 *
 * <p><b>Nunca toca la tarea.</b> Ningún otro código cambia {@code
 * ALERTA_CUIDADO.ID_ESTADO}: ni el recálculo de F4, ni la cancelación de una
 * tarea ni el envío de una notificación.
 */
@Service
public class ServicioCierreAlertas {

    static final String MENSAJE_YA_CERRADA = "La alerta ya está cerrada.";
    static final String MENSAJE_OBSERVACION_OBLIGATORIA = "La observación es obligatoria para cerrar la alerta.";
    static final String MENSAJE_OBSERVACION_MUY_LARGA = "La observación no puede superar los 500 caracteres.";

    private static final int MAXIMO_CARACTERES_OBSERVACION = 500;
    private static final int MAXIMO_BYTES_OBSERVACION = 1000;

    private final RepositorioAlertaCuidado repositorioAlerta;
    private final RepositorioIntentoNotificacion repositorioIntento;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final EnsambladorRespuestasAlerta ensamblador;
    private final Clock reloj;

    public ServicioCierreAlertas(RepositorioAlertaCuidado repositorioAlerta,
            RepositorioIntentoNotificacion repositorioIntento, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, ServicioAuditoria servicioAuditoria,
            EnsambladorRespuestasAlerta ensamblador, Clock reloj) {
        this.repositorioAlerta = repositorioAlerta;
        this.repositorioIntento = repositorioIntento;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.ensamblador = ensamblador;
        this.reloj = reloj;
    }

    /**
     * Cierra la alerta con la observación indicada.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo (ni
     *                                 administrador).
     * @throws ExcepcionNoEncontrado   la alerta no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      la alerta ya está cerrada.
     * @throws ExcepcionReglaNegocio   la observación es obligatoria, o supera los 500 caracteres o los 1000 bytes
     *                                 UTF-8.
     */
    @Transactional
    public RespuestaAlertaDetallada cerrar(long idGrupo, long idAlerta, SolicitudCerrarAlerta solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AlertaCuidado alerta = bloquearAlertaDelGrupo(idGrupo, idAlerta);

        long idEstadoCerrada = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_CERRADA);
        if (alerta.getIdEstado() == idEstadoCerrada) {
            throw new ExcepcionConflicto(MENSAJE_YA_CERRADA);
        }

        String observacion = validarObservacion(solicitud.observacion());

        long idActor = contexto.usuario().getIdUsuario();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        alerta.cerrar(idEstadoCerrada, idActor, observacion, ahora);
        repositorioAlerta.save(alerta);

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("tipoAlerta", alerta.getTipoAlerta());
        detalles.put("idTarea", alerta.getIdTarea());
        detalles.put("idDetallePlan", alerta.getIdDetallePlan());
        servicioAuditoria.registrar(new EventoARegistrar(CodigosAlertas.MODULO, CodigosAlertas.TABLA_ALERTA_CUIDADO,
                String.valueOf(idAlerta), idGrupo, idActor, AccionesAlertas.CERRAR_ALERTA, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));

        List<IntentoNotificacion> intentos = repositorioIntento
                .findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(idAlerta);
        return ensamblador.detallar(alerta, intentos, true);
    }

    /** @throws ExcepcionNoEncontrado la alerta no existe o no pertenece al grupo. */
    private AlertaCuidado bloquearAlertaDelGrupo(long idGrupo, long idAlerta) {
        AlertaCuidado alerta = repositorioAlerta.bloquearPorId(idAlerta)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioConsultaAlertas.MENSAJE_NO_EXISTE));
        if (alerta.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioConsultaAlertas.MENSAJE_NO_EXISTE);
        }
        return alerta;
    }

    /** @throws ExcepcionReglaNegocio vacía o en blanco, o supera los 500 caracteres o los 1000 bytes UTF-8. */
    private String validarObservacion(String observacion) {
        String recortada = observacion == null ? null : observacion.strip();
        if (recortada == null || recortada.isEmpty()) {
            throw new ExcepcionReglaNegocio(MENSAJE_OBSERVACION_OBLIGATORIA);
        }
        if (recortada.length() > MAXIMO_CARACTERES_OBSERVACION
                || recortada.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_OBSERVACION) {
            throw new ExcepcionReglaNegocio(MENSAJE_OBSERVACION_MUY_LARGA);
        }
        return recortada;
    }

    private long idEstadoAlerta(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigo);
    }
}
