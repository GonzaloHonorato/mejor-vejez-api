package cl.adndigital.mejorvejez.sucesion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;

/**
 * Sucesión de la coordinación de un grupo, parte 1 (HU-13, TSA-53):
 * proponer, aceptar, rechazar, cancelar y consultar. Completar la
 * transferencia (reglas 21 y 22) es de la Tarea 4, que reutiliza {@link
 * PropuestaSucesion#completar}, {@link CodigosSucesion}, {@link
 * RepositorioPropuestaSucesion#buscarDelGrupo} y {@link
 * EnsambladorRespuestasPropuesta}.
 *
 * <p><b>Concurrencia (regla 18 de la Fase 7).</b> Toda mutación bloquea
 * primero la fila del grupo ({@code RepositorioGrupoFamiliar#bloquearPorId},
 * {@code SELECT ... FOR UPDATE}), antes de autorizar y antes de leer la
 * propuesta, con el mismo orden bloqueo → autorización → lectura de {@code
 * grupos.ServicioIntegrantes} (ver su javadoc sobre la caché de primer nivel
 * de Hibernate): así dos propuestas simultáneas del mismo grupo se
 * serializan, la segunda ve la primera ya confirmada y recibe 409, y las
 * mutaciones de sucesión también se serializan contra los cambios de
 * integrantes y roles. Como respaldo, {@code UX_PROPUESTA_ACTIVA_GRUPO}: la
 * {@link DataIntegrityViolationException} del {@code saveAndFlush} causada
 * por ese índice se traduce al mismo 409.
 *
 * <p><b>Quién puede qué (regla 16).</b> Proponer exige una membresía {@code
 * COORDINADOR} activa explícita: a diferencia de {@code
 * ServicioAutorizacion#exigirCoordinador}, el administrador sin membresía
 * <b>no</b> pasa. Aceptar y rechazar, solo el candidato (sin excepción para
 * el administrador). Cancelar, el saliente, <b>cualquier COORDINADOR activo
 * del grupo</b> o un administrador (ronda de corrección final, Important 1:
 * así una propuesta no queda huérfana cuando el saliente sale del grupo o lo
 * desactivan, en un grupo con otro coordinador activo).
 *
 * <p><b>Auditoría (regla 23).</b> {@code MODULO = "sucesion"}, entidad
 * {@code PROPUESTA_SUCESION}, todos con {@code
 * DetallesAuditoria#conTrazabilidad}. El motivo de cancelación (texto libre)
 * nunca se audita.
 *
 * <p><b>403 de dominio auditados como {@code RECHAZADA} (ronda de
 * corrección final, minor 1).</b> A diferencia de los rechazos de {@code
 * ServicioAutorizacion} (sin membresía o sin rol de coordinador, ya
 * auditados ahí), los 403 propios de esta clase (no ser el candidato, no ser
 * coordinador al proponer, o no ser el saliente/coordinador/administrador al
 * cancelar) no dejaban rastro. Se auditan con {@link
 * ServicioAuditoria#registrarEnTransaccionPropia} (persisten aunque la
 * transacción del llamador haga rollback, igual que {@code
 * ServicioAutorizacion#rechazar}) y {@code {motivo: "<CODIGO>"}}, con
 * {@link CodigosSucesion#MOTIVO_RECHAZO_NO_ES_CANDIDATO}, {@link
 * CodigosSucesion#MOTIVO_RECHAZO_NO_ES_COORDINADOR} y {@link
 * CodigosSucesion#MOTIVO_RECHAZO_NO_ES_SALIENTE_NI_COORDINADOR}: códigos
 * fijos, nunca texto libre.
 */
@Service
public class ServicioSucesion {

    static final String MENSAJE_PROPUESTA_NO_EXISTE = "La propuesta de sucesión no existe";
    static final String MENSAJE_SOLO_COORDINADOR_PROPONE = "Solo un coordinador del grupo puede proponer un sucesor";
    static final String MENSAJE_SOLO_CANDIDATO_RESPONDE = "Solo el candidato puede responder la propuesta";
    static final String MENSAJE_SOLO_SALIENTE_CANCELA =
            "Solo un coordinador del grupo o un administrador pueden cancelar la propuesta";
    static final String MENSAJE_TRANSICION_INVALIDA = "La propuesta ya no admite esta operación";
    static final String MENSAJE_GRUPO_INACTIVO = "El grupo no está activo";
    static final String MENSAJE_PROPONERSE_A_SI_MISMO = "No puedes proponerte a ti mismo";
    static final String MENSAJE_CANDIDATO_NO_INTEGRANTE = "El candidato debe ser integrante activo del grupo";
    static final String MENSAJE_CUENTA_CANDIDATO_INACTIVA = "La cuenta del candidato no está activa";
    static final String MENSAJE_CANDIDATO_YA_COORDINADOR = "El candidato ya es coordinador del grupo";
    static final String MENSAJE_YA_EXISTE_ACTIVA = "Ya existe una propuesta de sucesión activa en el grupo";

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioRolGrupo repositorioRol;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioPropuestaSucesion repositorioPropuesta;
    private final RepositorioConsultaSucesion repositorioConsulta;
    private final EnsambladorRespuestasPropuesta ensamblador;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioSucesion(RepositorioGrupoFamiliar repositorioGrupo,
            RepositorioIntegranteGrupo repositorioIntegrante, RepositorioRolGrupo repositorioRol,
            RepositorioUsuario repositorioUsuario, RepositorioPropuestaSucesion repositorioPropuesta,
            RepositorioConsultaSucesion repositorioConsulta, EnsambladorRespuestasPropuesta ensamblador,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioRol = repositorioRol;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioPropuesta = repositorioPropuesta;
        this.repositorioConsulta = repositorioConsulta;
        this.ensamblador = ensamblador;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------------
    // Mutaciones
    // ------------------------------------------------------------------

    /**
     * El coordinador actual propone a {@code idUsuarioCandidato} como
     * sucesor; queda como saliente y la propuesta, {@code PENDIENTE}. Orden
     * (reglas 16–18): bloquear el grupo → {@code exigirMiembro} → el actor es
     * {@code COORDINADOR} activo → grupo {@code ACTIVO} → el candidato no es
     * el actor → es integrante {@code ACTIVO} → su cuenta está {@code ACTIVO}
     * → no es ya {@code COORDINADOR} → no hay otra propuesta activa.
     *
     * @throws ExcepcionNoEncontrado   el grupo no existe.
     * @throws ExcepcionAccesoDenegado no es integrante activo, o no es
     *                                 coordinador (el administrador sin
     *                                 membresía tampoco puede).
     * @throws ExcepcionReglaNegocio   alguna validación de la regla 17.
     * @throws ExcepcionConflicto      ya hay una propuesta {@code PENDIENTE}
     *                                 o {@code ACEPTADA} en el grupo.
     */
    @Transactional
    public RespuestaPropuesta proponer(long idGrupo, long idUsuarioCandidato) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        if (!RolGrupo.COORDINADOR.equals(contexto.rol())) {
            auditarRechazo403(contexto, CodigosSucesion.TABLA_GRUPO_FAMILIAR, String.valueOf(idGrupo), idGrupo,
                    CodigosSucesion.ACCION_PROPONER_SUCESION, CodigosSucesion.MOTIVO_RECHAZO_NO_ES_COORDINADOR);
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_COORDINADOR_PROPONE);
        }
        long idSaliente = contexto.usuario().getIdUsuario();

        if (contexto.grupo().getIdEstado() != idEstadoActivo(CodigosSucesion.TABLA_GRUPO_FAMILIAR)) {
            throw new ExcepcionReglaNegocio(MENSAJE_GRUPO_INACTIVO);
        }
        if (idUsuarioCandidato == idSaliente) {
            throw new ExcepcionReglaNegocio(MENSAJE_PROPONERSE_A_SI_MISMO);
        }
        IntegranteGrupo membresiaCandidato = exigirCandidatoActivo(idGrupo, idUsuarioCandidato);
        if (Objects.equals(membresiaCandidato.getIdRol(), idRol(RolGrupo.COORDINADOR))) {
            throw new ExcepcionReglaNegocio(MENSAJE_CANDIDATO_YA_COORDINADOR);
        }
        if (buscarActiva(idGrupo).isPresent()) {
            throw new ExcepcionConflicto(MENSAJE_YA_EXISTE_ACTIVA);
        }

        PropuestaSucesion propuesta = PropuestaSucesion.nueva(idGrupo, idSaliente, idUsuarioCandidato,
                idEstadoPropuesta(CodigosSucesion.ESTADO_PENDIENTE), OffsetDateTime.now(reloj));
        PropuestaSucesion guardada = guardarNueva(propuesta);

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idUsuarioCandidato", idUsuarioCandidato);
        auditar(contexto, guardada, CodigosSucesion.ACCION_PROPONER_SUCESION, detalles);

        return ensamblador.ensamblar(guardada);
    }

    /**
     * El candidato acepta la propuesta ({@code PENDIENTE} → {@code
     * ACEPTADA}, regla 20). No cambia roles, aunque no queden condiciones
     * pendientes: el cambio efectivo lo hace completar (Tarea 4). Audita
     * {@code ACEPTAR_SUCESION} con las condiciones pendientes en ese momento.
     *
     * @throws ExcepcionNoEncontrado   el grupo o la propuesta no existen, o
     *                                 la propuesta es de otro grupo.
     * @throws ExcepcionAccesoDenegado no es integrante activo, o no es el
     *                                 candidato (el administrador tampoco).
     * @throws ExcepcionConflicto      la propuesta no está {@code PENDIENTE}.
     * @throws ExcepcionReglaNegocio   el candidato ya no es integrante
     *                                 activo, su cuenta no está activa, o ya
     *                                 es {@code COORDINADOR} del grupo
     *                                 (ronda de corrección final, minor 2:
     *                                 lo promovieron mientras la propuesta
     *                                 seguía {@code PENDIENTE}).
     */
    @Transactional
    public RespuestaPropuesta aceptar(long idGrupo, long idPropuesta) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        PropuestaSucesion propuesta = buscarDelGrupo(idGrupo, idPropuesta);
        exigirCandidato(contexto, propuesta, CodigosSucesion.ACCION_ACEPTAR_SUCESION);
        exigirEstado(propuesta, CodigosSucesion.ESTADO_PENDIENTE);
        // Revalidación (regla 20). Con el actor = candidato, exigirMiembro y ServicioUsuarioActual
        // ya lo garantizan; se deja explícita como defensa en profundidad, con el 422 de la regla 17.
        IntegranteGrupo membresiaCandidato = exigirCandidatoActivo(idGrupo, propuesta.getIdUsuarioCandidato());
        // Minor 2: si lo promovieron a COORDINADOR mientras la propuesta seguía PENDIENTE, no la deja
        // quedar ACEPTADA (completar la rechazaría igual, pero antes). Mismo chequeo que proponer/completar.
        if (Objects.equals(membresiaCandidato.getIdRol(), idRol(RolGrupo.COORDINADOR))) {
            throw new ExcepcionReglaNegocio(MENSAJE_CANDIDATO_YA_COORDINADOR);
        }

        propuesta.aceptar(idEstadoPropuesta(CodigosSucesion.ESTADO_ACEPTADA), OffsetDateTime.now(reloj));
        PropuestaSucesion guardada = repositorioPropuesta.save(propuesta);

        List<Long> idsCondiciones = repositorioConsulta.idsCondicionesPendientes(idGrupo,
                guardada.getIdUsuarioSaliente());
        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("condicionesPendientes", idsCondiciones.size());
        detalles.put("idsTareasPendientes", idsCondiciones);
        auditar(contexto, guardada, CodigosSucesion.ACCION_ACEPTAR_SUCESION, detalles);

        return ensamblador.ensamblar(guardada, idsCondiciones);
    }

    /**
     * El candidato rechaza la propuesta ({@code PENDIENTE} → {@code
     * RECHAZADA}): se cierra, queda en el historial y el grupo puede recibir
     * una nueva propuesta.
     *
     * @throws ExcepcionNoEncontrado   el grupo o la propuesta no existen, o
     *                                 la propuesta es de otro grupo.
     * @throws ExcepcionAccesoDenegado no es integrante activo, o no es el
     *                                 candidato (el administrador tampoco).
     * @throws ExcepcionConflicto      la propuesta no está {@code PENDIENTE}.
     */
    @Transactional
    public RespuestaPropuesta rechazar(long idGrupo, long idPropuesta) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        PropuestaSucesion propuesta = buscarDelGrupo(idGrupo, idPropuesta);
        exigirCandidato(contexto, propuesta, CodigosSucesion.ACCION_RECHAZAR_SUCESION);
        exigirEstado(propuesta, CodigosSucesion.ESTADO_PENDIENTE);

        propuesta.rechazar(idEstadoPropuesta(CodigosSucesion.ESTADO_RECHAZADA), OffsetDateTime.now(reloj));
        PropuestaSucesion guardada = repositorioPropuesta.save(propuesta);
        auditar(contexto, guardada, CodigosSucesion.ACCION_RECHAZAR_SUCESION, null);

        return ensamblador.ensamblar(guardada);
    }

    /**
     * El saliente, cualquier {@code COORDINADOR} activo del grupo o un
     * administrador cancelan la propuesta ({@code PENDIENTE} | {@code
     * ACEPTADA} → {@code CANCELADA}), con un motivo opcional (recortado; en
     * blanco queda {@code null}). Los roles no cambian. Audita {@code
     * CANCELAR_SUCESION} con {@code estadoAnterior}, sin el motivo.
     *
     * <p>Ronda de corrección final, Important 1: antes solo el saliente o un
     * administrador podían cancelar, así que si el saliente salía del grupo
     * (o lo desactivaban) la propuesta quedaba huérfana en un grupo con otro
     * coordinador. Ahora ese otro coordinador también puede cerrarla.
     *
     * @throws ExcepcionNoEncontrado   el grupo o la propuesta no existen, o
     *                                 la propuesta es de otro grupo.
     * @throws ExcepcionAccesoDenegado no es integrante activo, o no es el
     *                                 saliente, un coordinador activo del
     *                                 grupo, ni un administrador.
     * @throws ExcepcionConflicto      la propuesta no está {@code PENDIENTE}
     *                                 ni {@code ACEPTADA}.
     */
    @Transactional
    public RespuestaPropuesta cancelar(long idGrupo, long idPropuesta, String motivo) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        PropuestaSucesion propuesta = buscarDelGrupo(idGrupo, idPropuesta);

        boolean puedeCancelar = contexto.esAdministrador() || RolGrupo.COORDINADOR.equals(contexto.rol())
                || Objects.equals(propuesta.getIdUsuarioSaliente(), contexto.usuario().getIdUsuario());
        if (!puedeCancelar) {
            auditarRechazo403(contexto, CodigosSucesion.TIPO_ENTIDAD_PROPUESTA, String.valueOf(idPropuesta), idGrupo,
                    CodigosSucesion.ACCION_CANCELAR_SUCESION,
                    CodigosSucesion.MOTIVO_RECHAZO_NO_ES_SALIENTE_NI_COORDINADOR);
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_SALIENTE_CANCELA);
        }
        exigirEstado(propuesta, CodigosSucesion.ESTADO_PENDIENTE, CodigosSucesion.ESTADO_ACEPTADA);
        String estadoAnterior = codigoActivo(propuesta);

        propuesta.cancelar(idEstadoPropuesta(CodigosSucesion.ESTADO_CANCELADA), OffsetDateTime.now(reloj),
                SolicitudCancelarSucesion.recortarOpcional(motivo));
        PropuestaSucesion guardada = repositorioPropuesta.save(propuesta);

        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("estadoAnterior", estadoAnterior);
        auditar(contexto, guardada, CodigosSucesion.ACCION_CANCELAR_SUCESION, detalles);

        return ensamblador.ensamblar(guardada);
    }

    // ------------------------------------------------------------------
    // Consultas (regla 15)
    // ------------------------------------------------------------------

    /**
     * La propuesta {@code PENDIENTE} o {@code ACEPTADA} del grupo, con sus
     * condiciones pendientes; vacío si no hay ninguna (el controlador
     * responde 204).
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    @Transactional(readOnly = true)
    public Optional<RespuestaPropuesta> activa(long idGrupo) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        return buscarActiva(idGrupo).map(ensamblador::ensamblar);
    }

    /**
     * Todas las propuestas del grupo, de la más reciente a la más antigua,
     * sin condiciones pendientes.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    @Transactional(readOnly = true)
    public List<RespuestaPropuesta> historial(long idGrupo) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        return ensamblador.ensamblarSinCondiciones(
                repositorioPropuesta.findByIdGrupoOrderByFechaCreacionDescIdPropuestaDesc(idGrupo));
    }

    /**
     * La propuesta, con sus condiciones pendientes si está activa.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   la propuesta no existe o es de otro grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPropuesta obtener(long idGrupo, long idPropuesta) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        return ensamblador.ensamblar(buscarDelGrupo(idGrupo, idPropuesta));
    }

    // ------------------------------------------------------------------
    // Validaciones
    // ------------------------------------------------------------------

    /**
     * Membresía {@code ACTIVO} del candidato en el grupo, con su cuenta
     * {@code ACTIVO} (regla 17).
     *
     * @throws ExcepcionReglaNegocio no es integrante activo, o su cuenta no
     *                               está activa.
     */
    private IntegranteGrupo exigirCandidatoActivo(long idGrupo, long idUsuarioCandidato) {
        long idIntegranteActivo = idEstadoActivo(CodigosSucesion.TABLA_INTEGRANTE_GRUPO);
        IntegranteGrupo membresia = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idUsuarioCandidato)
                .filter(integrante -> integrante.getIdEstado() == idIntegranteActivo)
                .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_CANDIDATO_NO_INTEGRANTE));

        long idUsuarioActivo = idEstadoActivo(CodigosSucesion.TABLA_USUARIO);
        boolean cuentaActiva = repositorioUsuario.findById(idUsuarioCandidato)
                .filter(usuario -> usuario.getIdEstado() == idUsuarioActivo)
                .isPresent();
        if (!cuentaActiva) {
            throw new ExcepcionReglaNegocio(MENSAJE_CUENTA_CANDIDATO_INACTIVA);
        }
        return membresia;
    }

    /**
     * @param codigoAccion la acción que se intentaba ({@code ACEPTAR_SUCESION} o {@code
     *                      RECHAZAR_SUCESION}), para auditar el rechazo (minor 1).
     * @throws ExcepcionAccesoDenegado el actor no es el candidato de la propuesta.
     */
    private void exigirCandidato(ContextoGrupo contexto, PropuestaSucesion propuesta, String codigoAccion) {
        if (!Objects.equals(propuesta.getIdUsuarioCandidato(), contexto.usuario().getIdUsuario())) {
            auditarRechazo403(contexto, CodigosSucesion.TIPO_ENTIDAD_PROPUESTA,
                    String.valueOf(propuesta.getIdPropuesta()), propuesta.getIdGrupo(), codigoAccion,
                    CodigosSucesion.MOTIVO_RECHAZO_NO_ES_CANDIDATO);
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_CANDIDATO_RESPONDE);
        }
    }

    /** @throws ExcepcionConflicto la propuesta no está en ninguno de {@code codigosPermitidos}. */
    private void exigirEstado(PropuestaSucesion propuesta, String... codigosPermitidos) {
        for (String codigo : codigosPermitidos) {
            if (Objects.equals(propuesta.getIdEstado(), idEstadoPropuesta(codigo))) {
                return;
            }
        }
        throw new ExcepcionConflicto(MENSAJE_TRANSICION_INVALIDA);
    }

    /** Código ({@code PENDIENTE} | {@code ACEPTADA}) de una propuesta que ya pasó {@link #exigirEstado}. */
    private String codigoActivo(PropuestaSucesion propuesta) {
        return Objects.equals(propuesta.getIdEstado(), idEstadoPropuesta(CodigosSucesion.ESTADO_ACEPTADA))
                ? CodigosSucesion.ESTADO_ACEPTADA
                : CodigosSucesion.ESTADO_PENDIENTE;
    }

    // ------------------------------------------------------------------
    // Persistencia y lecturas
    // ------------------------------------------------------------------

    /**
     * Inserta la propuesta con {@code saveAndFlush}, para que una violación
     * de {@code UX_PROPUESTA_ACTIVA_GRUPO} salga aquí (y no al confirmar) y
     * se traduzca al 409 de la regla 17. Cualquier otra violación de
     * integridad se propaga tal cual.
     */
    private PropuestaSucesion guardarNueva(PropuestaSucesion propuesta) {
        try {
            return repositorioPropuesta.saveAndFlush(propuesta);
        } catch (DataIntegrityViolationException violacion) {
            if (esPorIndiceDePropuestaActiva(violacion)) {
                throw new ExcepcionConflicto(MENSAJE_YA_EXISTE_ACTIVA);
            }
            throw violacion;
        }
    }

    private boolean esPorIndiceDePropuestaActiva(DataIntegrityViolationException violacion) {
        Throwable causa = NestedExceptionUtils.getMostSpecificCause(violacion);
        String mensaje = causa.getMessage();
        return mensaje != null && mensaje.contains(CodigosSucesion.INDICE_PROPUESTA_ACTIVA);
    }

    /** @throws ExcepcionNoEncontrado la propuesta no existe o es de otro grupo. */
    private PropuestaSucesion buscarDelGrupo(long idGrupo, long idPropuesta) {
        return repositorioPropuesta.buscarDelGrupo(idGrupo, idPropuesta)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_PROPUESTA_NO_EXISTE));
    }

    private Optional<PropuestaSucesion> buscarActiva(long idGrupo) {
        return repositorioPropuesta.findFirstByIdGrupoAndIdEstadoIn(idGrupo,
                List.of(idEstadoPropuesta(CodigosSucesion.ESTADO_PENDIENTE),
                        idEstadoPropuesta(CodigosSucesion.ESTADO_ACEPTADA)));
    }

    /** Bloquea la fila del grupo ({@code SELECT ... FOR UPDATE}); ver javadoc de la clase. */
    private void bloquearGrupo(long idGrupo) {
        repositorioGrupo.bloquearPorId(idGrupo).orElseThrow(() -> new ExcepcionNoEncontrado("El grupo no existe"));
    }

    private void auditar(ContextoGrupo contexto, PropuestaSucesion propuesta, String codigoAccion,
            Map<String, Object> detalles) {
        servicioAuditoria.registrar(new EventoARegistrar(CodigosSucesion.MODULO,
                CodigosSucesion.TIPO_ENTIDAD_PROPUESTA, String.valueOf(propuesta.getIdPropuesta()),
                propuesta.getIdGrupo(), contexto.usuario().getIdUsuario(), codigoAccion, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    /**
     * Audita un 403 de dominio de esta clase como {@code RECHAZADA}, en
     * transacción propia ({@code REQUIRES_NEW}): persiste aunque la
     * transacción del llamador haga rollback por la excepción que sigue,
     * igual que {@code ServicioAutorizacion#rechazar} y {@code
     * ServicioCompletarSucesion#auditarRechazo} (ronda de corrección final,
     * minor 1). {@code motivoCodigo} es un código fijo ({@link
     * CodigosSucesion}), nunca texto libre.
     */
    private void auditarRechazo403(ContextoGrupo contexto, String tipoEntidad, String idEntidad, long idGrupo,
            String codigoAccion, String motivoCodigo) {
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosSucesion.MODULO, tipoEntidad,
                idEntidad, idGrupo, contexto.usuario().getIdUsuario(), codigoAccion, ResultadoAuditoria.RECHAZADA,
                Map.of("motivo", motivoCodigo)));
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
