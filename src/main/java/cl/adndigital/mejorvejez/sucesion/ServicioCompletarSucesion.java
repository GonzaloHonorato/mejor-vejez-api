package cl.adndigital.mejorvejez.sucesion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.IntegranteGrupo;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.grupos.RepositorioRolGrupo;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.tareas.ServicioAsignacionTareas;
import cl.adndigital.mejorvejez.tareas.SolicitudAsignarTarea;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;

/**
 * Sucesión de la coordinación, parte 2 (HU-13, TSA-53, reglas 21 y 22 de la
 * Fase 7): completar la transferencia de una propuesta {@code ACEPTADA}. Las
 * reasignaciones de las condiciones pendientes, el cambio de roles y el
 * estado {@code COMPLETADA} ocurren en <b>una sola</b> transacción: ante
 * cualquier excepción se revierte todo (roles, asignaciones, propuesta y los
 * eventos de auditoría de esa transacción), la propuesta sigue {@code
 * ACEPTADA} y el grupo conserva a su coordinador.
 *
 * <p><b>Orden (regla 21).</b> Bloquear el grupo → {@code exigirMiembro} → el
 * actor es el saliente o un administrador (403) → leer la propuesta (404) →
 * está {@code ACEPTADA} (409) → revalidar saliente y candidato (422) → cada
 * reasignación (condición pendiente, destino distinto del saliente, y {@link
 * ServicioAsignacionTareas#asignar}) → recalcular condiciones (422 si queda
 * alguna) → roles → al menos un coordinador activo → {@code COMPLETADA}. Se
 * bloquea el grupo antes de autorizar y de leer la propuesta, igual que
 * {@link ServicioSucesion} (ver su javadoc): así dos completar simultáneos
 * se serializan y el segundo ve la propuesta ya {@code COMPLETADA} (409).
 * Las reasignaciones van <b>antes</b> del cambio de roles porque {@code
 * asignar} exige {@code exigirCoordinador}: el saliente aún lo es. El orden
 * de bloqueos es grupo → tarea ({@code asignar} bloquea cada tarea).
 *
 * <p><b>Lecturas después de las reasignaciones.</b> Las condiciones se
 * recalculan con la consulta JDBC ({@link
 * RepositorioConsultaSucesion#idsCondicionesPendientes}), que va a la BD y no
 * a entidades en caché, después de un {@code flush} explícito de la sesión
 * (lección de la Fase 5).
 *
 * <p><b>Rechazos (regla 22).</b> Los 409/422 de completar (incluidos los de
 * {@code asignar}) se registran además como {@code COMPLETAR_SUCESION}
 * {@code RECHAZADA} con {@code registrarEnTransaccionPropia} y {@code
 * {motivo}} (el mensaje del rechazo, generado por el backend), y se
 * relanzan: la transacción principal se revierte igual. El 403 de no ser el
 * saliente ni un administrador se audita igual, con el código fijo {@link
 * CodigosSucesion#MOTIVO_RECHAZO_NO_ES_SALIENTE} (ronda de corrección final,
 * minor 1). Una excepción inesperada (p. ej. de la auditoría del éxito, ver
 * {@link #transferir}) también revierte todo, pero no se audita como
 * rechazo.
 *
 * <p><b>Si falla el registro del rechazo (ronda de corrección final, minor
 * 6/7).</b> {@link #auditarRechazo} nunca deja que un fallo de {@code
 * registrarEnTransaccionPropia} (pool agotado, BD caída) reemplace la
 * excepción 403/409/422 original: la atrapa, la agrega con {@link
 * Throwable#addSuppressed} y relanza la original tal cual, el mismo patrón
 * que {@code alertas.CorridaAlertas#auditarFallo} y {@code
 * tareas.ServicioGeneracionTareas#auditarFallo}.
 *
 * <p>Las reasignaciones se aplican en orden ascendente de {@code idTarea}
 * (ronda de corrección final, minor 3), el mismo orden de bloqueo que {@code
 * tareas.ServicioRecalculoTareas#bloquearPorIds}: evita un ORA-00060 si el
 * recálculo de un detalle de plan bloquea las mismas tareas al mismo tiempo
 * (ver {@link #reasignar}).
 *
 * <p>Las alertas no se tocan, y las reasignaciones hechas antes por F4 no se
 * revierten.
 */
@Service
public class ServicioCompletarSucesion {

    static final String MENSAJE_SOLO_SALIENTE_COMPLETA =
            "Solo el coordinador saliente o un administrador pueden completar la transferencia";
    static final String MENSAJE_SALIENTE_YA_NO_COORDINADOR =
            "El coordinador saliente ya no es coordinador activo; cancela la propuesta";
    static final String MENSAJE_CANDIDATO_YA_NO_ACTIVO =
            "El candidato ya no es integrante activo; cancela la propuesta";
    static final String MENSAJE_TAREA_NO_ES_CONDICION = "La tarea %d no es una condición pendiente de la sucesión";
    static final String MENSAJE_REASIGNAR_AL_SALIENTE = "No puedes reasignar la tarea al coordinador saliente";
    static final String MENSAJE_QUEDAN_CONDICIONES = "Quedan %d tareas por resolver o reasignar";

    /** Motivo fijo de cada reasignación ({@code ASIGNACION_TAREA.MOTIVO}). */
    static final String MOTIVO_REASIGNACION = "Sucesión de la coordinación";

    /** Auditoría del cambio de rol: la misma de {@code grupos.ServicioIntegrantes}. */
    private static final String MODULO_GRUPOS = "grupos";
    private static final String TIPO_ENTIDAD_INTEGRANTE = "INTEGRANTE_GRUPO";
    private static final String ACCION_CAMBIAR_ROL_INTEGRANTE = "CAMBIAR_ROL_INTEGRANTE";
    private static final String MOTIVO_CAMBIO_ROL = "SUCESION";

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioRolGrupo repositorioRol;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioPropuestaSucesion repositorioPropuesta;
    private final RepositorioConsultaSucesion repositorioConsulta;
    private final EnsambladorRespuestasPropuesta ensamblador;
    private final ServicioAsignacionTareas servicioAsignacion;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioCompletarSucesion(RepositorioGrupoFamiliar repositorioGrupo,
            RepositorioIntegranteGrupo repositorioIntegrante, RepositorioRolGrupo repositorioRol,
            RepositorioUsuario repositorioUsuario, RepositorioPropuestaSucesion repositorioPropuesta,
            RepositorioConsultaSucesion repositorioConsulta, EnsambladorRespuestasPropuesta ensamblador,
            ServicioAsignacionTareas servicioAsignacion, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioRol = repositorioRol;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioPropuesta = repositorioPropuesta;
        this.repositorioConsulta = repositorioConsulta;
        this.ensamblador = ensamblador;
        this.servicioAsignacion = servicioAsignacion;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Completa la transferencia: aplica las reasignaciones pedidas, exige
     * que no quede ninguna condición pendiente, deja al candidato como
     * {@code COORDINADOR} y al saliente como {@code COLABORADOR} activo, y la
     * propuesta {@code COMPLETADA} con {@code FECHA_COMPLETADO = ahora}.
     * Audita {@code REASIGNAR_TAREA} (por {@code asignar}), dos {@code
     * CAMBIAR_ROL_INTEGRANTE} y {@code COMPLETAR_SUCESION}.
     *
     * @param solicitud las reasignaciones; {@code null} equivale a ninguna.
     * @throws ExcepcionNoEncontrado   el grupo o la propuesta no existen, o
     *                                 la propuesta es de otro grupo.
     * @throws ExcepcionAccesoDenegado no es integrante activo (ni
     *                                 administrador), o no es el saliente
     *                                 ni un administrador.
     * @throws ExcepcionConflicto      la propuesta no está {@code ACEPTADA}.
     * @throws ExcepcionReglaNegocio   falla una revalidación, una
     *                                 reasignación es inválida, o quedan
     *                                 condiciones pendientes.
     */
    @Transactional
    public RespuestaPropuesta completar(long idGrupo, long idPropuesta, SolicitudCompletarSucesion solicitud) {
        List<SolicitudReasignacion> reasignaciones = solicitud == null ? List.of() : solicitud.reasignaciones();

        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        PropuestaSucesion propuesta = repositorioPropuesta.buscarDelGrupo(idGrupo, idPropuesta)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE));
        boolean puedeCompletar = contexto.esAdministrador()
                || Objects.equals(propuesta.getIdUsuarioSaliente(), contexto.usuario().getIdUsuario());
        if (!puedeCompletar) {
            ExcepcionAccesoDenegado rechazo = new ExcepcionAccesoDenegado(MENSAJE_SOLO_SALIENTE_COMPLETA);
            auditarRechazo(contexto, propuesta, CodigosSucesion.MOTIVO_RECHAZO_NO_ES_SALIENTE, rechazo);
            throw rechazo;
        }

        try {
            return transferir(contexto, propuesta, reasignaciones);
        } catch (ExcepcionConflicto | ExcepcionReglaNegocio rechazo) {
            auditarRechazo(contexto, propuesta, rechazo.getMessage(), rechazo);
            throw rechazo;
        }
    }

    /** Pasos 3 a 9 de la regla 21, ya con el grupo bloqueado y el actor autorizado. */
    private RespuestaPropuesta transferir(ContextoGrupo contexto, PropuestaSucesion propuesta,
            List<SolicitudReasignacion> reasignaciones) {
        long idGrupo = propuesta.getIdGrupo();
        long idSaliente = propuesta.getIdUsuarioSaliente();
        if (!Objects.equals(propuesta.getIdEstado(), idEstadoPropuesta(CodigosSucesion.ESTADO_ACEPTADA))) {
            throw new ExcepcionConflicto(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);
        }

        long idRolCoordinador = idRol(RolGrupo.COORDINADOR);
        long idRolColaborador = idRol(RolGrupo.COLABORADOR);
        IntegranteGrupo membresiaSaliente = exigirSalienteCoordinador(idGrupo, idSaliente, idRolCoordinador);
        IntegranteGrupo membresiaCandidato = exigirCandidatoActivo(idGrupo, propuesta.getIdUsuarioCandidato());
        if (Objects.equals(membresiaCandidato.getIdRol(), idRolCoordinador)) {
            throw new ExcepcionReglaNegocio(ServicioSucesion.MENSAJE_CANDIDATO_YA_COORDINADOR);
        }

        reasignar(idGrupo, idSaliente, reasignaciones);

        // Las asignaciones nuevas deben estar en la BD antes de la consulta JDBC (que no hace flush de Hibernate).
        repositorioIntegrante.flush();
        int restantes = repositorioConsulta.idsCondicionesPendientes(idGrupo, idSaliente).size();
        if (restantes > 0) {
            throw new ExcepcionReglaNegocio(String.format(MENSAJE_QUEDAN_CONDICIONES, restantes));
        }

        cambiarRol(contexto, membresiaCandidato, idRolCoordinador);
        cambiarRol(contexto, membresiaSaliente, idRolColaborador);
        exigirAlMenosUnCoordinadorActivo(idGrupo, idRolCoordinador);

        propuesta.completar(idEstadoPropuesta(CodigosSucesion.ESTADO_COMPLETADA), OffsetDateTime.now(reloj));
        PropuestaSucesion guardada = repositorioPropuesta.save(propuesta);

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idUsuarioSaliente", idSaliente);
        detalles.put("idUsuarioCandidato", guardada.getIdUsuarioCandidato());
        detalles.put("reasignaciones", reasignaciones.size());
        servicioAuditoria.registrar(eventoPropuesta(contexto, guardada, ResultadoAuditoria.EXITO, detalles));

        return ensamblador.ensamblar(guardada);
    }

    /**
     * Aplica las reasignaciones con {@link ServicioAsignacionTareas#asignar}
     * (que valida que el destino sea integrante activo y audita {@code
     * REASIGNAR_TAREA}), <b>en orden ascendente de {@code idTarea}</b> (ronda
     * de corrección final, minor 3), nunca en el orden en que llegan en la
     * solicitud: es el mismo orden de bloqueo que {@code
     * ServicioRecalculoTareas#bloquearPorIds}, así que dos tareas del mismo
     * detalle de plan reasignadas aquí en orden inverso mientras alguien
     * recalcula ese plan ya no pueden formar un ciclo de bloqueos (ORA-00060)
     * con {@code asignar} (que bloquea cada tarea por separado). Cada una
     * debe ser de una tarea de las condiciones pendientes calculadas al
     * empezar, una sola vez, y con un destino distinto del saliente.
     *
     * @throws ExcepcionReglaNegocio la primera reasignación inválida (en el
     *                               orden ascendente de {@code idTarea}, no
     *                               el de la solicitud); las anteriores se
     *                               revierten con la transacción.
     */
    private void reasignar(long idGrupo, long idSaliente, List<SolicitudReasignacion> reasignaciones) {
        Set<Long> condiciones = new LinkedHashSet<>(repositorioConsulta.idsCondicionesPendientes(idGrupo, idSaliente));
        List<SolicitudReasignacion> ordenadasPorIdTarea = reasignaciones.stream()
                .sorted(Comparator.comparing(SolicitudReasignacion::idTarea))
                .toList();
        for (SolicitudReasignacion reasignacion : ordenadasPorIdTarea) {
            long idTarea = reasignacion.idTarea();
            // remove: una misma tarea repetida en la lista ya no es condición la segunda vez.
            if (!condiciones.remove(idTarea)) {
                throw new ExcepcionReglaNegocio(String.format(MENSAJE_TAREA_NO_ES_CONDICION, idTarea));
            }
            if (reasignacion.idUsuario() == idSaliente) {
                throw new ExcepcionReglaNegocio(MENSAJE_REASIGNAR_AL_SALIENTE);
            }
            servicioAsignacion.asignar(idGrupo, idTarea,
                    new SolicitudAsignarTarea(reasignacion.idUsuario(), MOTIVO_REASIGNACION));
        }
    }

    // ------------------------------------------------------------------
    // Revalidación (regla 21.4)
    // ------------------------------------------------------------------

    /** @throws ExcepcionReglaNegocio el saliente ya no tiene una membresía {@code COORDINADOR} activa. */
    private IntegranteGrupo exigirSalienteCoordinador(long idGrupo, long idSaliente, long idRolCoordinador) {
        long idIntegranteActivo = idEstadoActivo(CodigosSucesion.TABLA_INTEGRANTE_GRUPO);
        return repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idSaliente)
                .filter(integrante -> integrante.getIdEstado() == idIntegranteActivo)
                .filter(integrante -> Objects.equals(integrante.getIdRol(), idRolCoordinador))
                .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_SALIENTE_YA_NO_COORDINADOR));
    }

    /** @throws ExcepcionReglaNegocio el candidato ya no es integrante activo, o su cuenta no está activa. */
    private IntegranteGrupo exigirCandidatoActivo(long idGrupo, long idCandidato) {
        long idIntegranteActivo = idEstadoActivo(CodigosSucesion.TABLA_INTEGRANTE_GRUPO);
        IntegranteGrupo membresia = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idCandidato)
                .filter(integrante -> integrante.getIdEstado() == idIntegranteActivo)
                .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_CANDIDATO_YA_NO_ACTIVO));
        long idUsuarioActivo = idEstadoActivo(CodigosSucesion.TABLA_USUARIO);
        boolean cuentaActiva = repositorioUsuario.findById(idCandidato)
                .filter(usuario -> usuario.getIdEstado() == idUsuarioActivo)
                .isPresent();
        if (!cuentaActiva) {
            throw new ExcepcionReglaNegocio(MENSAJE_CANDIDATO_YA_NO_ACTIVO);
        }
        return membresia;
    }

    // ------------------------------------------------------------------
    // Roles (reglas 21.7 y 21.8)
    // ------------------------------------------------------------------

    /**
     * Cambia el rol de la membresía y audita {@code CAMBIAR_ROL_INTEGRANTE}
     * como {@code grupos.ServicioIntegrantes}, con {@code motivo: "SUCESION"}
     * y la trazabilidad de administrador.
     */
    private void cambiarRol(ContextoGrupo contexto, IntegranteGrupo integrante, long idRolNuevo) {
        long idRolAnterior = integrante.getIdRol();
        integrante.cambiarRol(idRolNuevo);
        IntegranteGrupo guardado = repositorioIntegrante.save(integrante);

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idRolAnterior", idRolAnterior);
        detalles.put("idRolNuevo", idRolNuevo);
        detalles.put("motivo", MOTIVO_CAMBIO_ROL);
        servicioAuditoria.registrar(new EventoARegistrar(MODULO_GRUPOS, TIPO_ENTIDAD_INTEGRANTE,
                String.valueOf(guardado.getIdIntegrante()), guardado.getIdGrupo(), contexto.usuario().getIdUsuario(),
                ACCION_CAMBIAR_ROL_INTEGRANTE, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    /**
     * Defensa en profundidad: tras el cambio de roles debe quedar al menos un
     * {@code COORDINADOR} activo. El conteo JPQL hace flush de los cambios de
     * la sesión antes de consultar.
     *
     * @throws IllegalStateException no queda ninguno (revierte todo).
     */
    private void exigirAlMenosUnCoordinadorActivo(long idGrupo, long idRolCoordinador) {
        long coordinadores = repositorioIntegrante.countByIdGrupoAndIdRolAndIdEstado(idGrupo, idRolCoordinador,
                idEstadoActivo(CodigosSucesion.TABLA_INTEGRANTE_GRUPO));
        if (coordinadores < 1) {
            throw new IllegalStateException("El grupo " + idGrupo + " quedaría sin coordinador activo");
        }
    }

    // ------------------------------------------------------------------
    // Auditoría y lecturas
    // ------------------------------------------------------------------

    /**
     * {@code COMPLETAR_SUCESION} {@code RECHAZADA} en transacción propia
     * ({@code REQUIRES_NEW}), igual que {@code ServicioAutorizacion#rechazar}:
     * persiste aunque la transacción principal se revierta.
     *
     * <p>Si el registro mismo falla (pool agotado, BD caída), no reemplaza
     * la excepción 403/409/422 ({@code original}) que motivó el rechazo: se
     * atrapa, se agrega con {@link Throwable#addSuppressed} y no se relanza
     * aquí (ronda de corrección final, minor 6/7), el mismo patrón que
     * {@code alertas.CorridaAlertas#auditarFallo} y {@code
     * tareas.ServicioGeneracionTareas#auditarFallo}. Quien llama sigue
     * relanzando {@code original}.
     */
    private void auditarRechazo(ContextoGrupo contexto, PropuestaSucesion propuesta, String motivo,
            RuntimeException original) {
        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("motivo", motivo);
        try {
            servicioAuditoria.registrarEnTransaccionPropia(
                    eventoPropuesta(contexto, propuesta, ResultadoAuditoria.RECHAZADA, detalles));
        } catch (RuntimeException alAuditar) {
            original.addSuppressed(alAuditar);
        }
    }

    private EventoARegistrar eventoPropuesta(ContextoGrupo contexto, PropuestaSucesion propuesta,
            ResultadoAuditoria resultado, Map<String, Object> detalles) {
        return new EventoARegistrar(CodigosSucesion.MODULO, CodigosSucesion.TIPO_ENTIDAD_PROPUESTA,
                String.valueOf(propuesta.getIdPropuesta()), propuesta.getIdGrupo(), contexto.usuario().getIdUsuario(),
                CodigosSucesion.ACCION_COMPLETAR_SUCESION, resultado,
                DetallesAuditoria.conTrazabilidad(contexto, detalles));
    }

    /** Bloquea la fila del grupo ({@code SELECT ... FOR UPDATE}); ver javadoc de la clase. */
    private void bloquearGrupo(long idGrupo) {
        repositorioGrupo.bloquearPorId(idGrupo).orElseThrow(() -> new ExcepcionNoEncontrado("El grupo no existe"));
    }

    private long idRol(String nombreRol) {
        return repositorioRol.findByNombreRol(nombreRol)
                .orElseThrow(() -> new IllegalStateException("No existe el rol " + nombreRol))
                .getIdRol();
    }

    private long idEstadoActivo(String tabla) {
        return servicioCatalogos.idEstado(tabla, CodigosSucesion.CAMPO_ESTADO, CodigosSucesion.ESTADO_ACTIVO);
    }

    private long idEstadoPropuesta(String codigo) {
        return servicioCatalogos.idEstado(CodigosSucesion.TABLA_PROPUESTA_SUCESION, CodigosSucesion.CAMPO_ESTADO,
                codigo);
    }
}
