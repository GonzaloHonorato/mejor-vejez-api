package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.catalogos.TipoCatalogo;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Medicamentos de un plan ({@code DETALLE_PLAN}, HU-04): agregar, actualizar,
 * actualizar la cantidad disponible, desactivar, reactivar y eliminar. Todas
 * las operaciones son de coordinador (el administrador pasa sin membresía y
 * queda trazado con {@link DetallesAuditoria}).
 *
 * <p><b>Orden de cada mutación.</b> {@code exigirCoordinador(idGrupo)} →
 * {@code repositorioPlan.bloquearPorId(idPlan)} ({@code SELECT ... FOR
 * UPDATE}: serializa toda mutación de detalles del plan contra las del plan
 * mismo, incluida la revisión de historial de {@code ServicioPlanes#eliminar})
 * → validar que el plan sea del grupo (404) → plan {@code ACTIVO} (solo
 * {@code POST} de alta y los {@code PUT}) → detalle del plan (404) →
 * validaciones → guardar → {@code plan.tocar(...)} → auditar.
 *
 * <p><b>Horario.</b> {@link ValidadorHorario} valida el {@code HORARIO_JSON}
 * y lo expande; se guardan solo las horas ({@code DETALLE_PLAN_HORARIO}, una
 * fila por hora, D3).
 *
 * <p><b>Tareas (F4).</b> {@code crear}, {@code actualizar} (solo si guardó
 * cambios), {@code desactivar} y {@code reactivar} publican {@link
 * EventoCambioPlanificacion} después de guardar y antes de auditar: el
 * recálculo síncrono de F4 ({@code tareas.ServicioRecalculoTareas}, regla 8)
 * cancela las tareas futuras PENDIENTE que ya no corresponden, ajusta el
 * texto de las que siguen y genera las que faltan, en esta misma
 * transacción (si falla, el cambio se revierte). Las tareas pasadas, en
 * curso o finalizadas no se tocan. {@code actualizarCantidad} y {@code
 * eliminar} no publican.
 *
 * <p><b>Término.</b> En {@code AUTOMATICO} la fecha enviada se ignora y se
 * recalcula en cada alta, actualización y cambio de cantidad con {@link
 * CalculadoraTermino}, desde {@code max(hoy, fechaInicio)}, con {@code hoy =
 * LocalDate.now(reloj)}. En {@code MANUAL} la fecha es obligatoria.
 *
 * <p><b>Colecciones LAZY.</b> {@code spring.jpa.open-in-view=false}: la
 * respuesta se construye siempre dentro de la transacción del método.
 */
@Service
public class ServicioDetallesPlan {

    private static final String MODULO = "planificacion";
    private static final String TIPO_ENTIDAD = TiposEntidadPlanificacion.DETALLE_PLAN;
    private static final String TABLA_ESTADO_PLAN = "PLAN_MEDICAMENTOS";
    private static final String TABLA_ESTADO_DETALLE = "DETALLE_PLAN";
    private static final String TABLA_ESTADO_INTEGRANTE = "INTEGRANTE_GRUPO";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CODIGO_INACTIVO = "INACTIVO";
    private static final String MODO_AUTOMATICO = "AUTOMATICO";

    static final String MENSAJE_NO_EXISTE = "El medicamento del plan no existe";
    private static final String MENSAJE_SIN_DIAS = "Selecciona al menos un día";
    private static final String MENSAJE_DIAS_REPETIDOS = "Hay días repetidos";
    private static final String MENSAJE_DIA_NO_VALIDO =
            "La opción indicada no pertenece al catálogo " + TipoCatalogo.DIA_SEMANA;
    private static final String MENSAJE_VIGENCIA_INVALIDA = "La vigencia del medicamento no es válida";
    private static final String MENSAJE_FUERA_DEL_PLAN =
            "La vigencia del medicamento debe estar dentro de la vigencia del plan";
    private static final String MENSAJE_RESPONSABLE = "El responsable debe ser integrante activo del grupo";
    private static final String MENSAJE_MANUAL_SIN_FECHA =
            "Indica la fecha estimada de término o usa el cálculo automático";
    private static final String MENSAJE_DETALLE_INACTIVO_NO_EDITABLE =
            "El medicamento del plan está inactivo; reactívalo para modificarlo";
    private static final String MENSAJE_YA_INACTIVO = "El medicamento del plan ya está inactivo";
    private static final String MENSAJE_YA_ACTIVO = "El medicamento del plan ya está activo";
    private static final String MENSAJE_TIENE_REFERENCIAS =
            "El medicamento del plan tiene historial o referencias; desactívalo en su lugar";

    private final RepositorioPlanMedicamentos repositorioPlan;
    private final RepositorioDetallePlan repositorioDetalle;
    private final RepositorioHistorialPlan repositorioHistorial;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final ServicioMedicamentos servicioMedicamentos;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final ValidadorHorario validadorHorario;
    private final CalculadoraTermino calculadoraTermino;
    private final CatalogoDiasSemana catalogoDias;
    private final ApplicationEventPublisher publicadorEventos;
    private final Clock reloj;

    public ServicioDetallesPlan(RepositorioPlanMedicamentos repositorioPlan, RepositorioDetallePlan repositorioDetalle,
            RepositorioHistorialPlan repositorioHistorial, RepositorioIntegranteGrupo repositorioIntegrante,
            ServicioMedicamentos servicioMedicamentos, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, ServicioAuditoria servicioAuditoria,
            ValidadorHorario validadorHorario, CalculadoraTermino calculadoraTermino,
            CatalogoDiasSemana catalogoDias, ApplicationEventPublisher publicadorEventos, Clock reloj) {
        this.repositorioPlan = repositorioPlan;
        this.repositorioDetalle = repositorioDetalle;
        this.repositorioHistorial = repositorioHistorial;
        this.repositorioIntegrante = repositorioIntegrante;
        this.servicioMedicamentos = servicioMedicamentos;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.validadorHorario = validadorHorario;
        this.calculadoraTermino = calculadoraTermino;
        this.catalogoDias = catalogoDias;
        this.publicadorEventos = publicadorEventos;
        this.reloj = reloj;
    }

    /**
     * Agrega un medicamento al plan, {@code ACTIVO}.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el plan está inactivo, o alguna regla del detalle no se cumple.
     */
    @Transactional
    public RespuestaDetallePlan crear(long idGrupo, long idPlan, SolicitudGuardarDetalle solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        exigirPlanActivo(plan);

        Medicamento medicamento = servicioMedicamentos.obtenerActivo(solicitud.idMedicamento());
        DatosValidados datos = validar(idGrupo, plan, solicitud);

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        DetallePlan detalle = new DetallePlan(plan, medicamento.getIdMedicamento(), medicamento.getNombre(),
                solicitud.idPresentacion(), solicitud.concentracion(), solicitud.instrucciones(),
                solicitud.dosisCantidad(), solicitud.idUnidadDosis(), solicitud.cantidadInicial(),
                solicitud.cantidadDisponible(), solicitud.modoCalculoTermino(), datos.fechaEstimadaTermino(),
                solicitud.fechaInicio(), solicitud.fechaFin(), solicitud.idResponsablePredeterminado(),
                idEstadoDetalle(CODIGO_ACTIVO), ahora);
        detalle.reemplazarDias(datos.dias());
        detalle.reemplazarHorario(datos.horas());
        DetallePlan guardado = repositorioDetalle.save(detalle);

        tocarPlan(plan, contexto, ahora);
        publicarCambio(contexto, idGrupo, idPlan, guardado.getIdDetallePlan());
        auditar(contexto, idGrupo, guardado.getIdDetallePlan(), idPlan, AccionesPlanificacion.AGREGAR_MEDICAMENTO_PLAN,
                null);

        return RespuestaDetallePlan.desde(guardado, catalogoDias);
    }

    /**
     * Reemplaza los datos del medicamento del plan. Si nada cambia (incluida
     * la fecha de término recalculada), no guarda ni audita.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no es del grupo, o el detalle no es del plan.
     * @throws ExcepcionReglaNegocio   el plan está inactivo, o alguna regla del detalle no se cumple.
     */
    @Transactional
    public RespuestaDetallePlan actualizar(long idGrupo, long idPlan, long idDetalle,
            SolicitudGuardarDetalle solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        exigirPlanActivo(plan);
        DetallePlan detalle = buscarDetalle(idPlan, idDetalle);
        exigirDetalleActivo(detalle);

        // El medicamento se exige activo solo si cambia: un detalle existente
        // sigue editable aunque su medicamento se haya dado de baja en el
        // catálogo global después de indicarse.
        boolean cambiaMedicamento = !Objects.equals(detalle.getIdMedicamento(), solicitud.idMedicamento());
        Medicamento medicamento = cambiaMedicamento ? servicioMedicamentos.obtenerActivo(solicitud.idMedicamento())
                : null;
        DatosValidados datos = validar(idGrupo, plan, solicitud);

        List<String> camposModificados = detectarCambios(detalle, solicitud, datos);
        if (camposModificados.isEmpty()) {
            return RespuestaDetallePlan.desde(detalle, catalogoDias);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (medicamento != null) {
            detalle.cambiarMedicamento(medicamento.getIdMedicamento(), medicamento.getNombre(), ahora);
        }
        detalle.actualizarDatos(solicitud.idPresentacion(), solicitud.concentracion(), solicitud.instrucciones(),
                solicitud.dosisCantidad(), solicitud.idUnidadDosis(), solicitud.idResponsablePredeterminado(),
                solicitud.fechaInicio(), solicitud.fechaFin(), ahora);
        detalle.actualizarCantidad(solicitud.cantidadInicial(), solicitud.cantidadDisponible(), ahora);
        detalle.actualizarTermino(solicitud.modoCalculoTermino(), datos.fechaEstimadaTermino(), ahora);
        if (camposModificados.contains("dias")) {
            detalle.reemplazarDias(datos.dias());
        }
        if (camposModificados.contains("horario")) {
            detalle.reemplazarHorario(datos.horas());
        }
        DetallePlan guardado = repositorioDetalle.save(detalle);

        tocarPlan(plan, contexto, ahora);
        publicarCambio(contexto, idGrupo, idPlan, idDetalle);
        auditar(contexto, idGrupo, idDetalle, idPlan, AccionesPlanificacion.ACTUALIZAR_MEDICAMENTO_PLAN,
                Map.of("camposModificados", camposModificados));

        return RespuestaDetallePlan.desde(guardado, catalogoDias);
    }

    /**
     * Cambia la cantidad disponible (no la inicial) y, en modo {@code
     * AUTOMATICO}, recalcula la fecha estimada de término. Si nada cambia,
     * no guarda ni audita.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el plan no existe o no es del grupo, o el detalle no es del plan.
     * @throws ExcepcionReglaNegocio   el plan está inactivo.
     */
    @Transactional
    public RespuestaDetallePlan actualizarCantidad(long idGrupo, long idPlan, long idDetalle,
            SolicitudActualizarCantidad solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        exigirPlanActivo(plan);
        DetallePlan detalle = buscarDetalle(idPlan, idDetalle);
        exigirDetalleActivo(detalle);

        BigDecimal cantidadDisponible = solicitud.cantidadDisponible();
        LocalDate fechaEstimada = MODO_AUTOMATICO.equals(detalle.getModoCalculoTermino())
                ? estimarTermino(cantidadDisponible, detalle.getDosisCantidad(), detalle.horas().size(),
                        detalle.diasSemana(), detalle.getFechaInicio(), detalle.getFechaFin())
                : detalle.getFechaEstimadaTermino();

        boolean cambiaCantidad = !mismoDecimal(detalle.getCantidadDisponible(), cantidadDisponible);
        boolean cambiaFecha = !Objects.equals(detalle.getFechaEstimadaTermino(), fechaEstimada);
        if (!cambiaCantidad && !cambiaFecha) {
            return RespuestaDetallePlan.desde(detalle, catalogoDias);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        detalle.actualizarCantidad(detalle.getCantidadInicial(), cantidadDisponible, ahora);
        detalle.actualizarTermino(detalle.getModoCalculoTermino(), fechaEstimada, ahora);
        DetallePlan guardado = repositorioDetalle.save(detalle);

        tocarPlan(plan, contexto, ahora);
        auditar(contexto, idGrupo, idDetalle, idPlan, AccionesPlanificacion.ACTUALIZAR_CANTIDAD_DISPONIBLE, null);

        return RespuestaDetallePlan.desde(guardado, catalogoDias);
    }

    /**
     * Desactivación lógica del detalle: no toca el plan (salvo su fecha de
     * actualización) ni los demás detalles.
     *
     * @throws ExcepcionConflicto el detalle ya está inactivo.
     */
    @Transactional
    public RespuestaDetallePlan desactivar(long idGrupo, long idPlan, long idDetalle) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        DetallePlan detalle = buscarDetalle(idPlan, idDetalle);

        long idEstadoInactivo = idEstadoDetalle(CODIGO_INACTIVO);
        if (detalle.getIdEstado() == idEstadoInactivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_INACTIVO);
        }

        return cambiarEstado(contexto, idGrupo, plan, detalle, idEstadoInactivo,
                AccionesPlanificacion.DESACTIVAR_MEDICAMENTO_PLAN);
    }

    /**
     * Reactiva el detalle, solo si su vigencia cabe en la vigencia ACTUAL del
     * plan (pudo acotarse mientras el detalle estaba inactivo). En modo
     * {@code AUTOMATICO} recalcula la fecha estimada de término.
     *
     * @throws ExcepcionConflicto    el detalle ya está activo.
     * @throws ExcepcionReglaNegocio su vigencia quedó fuera de la del plan.
     */
    @Transactional
    public RespuestaDetallePlan reactivar(long idGrupo, long idPlan, long idDetalle) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        DetallePlan detalle = buscarDetalle(idPlan, idDetalle);

        long idEstadoActivo = idEstadoDetalle(CODIGO_ACTIVO);
        if (detalle.getIdEstado() == idEstadoActivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_ACTIVO);
        }
        validarDentroDelPlan(plan, detalle.getFechaInicio(), detalle.getFechaFin());

        // En AUTOMATICO, "hoy" pudo avanzar mientras el detalle estuvo
        // inactivo: la fecha estimada se recalcula al reactivarlo.
        if (MODO_AUTOMATICO.equals(detalle.getModoCalculoTermino())) {
            detalle.actualizarTermino(MODO_AUTOMATICO, estimarTermino(detalle.getCantidadDisponible(),
                    detalle.getDosisCantidad(), detalle.horas().size(), detalle.diasSemana(),
                    detalle.getFechaInicio(), detalle.getFechaFin()), OffsetDateTime.now(reloj));
        }

        return cambiarEstado(contexto, idGrupo, plan, detalle, idEstadoActivo,
                AccionesPlanificacion.REACTIVAR_MEDICAMENTO_PLAN);
    }

    /**
     * Elimina físicamente el detalle (y sus filas de días y horas, vía
     * borrado de la entidad JPA), solo si no tiene historial ni referencias
     * ({@link RepositorioHistorialPlan#detalleTieneReferencias}).
     *
     * @throws ExcepcionReglaNegocio el detalle tiene historial o referencias.
     */
    @Transactional
    public void eliminar(long idGrupo, long idPlan, long idDetalle) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        PlanMedicamentos plan = bloquearPlan(idGrupo, idPlan);
        DetallePlan detalle = buscarDetalle(idPlan, idDetalle);

        if (repositorioHistorial.detalleTieneReferencias(idDetalle)) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIENE_REFERENCIAS);
        }

        repositorioDetalle.delete(detalle);
        tocarPlan(plan, contexto, OffsetDateTime.now(reloj));
        auditar(contexto, idGrupo, idDetalle, idPlan, AccionesPlanificacion.ELIMINAR_MEDICAMENTO_PLAN, null);
    }

    private RespuestaDetallePlan cambiarEstado(ContextoGrupo contexto, long idGrupo, PlanMedicamentos plan,
            DetallePlan detalle, long idEstado, String accion) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        detalle.cambiarEstado(idEstado, ahora);
        DetallePlan guardado = repositorioDetalle.save(detalle);

        tocarPlan(plan, contexto, ahora);
        publicarCambio(contexto, idGrupo, plan.getIdPlan(), guardado.getIdDetallePlan());
        auditar(contexto, idGrupo, guardado.getIdDetallePlan(), plan.getIdPlan(), accion, null);

        return RespuestaDetallePlan.desde(guardado, catalogoDias);
    }

    // ------------------------------------------------------------------
    // Validaciones
    // ------------------------------------------------------------------

    /** Resultado de {@link #validar}: días, horas expandidas y fecha de término a guardar. */
    private record DatosValidados(Set<Long> dias, List<LocalTime> horas, LocalDate fechaEstimadaTermino) {
    }

    /**
     * Reglas del detalle, en el orden del brief: opciones de catálogo, días,
     * horario, vigencia, responsable y término. (El medicamento lo valida el
     * llamador antes, con {@code obtenerActivo}.)
     */
    private DatosValidados validar(long idGrupo, PlanMedicamentos plan, SolicitudGuardarDetalle solicitud) {
        servicioCatalogos.validarOpcion(solicitud.idPresentacion(), TipoCatalogo.PRESENTACION_MEDICAMENTO);
        servicioCatalogos.validarOpcion(solicitud.idUnidadDosis(), TipoCatalogo.UNIDAD_DOSIS);
        Set<Long> dias = validarDias(solicitud.diasSemana());
        List<LocalTime> horas = validadorHorario.validarYExpandir(solicitud.horario());
        validarVigencia(plan, solicitud.fechaInicio(), solicitud.fechaFin());
        validarResponsable(idGrupo, solicitud.idResponsablePredeterminado());
        LocalDate fechaEstimada = resolverTermino(solicitud, horas.size(), dias);
        return new DatosValidados(dias, horas, fechaEstimada);
    }

    /** @throws ExcepcionReglaNegocio sin días, días repetidos, o algún id que no es un día activo de {@code DIA_SEMANA}. */
    private Set<Long> validarDias(List<Long> idsDia) {
        if (idsDia.isEmpty()) {
            throw new ExcepcionReglaNegocio(MENSAJE_SIN_DIAS);
        }
        Set<Long> dias = new HashSet<>(idsDia);
        if (dias.size() != idsDia.size()) {
            throw new ExcepcionReglaNegocio(MENSAJE_DIAS_REPETIDOS);
        }
        for (Long idDia : dias) {
            servicioCatalogos.validarOpcion(idDia, TipoCatalogo.DIA_SEMANA);
            if (!catalogoDias.esDiaActivo(idDia)) {
                throw new ExcepcionReglaNegocio(MENSAJE_DIA_NO_VALIDO);
            }
        }
        return dias;
    }

    /** @throws ExcepcionReglaNegocio vigencia invertida, o fuera de la vigencia del plan. */
    private void validarVigencia(PlanMedicamentos plan, LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaFin != null && fechaFin.isBefore(fechaInicio)) {
            throw new ExcepcionReglaNegocio(MENSAJE_VIGENCIA_INVALIDA);
        }
        validarDentroDelPlan(plan, fechaInicio, fechaFin);
    }

    /**
     * Misma regla que {@code ServicioPlanes} usa al acotar el plan: el
     * detalle no empieza antes que el plan, y si el plan tiene término, el
     * detalle también lo tiene y no lo excede (una {@code fechaFin} nula
     * nunca cabe en un plan acotado).
     *
     * @throws ExcepcionReglaNegocio el detalle queda fuera de la vigencia del plan.
     */
    private void validarDentroDelPlan(PlanMedicamentos plan, LocalDate fechaInicio, LocalDate fechaFin) {
        boolean empiezaAntes = fechaInicio.isBefore(plan.getVigenteDesde());
        boolean terminaDespues = plan.getVigenteHasta() != null
                && (fechaFin == null || fechaFin.isAfter(plan.getVigenteHasta()));
        if (empiezaAntes || terminaDespues) {
            throw new ExcepcionReglaNegocio(MENSAJE_FUERA_DEL_PLAN);
        }
    }

    /** @throws ExcepcionReglaNegocio el responsable no es integrante ACTIVO del grupo. */
    private void validarResponsable(long idGrupo, Long idResponsable) {
        if (idResponsable == null) {
            return;
        }
        long idEstadoActivo = servicioCatalogos.idEstado(TABLA_ESTADO_INTEGRANTE, CAMPO_ESTADO, CODIGO_ACTIVO);
        boolean esIntegranteActivo = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idResponsable)
                .filter(integrante -> integrante.getIdEstado().longValue() == idEstadoActivo)
                .isPresent();
        if (!esIntegranteActivo) {
            throw new ExcepcionReglaNegocio(MENSAJE_RESPONSABLE);
        }
    }

    /**
     * {@code MANUAL}: la fecha enviada (obligatoria). {@code AUTOMATICO}: la
     * calculada (la enviada se ignora).
     *
     * @throws ExcepcionReglaNegocio modo {@code MANUAL} sin fecha.
     */
    private LocalDate resolverTermino(SolicitudGuardarDetalle solicitud, int dosisPorDia, Set<Long> dias) {
        if (!MODO_AUTOMATICO.equals(solicitud.modoCalculoTermino())) {
            if (solicitud.fechaEstimadaTermino() == null) {
                throw new ExcepcionReglaNegocio(MENSAJE_MANUAL_SIN_FECHA);
            }
            return solicitud.fechaEstimadaTermino();
        }
        return estimarTermino(solicitud.cantidadDisponible(), solicitud.dosisCantidad(), dosisPorDia, dias,
                solicitud.fechaInicio(), solicitud.fechaFin());
    }

    private LocalDate estimarTermino(BigDecimal cantidadDisponible, BigDecimal dosisCantidad, int dosisPorDia,
            Set<Long> dias, LocalDate fechaInicio, LocalDate fechaFin) {
        LocalDate hoy = LocalDate.now(reloj);
        LocalDate desde = fechaInicio.isAfter(hoy) ? fechaInicio : hoy;
        // Un día guardado que ya no está activo en el catálogo se ignora (nunca un 500);
        // si no queda ninguno, la calculadora devuelve null.
        Set<DayOfWeek> diasDeSemana = catalogoDias.diasDeSemanaConocidos(dias);
        return calculadoraTermino.estimar(cantidadDisponible, dosisCantidad, dosisPorDia, diasDeSemana, desde,
                fechaFin);
    }

    /**
     * Campos cuyo valor cambia respecto del detalle actual, con los nombres
     * de la solicitud ({@code "dias"} y {@code "horario"} para las
     * colecciones). Los decimales se comparan por valor ({@code compareTo}),
     * no por escala: {@code 1} y {@code 1.000} (como vuelve de la BD) son
     * iguales.
     */
    private List<String> detectarCambios(DetallePlan detalle, SolicitudGuardarDetalle solicitud,
            DatosValidados datos) {
        List<String> cambios = new ArrayList<>();
        agregarSi(cambios, "idMedicamento", !Objects.equals(detalle.getIdMedicamento(), solicitud.idMedicamento()));
        agregarSi(cambios, "idPresentacion",
                !Objects.equals(detalle.getIdPresentacion(), solicitud.idPresentacion()));
        agregarSi(cambios, "concentracion", !Objects.equals(detalle.getConcentracion(), solicitud.concentracion()));
        agregarSi(cambios, "instrucciones", !Objects.equals(detalle.getInstrucciones(), solicitud.instrucciones()));
        agregarSi(cambios, "dosisCantidad", !mismoDecimal(detalle.getDosisCantidad(), solicitud.dosisCantidad()));
        agregarSi(cambios, "idUnidadDosis", !Objects.equals(detalle.getIdUnidadDosis(), solicitud.idUnidadDosis()));
        agregarSi(cambios, "dias", !detalle.diasSemana().equals(datos.dias()));
        agregarSi(cambios, "horario", !detalle.horas().equals(datos.horas()));
        agregarSi(cambios, "fechaInicio", !Objects.equals(detalle.getFechaInicio(), solicitud.fechaInicio()));
        agregarSi(cambios, "fechaFin", !Objects.equals(detalle.getFechaFin(), solicitud.fechaFin()));
        agregarSi(cambios, "cantidadInicial",
                !mismoDecimal(detalle.getCantidadInicial(), solicitud.cantidadInicial()));
        agregarSi(cambios, "cantidadDisponible",
                !mismoDecimal(detalle.getCantidadDisponible(), solicitud.cantidadDisponible()));
        agregarSi(cambios, "modoCalculoTermino",
                !Objects.equals(detalle.getModoCalculoTermino(), solicitud.modoCalculoTermino()));
        agregarSi(cambios, "fechaEstimadaTermino",
                !Objects.equals(detalle.getFechaEstimadaTermino(), datos.fechaEstimadaTermino()));
        agregarSi(cambios, "idResponsablePredeterminado",
                !Objects.equals(detalle.getIdResponsablePredeterminado(), solicitud.idResponsablePredeterminado()));
        return cambios;
    }

    private static void agregarSi(List<String> cambios, String campo, boolean cambio) {
        if (cambio) {
            cambios.add(campo);
        }
    }

    private static boolean mismoDecimal(BigDecimal actual, BigDecimal nuevo) {
        if (actual == null || nuevo == null) {
            return actual == nuevo;
        }
        return actual.compareTo(nuevo) == 0;
    }

    // ------------------------------------------------------------------
    // Búsquedas, estado y auditoría
    // ------------------------------------------------------------------

    /** @throws ExcepcionNoEncontrado el plan no existe o no pertenece al grupo. */
    private PlanMedicamentos bloquearPlan(long idGrupo, long idPlan) {
        PlanMedicamentos plan = repositorioPlan.bloquearPorId(idPlan)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioPlanes.MENSAJE_NO_EXISTE));
        if (plan.getIdGrupo().longValue() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioPlanes.MENSAJE_NO_EXISTE);
        }
        return plan;
    }

    /** @throws ExcepcionReglaNegocio el plan está inactivo. */
    private void exigirPlanActivo(PlanMedicamentos plan) {
        long idEstadoActivo = servicioCatalogos.idEstado(TABLA_ESTADO_PLAN, CAMPO_ESTADO, CODIGO_ACTIVO);
        if (plan.getIdEstado().longValue() != idEstadoActivo) {
            throw new ExcepcionReglaNegocio(ServicioPlanes.MENSAJE_INACTIVO_NO_EDITABLE);
        }
    }

    /** @throws ExcepcionReglaNegocio el detalle está inactivo (se reactiva antes de modificarlo). */
    private void exigirDetalleActivo(DetallePlan detalle) {
        if (detalle.getIdEstado().longValue() != idEstadoDetalle(CODIGO_ACTIVO)) {
            throw new ExcepcionReglaNegocio(MENSAJE_DETALLE_INACTIVO_NO_EDITABLE);
        }
    }

    /** @throws ExcepcionNoEncontrado el detalle no existe o es de otro plan. */
    private DetallePlan buscarDetalle(long idPlan, long idDetalle) {
        DetallePlan detalle = repositorioDetalle.findById(idDetalle)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (detalle.getIdPlan().longValue() != idPlan) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return detalle;
    }

    /**
     * Recálculo síncrono de las tareas del detalle (F4, regla 8): el oyente
     * corre en esta transacción, con el plan ya bloqueado.
     */
    private void publicarCambio(ContextoGrupo contexto, long idGrupo, long idPlan, long idDetalle) {
        publicadorEventos.publishEvent(new EventoCambioPlanificacion(idGrupo, idPlan, List.of(idDetalle),
                contexto.usuario().getIdUsuario(), DetallesAuditoria.esAccesoAdministrador(contexto)));
    }

    private void tocarPlan(PlanMedicamentos plan, ContextoGrupo contexto, OffsetDateTime ahora) {
        plan.tocar(contexto.usuario().getIdUsuario(), ahora);
        repositorioPlan.save(plan);
    }

    /** Detalles de auditoría: {@code idPlan} (+ {@code extra}) y la trazabilidad de administrador. */
    private void auditar(ContextoGrupo contexto, long idGrupo, long idDetalle, long idPlan, String accion,
            Map<String, Object> extra) {
        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idPlan", idPlan);
        if (extra != null) {
            detalles.putAll(extra);
        }
        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(idDetalle), idGrupo,
                contexto.usuario().getIdUsuario(), accion, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    private long idEstadoDetalle(String codigo) {
        return servicioCatalogos.idEstado(TABLA_ESTADO_DETALLE, CAMPO_ESTADO, codigo);
    }
}
