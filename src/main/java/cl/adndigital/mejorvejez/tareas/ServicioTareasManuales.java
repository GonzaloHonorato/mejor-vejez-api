package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.adultos.ServicioAdultosMayores;
import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.catalogos.TipoCatalogo;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.planificacion.ServicioMedicamentos;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Alta manual de una tarea de cuidado por el coordinador (HU-05, TSA-41):
 * compra/retiro de medicamentos (varias líneas) o cita/control médico
 * (médico y lugar). Ninguna de las dos lleva {@code CLAVE_OCURRENCIA} (solo
 * las de {@code MEDICAMENTOS} la llevan, regla 3 de la Fase 4); la compra
 * NUNCA toca {@code CANTIDAD_DISPONIBLE} de ningún detalle del plan.
 *
 * <p><b>Historial inicial (regla 9 de la Fase 4).</b> A diferencia del
 * generador (que no escribe historial al crear, por no existir un usuario de
 * sistema), la tarea manual sí lo hace: {@code null} → {@code PENDIENTE},
 * con el coordinador que la crea como actor.
 *
 * <p><b>Sin bloqueo.</b> A diferencia de {@link ServicioAsignacionTareas} y
 * {@link ServicioEstadoTareas} (que bloquean la fila de una tarea
 * existente), esta operación solo inserta filas nuevas (tarea, líneas o
 * cita, asignación y historial): no hay una fila previa que serializar, así
 * que no bloquea el adulto ni ninguna otra fila.
 *
 * <p><b>Orden de validaciones</b> (Tarea 8 de la Fase 4): {@code
 * exigirCoordinador} (403) → {@code tipoTarea} válido (422) → el adulto
 * pertenece al grupo (404) y está {@code ACTIVO} (422) → {@code
 * fechaHorario} futura (422) → datos de compra o de cita según el tipo,
 * mutuamente excluyentes (422) → {@code idResponsable} integrante {@code
 * ACTIVO} del grupo, si viene (422).
 */
@Service
public class ServicioTareasManuales {

    static final String MENSAJE_TIPO_INVALIDO = "Tipo de tarea no válido";
    static final String MENSAJE_ADULTO_INACTIVO = "El adulto mayor está inactivo";
    static final String MENSAJE_FECHA_FUTURA = "La fecha y hora debe ser futura";
    static final String MENSAJE_COMPRA_SIN_LINEAS = "Agrega al menos un medicamento a la compra";
    static final String MENSAJE_COMPRA_CON_CITA = "Una compra no lleva datos de cita";
    static final String MENSAJE_CITA_SIN_DATOS = "Indica el médico y el lugar de la cita";
    static final String MENSAJE_CITA_CON_COMPRA = "Una cita no lleva medicamentos";

    private static final String TABLA_ADULTO_MAYOR = "ADULTO_MAYOR";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CAMPO_ESTADO = CodigosTareas.CAMPO_ESTADO;
    private static final String TABLA_INTEGRANTE_GRUPO = "INTEGRANTE_GRUPO";

    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioDetalleCompraMedicamento repositorioDetalleCompra;
    private final RepositorioDetalleCitaMedica repositorioDetalleCita;
    private final RepositorioAsignacionTarea repositorioAsignacion;
    private final RepositorioHistorialEstadoTarea repositorioHistorial;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioMedicamentos servicioMedicamentos;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final EnsambladorRespuestasTarea ensamblador;
    private final Clock reloj;

    public ServicioTareasManuales(RepositorioAdultoMayor repositorioAdulto,
            RepositorioIntegranteGrupo repositorioIntegrante, RepositorioTareaCuidado repositorioTarea,
            RepositorioDetalleCompraMedicamento repositorioDetalleCompra,
            RepositorioDetalleCitaMedica repositorioDetalleCita, RepositorioAsignacionTarea repositorioAsignacion,
            RepositorioHistorialEstadoTarea repositorioHistorial, ServicioAutorizacion servicioAutorizacion,
            ServicioMedicamentos servicioMedicamentos, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, EnsambladorRespuestasTarea ensamblador, Clock reloj) {
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioTarea = repositorioTarea;
        this.repositorioDetalleCompra = repositorioDetalleCompra;
        this.repositorioDetalleCita = repositorioDetalleCita;
        this.repositorioAsignacion = repositorioAsignacion;
        this.repositorioHistorial = repositorioHistorial;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioMedicamentos = servicioMedicamentos;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.ensamblador = ensamblador;
        this.reloj = reloj;
    }

    /**
     * Crea la tarea manual (compra/retiro de medicamentos o cita/control
     * médico), con sus líneas o su cita, su asignación (si hay responsable)
     * y su historial inicial.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo (ni
     *                                 administrador).
     * @throws ExcepcionNoEncontrado   el adulto mayor no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   {@code tipoTarea} no es válido, el adulto está inactivo, {@code
     *                                 fechaHorario} no es futura, faltan o sobran datos de compra/cita según el
     *                                 tipo, algún medicamento/presentación/unidad de una línea no es válido, o
     *                                 {@code idResponsable} no es integrante {@code ACTIVO} del grupo.
     */
    @Transactional
    public RespuestaTarea crear(long idGrupo, SolicitudCrearTareaManual solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);

        String tipoTarea = solicitud.tipoTarea();
        boolean esCompra = CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS.equals(tipoTarea);
        boolean esCita = CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS.equals(tipoTarea);
        if (!esCompra && !esCita) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIPO_INVALIDO);
        }

        AdultoMayor adulto = adultoActivoDelGrupo(idGrupo, solicitud.idAdultoMayor());

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (!solicitud.fechaHorario().isAfter(ahora)) {
            throw new ExcepcionReglaNegocio(MENSAJE_FECHA_FUTURA);
        }

        List<SolicitudLineaCompra> compra = solicitud.compra();
        boolean compraVacia = compra == null || compra.isEmpty();

        if (esCompra) {
            if (compraVacia) {
                throw new ExcepcionReglaNegocio(MENSAJE_COMPRA_SIN_LINEAS);
            }
            if (solicitud.cita() != null) {
                throw new ExcepcionReglaNegocio(MENSAJE_COMPRA_CON_CITA);
            }
            for (SolicitudLineaCompra linea : compra) {
                servicioMedicamentos.obtenerActivo(linea.idMedicamento());
                servicioCatalogos.validarOpcion(linea.idPresentacion(), TipoCatalogo.PRESENTACION_MEDICAMENTO);
                servicioCatalogos.validarOpcion(linea.idUnidadCantidad(), TipoCatalogo.UNIDAD_DOSIS);
            }
        } else {
            if (solicitud.cita() == null) {
                throw new ExcepcionReglaNegocio(MENSAJE_CITA_SIN_DATOS);
            }
            if (!compraVacia) {
                throw new ExcepcionReglaNegocio(MENSAJE_CITA_CON_COMPRA);
            }
        }

        Long idResponsable = solicitud.idResponsable();
        if (idResponsable != null) {
            exigirIntegranteActivo(idGrupo, idResponsable);
        }

        long idEstadoPendiente = idEstadoTareaCuidado(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        TareaCuidado tarea = TareaCuidado.manual(tipoTarea, idGrupo, adulto.getIdAdultoMayor(), solicitud.titulo(),
                solicitud.descripcion(), solicitud.fechaHorario(), idEstadoPendiente, ahora);
        repositorioTarea.save(tarea);
        long idTarea = tarea.getIdTarea();

        if (esCompra) {
            for (SolicitudLineaCompra linea : compra) {
                repositorioDetalleCompra.save(new DetalleCompraMedicamento(idTarea, linea.idMedicamento(),
                        linea.idPresentacion(), linea.dosisIndicada(), linea.cantidadSolicitada(),
                        linea.idUnidadCantidad(), linea.observaciones()));
            }
        } else {
            SolicitudCita cita = solicitud.cita();
            repositorioDetalleCita.save(
                    new DetalleCitaMedica(idTarea, cita.nombreMedico(), cita.lugarAtencion(), cita.observaciones()));
        }

        long idActor = contexto.usuario().getIdUsuario();
        if (idResponsable != null) {
            long idEstadoAsignacionActivo = idEstadoAsignacion(CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
            repositorioAsignacion.save(new AsignacionTarea(idTarea, idGrupo, idResponsable, idActor, null,
                    idEstadoAsignacionActivo, ahora));
        }

        // Regla 9 de la Fase 4: la tarea manual sí escribe historial al crearse (anterior null -> PENDIENTE).
        repositorioHistorial.save(new HistorialEstadoTarea(idTarea, null, idEstadoPendiente, idActor, null, ahora));

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("tipoTarea", tipoTarea);
        detalles.put("idAdultoMayor", adulto.getIdAdultoMayor());
        servicioAuditoria.registrar(new EventoARegistrar(CodigosTareas.MODULO, CodigosTareas.TIPO_ENTIDAD_TAREA,
                String.valueOf(idTarea), idGrupo, idActor, AccionesTareas.CREAR_TAREA_MANUAL,
                ResultadoAuditoria.EXITO, DetallesAuditoria.conTrazabilidad(contexto, detalles)));

        return ensamblador.ensamblar(tarea);
    }

    /**
     * @throws ExcepcionNoEncontrado el adulto mayor no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio el adulto mayor está inactivo.
     */
    private AdultoMayor adultoActivoDelGrupo(long idGrupo, long idAdultoMayor) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdultoMayor)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
        long idEstadoActivo = servicioCatalogos.idEstado(TABLA_ADULTO_MAYOR, CAMPO_ESTADO, CODIGO_ACTIVO);
        if (adulto.getIdEstado() != idEstadoActivo) {
            throw new ExcepcionReglaNegocio(MENSAJE_ADULTO_INACTIVO);
        }
        return adulto;
    }

    /** @throws ExcepcionReglaNegocio el usuario no tiene membresía {@code ACTIVO} en el grupo. */
    private void exigirIntegranteActivo(long idGrupo, long idUsuario) {
        long idActivo = servicioCatalogos.idEstado(TABLA_INTEGRANTE_GRUPO, CAMPO_ESTADO, CODIGO_ACTIVO);
        boolean esIntegranteActivo = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idUsuario)
                .filter(integrante -> integrante.getIdEstado().longValue() == idActivo)
                .isPresent();
        if (!esIntegranteActivo) {
            throw new ExcepcionReglaNegocio(ServicioAsignacionTareas.MENSAJE_RESPONSABLE_NO_ACTIVO);
        }
    }

    private long idEstadoTareaCuidado(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CAMPO_ESTADO, codigo);
    }

    private long idEstadoAsignacion(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CAMPO_ESTADO, codigo);
    }
}
