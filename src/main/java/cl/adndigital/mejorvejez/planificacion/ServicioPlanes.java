package cl.adndigital.mejorvejez.planificacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
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
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Crear, consultar, actualizar, desactivar, reactivar y eliminar planes de
 * medicamentos de un adulto mayor (HU-04). La autorización por grupo la
 * exige {@link ServicioAutorizacion} en cada operación: consultar exige solo
 * membresía activa; mutar exige coordinador (el administrador pasa ambas sin
 * membresía, como ya hace {@code ServicioAutorizacion}).
 *
 * <p><b>Orden de cada mutación.</b> {@code exigirCoordinador(idGrupo)} →
 * {@code repositorioPlan.bloquearPorId(idPlan)} (con {@code SELECT ... FOR
 * UPDATE}, serializa toda mutación del mismo plan) → validar que el plan
 * pertenezca al grupo de la URL → reglas → guardar → auditar. Autorizar
 * antes de bloquear evita revelar en qué grupo está un plan ajeno.
 *
 * <p><b>Vigencia.</b> {@code vigenteHasta} nunca puede ser anterior a {@code
 * vigenteDesde} ({@code CK_PLAN_VIGENCIA}), y se valida ANTES de llegar a la
 * BD para no terminar en el 409 genérico de {@code
 * DataIntegrityViolationException}. Al acortar la vigencia, ningún {@link
 * DetallePlan} {@code ACTIVO} puede quedar fuera del nuevo rango.
 *
 * <p><b>Bajas.</b> Desactivar/reactivar son siempre lógicas y no tocan los
 * detalles del plan. Eliminar es físico (vía borrado de entidades JPA, nunca
 * JPQL/JDBC masivo, para que Hibernate borre también las filas de {@code
 * DETALLE_PLAN_DIA}/{@code DETALLE_PLAN_HORARIO} de cada detalle), y solo si
 * el plan no tiene historial ni otras referencias ({@code
 * RepositorioHistorialPlan#planTieneReferencias}); si las tiene, debe
 * desactivarse en su lugar.
 *
 * <p><b>Tareas (F4).</b> {@code actualizar} (solo si cambió la vigencia),
 * {@code desactivar} y {@code reactivar} publican {@link
 * EventoCambioPlanificacion} (todos los detalles) después de guardar y antes
 * de auditar: el recálculo síncrono de F4 ({@code
 * tareas.ServicioRecalculoTareas}, regla 8) cancela las tareas futuras
 * PENDIENTE que ya no corresponden y genera las que faltan, en esta misma
 * transacción (si falla, el cambio se revierte).
 *
 * <p><b>Sin N+1 al listar.</b> {@link #listar} resuelve {@code nombreAdulto}
 * (una consulta {@code findAllById} para todos los adultos de la página) y
 * {@code medicamentosActivos} (una consulta agrupada, {@link
 * RepositorioDetallePlan#contarActivosPorPlan}) sin una consulta por plan.
 *
 * <p><b>Colecciones LAZY.</b> {@code spring.jpa.open-in-view=false}: {@link
 * #obtener} es {@code @Transactional} para construir {@link
 * RespuestaDetallePlan} (que lee {@code diasSemana}/{@code horas}, ambas
 * {@code @ElementCollection} LAZY) dentro de la misma transacción que las
 * carga, nunca después de que la sesión de Hibernate se cerró.
 *
 * <p><b>Trazabilidad de administrador.</b> Ver {@link DetallesAuditoria}.
 */
@Service
public class ServicioPlanes {

    private static final String MODULO = "planificacion";
    private static final String TIPO_ENTIDAD = TiposEntidadPlanificacion.PLAN_MEDICAMENTOS;
    private static final String TABLA_ESTADO_PLAN = "PLAN_MEDICAMENTOS";
    private static final String TABLA_ESTADO_DETALLE = "DETALLE_PLAN";
    private static final String TABLA_ESTADO_ADULTO = "ADULTO_MAYOR";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CODIGO_INACTIVO = "INACTIVO";

    private static final String ACCION_CREAR = AccionesPlanificacion.CREAR_PLAN;
    private static final String ACCION_ACTUALIZAR = AccionesPlanificacion.ACTUALIZAR_PLAN;
    private static final String ACCION_DESACTIVAR = AccionesPlanificacion.DESACTIVAR_PLAN;
    private static final String ACCION_REACTIVAR = AccionesPlanificacion.REACTIVAR_PLAN;
    private static final String ACCION_ELIMINAR = AccionesPlanificacion.ELIMINAR_PLAN;

    static final String MENSAJE_NO_EXISTE = "El plan no existe";

    private static final String MENSAJE_VIGENCIA_INVERTIDA =
            "La vigencia del plan no es válida: la fecha de término es anterior a la de inicio";
    private static final String MENSAJE_DETALLES_FUERA_DE_VIGENCIA =
            "Hay medicamentos del plan con vigencia fuera de la nueva vigencia del plan";
    static final String MENSAJE_INACTIVO_NO_EDITABLE = "El plan está inactivo; reactívalo para modificarlo";
    private static final String MENSAJE_YA_INACTIVO = "El plan ya está inactivo";
    private static final String MENSAJE_YA_ACTIVO = "El plan ya está activo";
    private static final String MENSAJE_TIENE_REFERENCIAS =
            "El plan tiene historial o referencias; desactívalo en su lugar";

    private final RepositorioPlanMedicamentos repositorioPlan;
    private final RepositorioDetallePlan repositorioDetalle;
    private final RepositorioHistorialPlan repositorioHistorial;
    private final RepositorioAdultoMayor repositorioAdulto;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final CatalogoDiasSemana catalogoDias;
    private final ApplicationEventPublisher publicadorEventos;
    private final Clock reloj;

    public ServicioPlanes(RepositorioPlanMedicamentos repositorioPlan, RepositorioDetallePlan repositorioDetalle,
            RepositorioHistorialPlan repositorioHistorial, RepositorioAdultoMayor repositorioAdulto,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, CatalogoDiasSemana catalogoDias,
            ApplicationEventPublisher publicadorEventos, Clock reloj) {
        this.repositorioPlan = repositorioPlan;
        this.repositorioDetalle = repositorioDetalle;
        this.repositorioHistorial = repositorioHistorial;
        this.repositorioAdulto = repositorioAdulto;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.catalogoDias = catalogoDias;
        this.publicadorEventos = publicadorEventos;
        this.reloj = reloj;
    }

    /**
     * Planes del grupo, opcionalmente filtrados por adulto mayor. Por
     * defecto solo los {@code ACTIVO}; con {@code incluirInactivos}, todos,
     * con los activos primero y luego por fecha de creación descendente.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    @Transactional(readOnly = true)
    public List<RespuestaPlan> listar(long idGrupo, Long idAdulto, boolean incluirInactivos) {
        servicioAutorizacion.exigirMiembro(idGrupo);

        List<PlanMedicamentos> planes = idAdulto == null
                ? repositorioPlan.findByIdGrupoOrderByFechaCreacionDescIdPlanDesc(idGrupo)
                : repositorioPlan.findByIdGrupoAndIdAdultoMayorOrderByFechaCreacionDescIdPlanDesc(idGrupo, idAdulto);

        long idEstadoActivo = idEstadoActivoPlan();
        List<PlanMedicamentos> filtrados = incluirInactivos
                ? ordenarActivosPrimero(planes, idEstadoActivo, PlanMedicamentos::getIdEstado)
                : planes.stream().filter(plan -> plan.getIdEstado() == idEstadoActivo).toList();

        Map<Long, String> nombresAdulto = nombresAdultoPorId(
                filtrados.stream().map(PlanMedicamentos::getIdAdultoMayor).distinct().toList());
        Map<Long, Long> medicamentosActivos = medicamentosActivosPorPlan(
                filtrados.stream().map(PlanMedicamentos::getIdPlan).toList());

        return filtrados.stream()
                .map(plan -> RespuestaPlan.desde(plan, nombresAdulto.get(plan.getIdAdultoMayor()),
                        medicamentosActivos.getOrDefault(plan.getIdPlan(), 0L)))
                .toList();
    }

    /**
     * El plan con todos sus detalles, activos primero.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPlanDetallado obtener(long idGrupo, long idPlan) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        PlanMedicamentos plan = buscarPlan(idGrupo, idPlan);

        long idEstadoActivoDetalle = idEstadoActivoDetalle();
        List<DetallePlan> detalles = ordenarActivosPrimero(
                repositorioDetalle.findByIdPlanOrderByIdDetallePlan(idPlan), idEstadoActivoDetalle,
                DetallePlan::getIdEstado);
        // (ver #ordenarActivosPrimero: comparador estable, conserva el orden secundario de la consulta)

        String nombreAdulto = nombreAdulto(plan.getIdAdultoMayor());
        long medicamentosActivos = detalles.stream().filter(d -> d.getIdEstado() == idEstadoActivoDetalle).count();

        RespuestaPlan respuestaPlan = RespuestaPlan.desde(plan, nombreAdulto, medicamentosActivos);
        List<RespuestaDetallePlan> respuestaDetalles = detalles.stream()
                .map(detalle -> RespuestaDetallePlan.desde(detalle, catalogoDias))
                .toList();
        return new RespuestaPlanDetallado(respuestaPlan, respuestaDetalles);
    }

    /**
     * Crea el plan, {@code ACTIVO}. El adulto mayor se resuelve (y se
     * bloquea con {@code SELECT ... FOR UPDATE}, como en {@code
     * ServicioContactos}, para serializar contra su desactivación
     * concurrente) ANTES de la regla de vigencia: un adulto ajeno responde
     * 404 aunque la vigencia también sea inválida.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto mayor no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el adulto mayor está inactivo, o la vigencia es inválida.
     */
    @Transactional
    public RespuestaPlan crear(long idGrupo, SolicitudCrearPlan solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);

        AdultoMayor adulto = bloquearAdultoActivoDelGrupo(idGrupo, solicitud.idAdultoMayor());
        validarVigencia(solicitud.vigenteDesde(), solicitud.vigenteHasta());

        PlanMedicamentos plan = new PlanMedicamentos(idGrupo, solicitud.idAdultoMayor(), solicitud.nombrePlan(),
                solicitud.vigenteDesde(), solicitud.vigenteHasta(), contexto.usuario().getIdUsuario(),
                idEstadoActivoPlan(), OffsetDateTime.now(reloj));
        PlanMedicamentos guardado = repositorioPlan.save(plan);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdPlan()), idGrupo, contexto.usuario().getIdUsuario(), ACCION_CREAR,
                ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return RespuestaPlan.desde(guardado, adulto.getNombreCompleto(), 0L);
    }

    /**
     * Actualiza nombre y vigencia del plan. Si, tras recortar el nombre,
     * nada cambia, no guarda ni audita (igual que {@code
     * ServicioAdultosMayores}).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el plan está inactivo, la vigencia es
     *                                 inválida, o (solo si la vigencia
     *                                 cambia) deja algún detalle ACTIVO fuera
     *                                 de la nueva vigencia.
     */
    @Transactional
    public RespuestaPlan actualizar(long idGrupo, long idPlan, SolicitudActualizarPlan solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);

        if (plan.getIdEstado() != idEstadoActivoPlan()) {
            throw new ExcepcionReglaNegocio(MENSAJE_INACTIVO_NO_EDITABLE);
        }
        validarVigencia(solicitud.vigenteDesde(), solicitud.vigenteHasta());

        List<String> camposModificados = detectarCambios(plan, solicitud);
        if (camposModificados.isEmpty()) {
            return RespuestaPlan.desde(plan, nombreAdulto(plan.getIdAdultoMayor()), medicamentosActivos(idPlan));
        }

        // Solo si cambió la vigencia: renombrar no revisa los detalles (uno
        // que ya estuviera fuera no debe impedir corregir el nombre).
        boolean cambiaVigencia = camposModificados.contains("vigenteDesde")
                || camposModificados.contains("vigenteHasta");
        if (cambiaVigencia) {
            validarDetallesDentroDeVigencia(idPlan, solicitud.vigenteDesde(), solicitud.vigenteHasta());
        }

        plan.actualizar(solicitud.nombrePlan(), solicitud.vigenteDesde(), solicitud.vigenteHasta(),
                contexto.usuario().getIdUsuario(), OffsetDateTime.now(reloj));
        PlanMedicamentos guardado = repositorioPlan.save(plan);
        if (cambiaVigencia) {
            publicarCambio(contexto, idGrupo, idPlan);
        }

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdPlan()), idGrupo, contexto.usuario().getIdUsuario(), ACCION_ACTUALIZAR,
                ResultadoAuditoria.EXITO, detalles(contexto, Map.of("camposModificados", camposModificados))));

        return RespuestaPlan.desde(guardado, nombreAdulto(guardado.getIdAdultoMayor()), medicamentosActivos(idPlan));
    }

    /**
     * Desactivación lógica: no toca los detalles del plan.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      el plan ya está inactivo.
     */
    @Transactional
    public RespuestaPlan desactivar(long idGrupo, long idPlan) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);

        long idEstadoInactivo = idEstadoInactivoPlan();
        if (plan.getIdEstado() == idEstadoInactivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_INACTIVO);
        }

        plan.cambiarEstado(idEstadoInactivo, contexto.usuario().getIdUsuario(), OffsetDateTime.now(reloj));
        PlanMedicamentos guardado = repositorioPlan.save(plan);
        publicarCambio(contexto, idGrupo, idPlan);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdPlan()), idGrupo, contexto.usuario().getIdUsuario(), ACCION_DESACTIVAR,
                ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return RespuestaPlan.desde(guardado, nombreAdulto(guardado.getIdAdultoMayor()), medicamentosActivos(idPlan));
    }

    /**
     * Reactiva el plan.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      el plan ya está activo.
     */
    @Transactional
    public RespuestaPlan reactivar(long idGrupo, long idPlan) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);

        long idEstadoActivo = idEstadoActivoPlan();
        if (plan.getIdEstado() == idEstadoActivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_ACTIVO);
        }

        plan.cambiarEstado(idEstadoActivo, contexto.usuario().getIdUsuario(), OffsetDateTime.now(reloj));
        PlanMedicamentos guardado = repositorioPlan.save(plan);
        publicarCambio(contexto, idGrupo, idPlan);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdPlan()), idGrupo, contexto.usuario().getIdUsuario(), ACCION_REACTIVAR,
                ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return RespuestaPlan.desde(guardado, nombreAdulto(guardado.getIdAdultoMayor()), medicamentosActivos(idPlan));
    }

    /**
     * Elimina físicamente el plan y sus detalles (y las filas de días/horas
     * de cada detalle, vía borrado de entidades JPA), solo si no tiene
     * historial ni otras referencias ({@link RepositorioHistorialPlan#planTieneReferencias}).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el plan tiene historial o referencias.
     */
    @Transactional
    public void eliminar(long idGrupo, long idPlan) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);

        if (repositorioHistorial.planTieneReferencias(idPlan)) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIENE_REFERENCIAS);
        }

        // Borrado de entidades (nunca JPQL/JDBC masivo): DETALLE_PLAN_DIA y
        // DETALLE_PLAN_HORARIO no tienen ON DELETE CASCADE, así que Hibernate
        // debe borrarlas gestionando cada DetallePlan como entidad.
        List<DetallePlan> detalles = repositorioDetalle.findByIdPlanOrderByIdDetallePlan(idPlan);
        repositorioDetalle.deleteAll(detalles);
        repositorioPlan.delete(plan);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(idPlan), idGrupo,
                contexto.usuario().getIdUsuario(), ACCION_ELIMINAR, ResultadoAuditoria.EXITO,
                detalles(contexto, null)));
    }

    /**
     * Recálculo síncrono de las tareas de todos los detalles del plan (F4,
     * regla 8): el oyente corre en esta transacción, con el plan ya bloqueado.
     */
    private void publicarCambio(ContextoGrupo contexto, long idGrupo, long idPlan) {
        publicadorEventos.publishEvent(new EventoCambioPlanificacion(idGrupo, idPlan, List.of(),
                contexto.usuario().getIdUsuario(), DetallesAuditoria.esAccesoAdministrador(contexto)));
    }

    /** @throws ExcepcionNoEncontrado el plan no existe o no pertenece al grupo. */
    private PlanMedicamentos buscarPlan(long idGrupo, long idPlan) {
        PlanMedicamentos plan = repositorioPlan.findById(idPlan)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (plan.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return plan;
    }

    /** @throws ExcepcionNoEncontrado el plan no existe o no pertenece al grupo. */
    private PlanMedicamentos bloquearPlan(long idGrupo, long idPlan) {
        PlanMedicamentos plan = repositorioPlan.bloquearPorId(idPlan)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (plan.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return plan;
    }

    /**
     * @throws ExcepcionNoEncontrado el adulto mayor no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio el adulto mayor está inactivo.
     */
    private AdultoMayor bloquearAdultoActivoDelGrupo(long idGrupo, long idAdultoMayor) {
        AdultoMayor adulto = repositorioAdulto.bloquearPorId(idAdultoMayor)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
        if (adulto.getIdEstado() != idEstadoActivoAdulto()) {
            throw new ExcepcionReglaNegocio(ServicioAdultosMayores.MENSAJE_INACTIVO_NO_EDITABLE);
        }
        return adulto;
    }

    /** @throws ExcepcionReglaNegocio {@code vigenteHasta} es anterior a {@code vigenteDesde}. */
    private void validarVigencia(LocalDate vigenteDesde, LocalDate vigenteHasta) {
        if (vigenteHasta != null && vigenteHasta.isBefore(vigenteDesde)) {
            throw new ExcepcionReglaNegocio(MENSAJE_VIGENCIA_INVERTIDA);
        }
    }

    /**
     * @throws ExcepcionReglaNegocio algún detalle ACTIVO del plan queda fuera
     *                               de la nueva vigencia ({@code fechaInicio}
     *                               anterior a {@code vigenteDesde}, o {@code
     *                               vigenteHasta} no nula y {@code fechaFin}
     *                               nula o posterior a {@code vigenteHasta}).
     */
    private void validarDetallesDentroDeVigencia(long idPlan, LocalDate vigenteDesde, LocalDate vigenteHasta) {
        long idEstadoActivoDetalle = idEstadoActivoDetalle();
        boolean algunoFuera = repositorioDetalle.findByIdPlanOrderByIdDetallePlan(idPlan).stream()
                .filter(detalle -> detalle.getIdEstado() == idEstadoActivoDetalle)
                .anyMatch(detalle -> detalle.getFechaInicio().isBefore(vigenteDesde)
                        || (vigenteHasta != null
                                && (detalle.getFechaFin() == null || detalle.getFechaFin().isAfter(vigenteHasta))));
        if (algunoFuera) {
            throw new ExcepcionReglaNegocio(MENSAJE_DETALLES_FUERA_DE_VIGENCIA);
        }
    }

    /** Campos del plan cuyo valor cambia respecto del actual. */
    private List<String> detectarCambios(PlanMedicamentos plan, SolicitudActualizarPlan solicitud) {
        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(plan.getNombrePlan(), solicitud.nombrePlan())) {
            cambios.add("nombrePlan");
        }
        if (!Objects.equals(plan.getVigenteDesde(), solicitud.vigenteDesde())) {
            cambios.add("vigenteDesde");
        }
        if (!Objects.equals(plan.getVigenteHasta(), solicitud.vigenteHasta())) {
            cambios.add("vigenteHasta");
        }
        return cambios;
    }

    /**
     * Agrega {@code "accesoAdministrador": true} a {@code extra} si el actor
     * mutó como administrador sin membresía en el grupo. Delega en {@link
     * DetallesAuditoria}, compartido con {@code adultos}, para no duplicar
     * la lógica.
     */
    private Map<String, Object> detalles(ContextoGrupo contexto, Map<String, Object> extra) {
        return DetallesAuditoria.conTrazabilidad(contexto, extra);
    }

    /**
     * Reordena {@code elementos} dejando primero los que están en {@code
     * idEstadoActivo} (según {@code idEstadoDe}), conservando entre ellos el
     * orden relativo original ({@code Comparator.comparing} con {@code
     * Stream.sorted} es estable): así los activos quedan ordenados también
     * por el criterio secundario de la consulta (p. ej. fecha de creación
     * descendente).
     */
    private <T> List<T> ordenarActivosPrimero(List<T> elementos, long idEstadoActivo, Function<T, Long> idEstadoDe) {
        return elementos.stream()
                .sorted(Comparator.comparing(elemento -> idEstadoDe.apply(elemento) != idEstadoActivo))
                .toList();
    }

    /** {@code nombreCompleto} de los adultos indicados, por {@code idAdultoMayor}, en una sola consulta. */
    private Map<Long, String> nombresAdultoPorId(List<Long> idsAdultos) {
        if (idsAdultos.isEmpty()) {
            return Map.of();
        }
        return repositorioAdulto.findAllById(idsAdultos).stream()
                .collect(Collectors.toMap(AdultoMayor::getIdAdultoMayor, AdultoMayor::getNombreCompleto));
    }

    private String nombreAdulto(long idAdulto) {
        return nombresAdultoPorId(List.of(idAdulto)).get(idAdulto);
    }

    /** Cantidad de detalles ACTIVO de cada plan indicado, por {@code idPlan}, en una sola consulta agrupada. */
    private Map<Long, Long> medicamentosActivosPorPlan(List<Long> idsPlan) {
        if (idsPlan.isEmpty()) {
            return Map.of();
        }
        return repositorioDetalle.contarActivosPorPlan(idsPlan, idEstadoActivoDetalle()).stream()
                .collect(Collectors.toMap(RepositorioDetallePlan.ConteoDetallesActivos::getIdPlan,
                        RepositorioDetallePlan.ConteoDetallesActivos::getCantidad));
    }

    /**
     * Cantidad de detalles ACTIVO de un único plan (respuesta de una
     * mutación puntual): usa {@code countByIdPlanAndIdEstado} directo, no la
     * consulta agrupada de {@link #medicamentosActivosPorPlan} (pensada para
     * no incurrir en N+1 al listar varios planes a la vez).
     */
    private long medicamentosActivos(long idPlan) {
        return repositorioDetalle.countByIdPlanAndIdEstado(idPlan, idEstadoActivoDetalle());
    }

    private long idEstadoActivoPlan() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_PLAN, CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoInactivoPlan() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_PLAN, CAMPO_ESTADO, CODIGO_INACTIVO);
    }

    private long idEstadoActivoDetalle() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_DETALLE, CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoActivoAdulto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_ADULTO, CAMPO_ESTADO, CODIGO_ACTIVO);
    }
}
