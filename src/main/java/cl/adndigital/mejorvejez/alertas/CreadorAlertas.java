package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.TextoAcotado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.DetalleCitaMedica;
import cl.adndigital.mejorvejez.tareas.RepositorioDetalleCitaMedica;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;
import cl.adndigital.mejorvejez.tareas.VerificadorVigencia;

/**
 * Crea las alertas de cuidado, cada una en su propia transacción ({@code
 * REQUIRES_NEW}, regla 5 de la Fase 5): bloquea el origen, vuelve a
 * verificar las condiciones con el estado vigente y solo entonces inserta,
 * si la clave ({@code CLAVE_ALERTA}, regla 1) todavía no existe. Así, una
 * tarea confirmada entre la consulta de candidatas y la creación no genera
 * alerta, y dos corridas simultáneas crean una sola.
 *
 * <p>El atraso de una tarea (TSA-43, {@link #crearAtraso}) y las dos
 * preventivas de TSA-44: cita próxima ({@link #crearCitaProxima}) y
 * reposición de un medicamento ({@link #crearReposicion}). El detector y el
 * de citas solo bloquean la tarea; el de reposición bloquea el plan del
 * detalle (mismo orden de bloqueo de F4: adulto → plan → tarea, nunca al
 * revés).
 */
@Component
public class CreadorAlertas {

    /** Largo máximo de {@code MENSAJE_ALERTA}, en bytes UTF-8 ({@code VARCHAR2(1000)}, semántica BYTE). */
    private static final int LARGO_MAXIMO_MENSAJE = 1000;
    private static final String PREFIJO_ATRASO = "ATR-";
    private static final String PREFIJO_CITA = "CIT-";
    private static final String PREFIJO_REPOSICION = "REP-";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FORMATO_CLAVE_FECHA = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RepositorioTareaCuidado repositorioTareas;
    private final RepositorioAlertaCuidado repositorioAlertas;
    private final RepositorioAdultoMayor repositorioAdultos;
    private final RepositorioDetallePlan repositorioDetalles;
    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final RepositorioDetalleCitaMedica repositorioCitas;
    private final RepositorioConsultaAlertas consultaAlertas;
    private final VerificadorVigencia verificador;
    private final ServicioCatalogos servicioCatalogos;
    private final int horasRetrospectiva;

    CreadorAlertas(RepositorioTareaCuidado repositorioTareas, RepositorioAlertaCuidado repositorioAlertas,
            RepositorioAdultoMayor repositorioAdultos, RepositorioDetallePlan repositorioDetalles,
            RepositorioPlanMedicamentos repositorioPlanes, RepositorioDetalleCitaMedica repositorioCitas,
            RepositorioConsultaAlertas consultaAlertas, VerificadorVigencia verificador,
            ServicioCatalogos servicioCatalogos,
            @Value("${mejorvejez.alertas.horas-retrospectiva}") int horasRetrospectiva) {
        this.repositorioTareas = repositorioTareas;
        this.repositorioAlertas = repositorioAlertas;
        this.repositorioAdultos = repositorioAdultos;
        this.repositorioDetalles = repositorioDetalles;
        this.repositorioPlanes = repositorioPlanes;
        this.repositorioCitas = repositorioCitas;
        this.consultaAlertas = consultaAlertas;
        this.verificador = verificador;
        this.servicioCatalogos = servicioCatalogos;
        this.horasRetrospectiva = horasRetrospectiva;
    }

    /**
     * Crea la alerta {@code ATRASO_TAREA} de la tarea ({@code ATR-{idTarea}})
     * en su propia transacción. Bloquea la tarea ({@code SELECT ... FOR
     * UPDATE}) y recién entonces verifica la regla 2 con su estado vigente.
     *
     * @return el id de la alerta creada, o vacío si la tarea ya no
     *         corresponde (no existe, se confirmó, cambió de horario, se
     *         inactivó su adulto, grupo, plan o detalle...) o la alerta ya
     *         existía.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> crearAtraso(long idTarea, OffsetDateTime ahora) {
        TareaCuidado tarea = repositorioTareas.bloquearPorId(idTarea).orElse(null);
        if (tarea == null || !estaAtrasada(tarea, ahora)) {
            return Optional.empty();
        }
        String clave = claveAtraso(idTarea);
        if (repositorioAlertas.findByClaveAlerta(clave).isPresent()) {
            return Optional.empty();
        }
        AdultoMayor adulto = repositorioAdultos.findById(tarea.getIdAdultoMayor()).orElseThrow();
        AlertaCuidado alerta = AlertaCuidado.deTarea(clave, CodigosAlertas.TIPO_ATRASO_TAREA, idTarea,
                tarea.getFechaHorario(), tarea.getIdGrupo(), tarea.getIdAdultoMayor(),
                mensajeAtraso(tarea, adulto.getNombreCompleto()), idEstadoAbierta(), ahora);
        return Optional.of(repositorioAlertas.save(alerta).getIdAlerta());
    }

    /**
     * Clave de idempotencia del atraso de una tarea (regla 1): {@code
     * ATR-{idTarea}}. Pública: Fase 9 (TSA-61), {@code
     * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} la usa para
     * saber si {@code POST /api/interno/alertas} ya había creado la alerta
     * cuando {@link #crearAtraso} devuelve vacío.
     */
    public static String claveAtraso(long idTarea) {
        return PREFIJO_ATRASO + idTarea;
    }

    /**
     * Regla 2 de la Fase 5 sobre la tarea ya bloqueada: PENDIENTE o EN_CURSO,
     * {@code GENERA_ALERTA}, dentro de la retrospectiva, pasado su límite y
     * con todo su contexto (adulto, grupo y, si es de MEDICAMENTOS, detalle y
     * plan) ACTIVO. El adulto, el grupo, el detalle y el plan solo se leen,
     * no se bloquean.
     */
    private boolean estaAtrasada(TareaCuidado tarea, OffsetDateTime ahora) {
        long idEstado = tarea.getIdEstado();
        boolean abierta = idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE)
                || idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO);
        OffsetDateTime horario = tarea.getFechaHorario();
        OffsetDateTime limite = horario.plusMinutes(tarea.getMinutosTolerancia());
        boolean enVentana = !horario.isBefore(ahora.minusHours(horasRetrospectiva)) && ahora.isAfter(limite);
        if (!abierta || !Boolean.TRUE.equals(tarea.getGeneraAlerta()) || !enVentana
                || !verificador.adultoYGrupoActivos(tarea.getIdAdultoMayor(), tarea.getIdGrupo())) {
            return false;
        }
        if (!CodigosTareas.TIPO_TAREA_MEDICAMENTOS.equals(tarea.getTipoTarea())) {
            return true;
        }
        Optional<DetallePlan> detalle = Optional.ofNullable(tarea.getIdDetallePlan())
                .flatMap(repositorioDetalles::findById);
        return detalle.filter(verificador::detalleActivo)
                .flatMap(d -> repositorioPlanes.findById(d.getIdPlan()))
                .filter(verificador::planAdultoYGrupoActivos)
                .isPresent();
    }

    /**
     * Regla 6: {@code «{tituloTarea}» de {nombreAdulto}, programada para el
     * {fecha} a las {hora}, sigue sin confirmación final.}, con la fecha y la
     * hora en Santiago, truncado a 1000 bytes UTF-8.
     */
    private static String mensajeAtraso(TareaCuidado tarea, String nombreAdulto) {
        OffsetDateTime horario = ZonaNegocio.enZona(tarea.getFechaHorario());
        String mensaje = "«" + tarea.getTituloTarea() + "» de " + nombreAdulto + ", programada para el "
                + FORMATO_FECHA.format(horario) + " a las " + FORMATO_HORA.format(horario)
                + ", sigue sin confirmación final.";
        return TextoAcotado.truncarABytes(mensaje, LARGO_MAXIMO_MENSAJE);
    }

    /**
     * Crea la alerta {@code CITA_PROXIMA} de la tarea ({@code
     * CIT-{idTarea}}) en su propia transacción. Bloquea la tarea y recién
     * entonces verifica la regla 3 con su estado vigente.
     *
     * @return el id de la alerta creada, o vacío si la tarea ya no
     *         corresponde o la alerta ya existía.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> crearCitaProxima(long idTarea, OffsetDateTime ahora) {
        TareaCuidado tarea = repositorioTareas.bloquearPorId(idTarea).orElse(null);
        if (tarea == null || !esCitaProxima(tarea, ahora)) {
            return Optional.empty();
        }
        String clave = claveCitaProxima(idTarea);
        if (repositorioAlertas.findByClaveAlerta(clave).isPresent()) {
            return Optional.empty();
        }
        AdultoMayor adulto = repositorioAdultos.findById(tarea.getIdAdultoMayor()).orElseThrow();
        DetalleCitaMedica detalleCita = repositorioCitas.findById(idTarea).orElse(null);
        AlertaCuidado alerta = AlertaCuidado.deTarea(clave, CodigosAlertas.TIPO_CITA_PROXIMA, idTarea,
                tarea.getFechaHorario(), tarea.getIdGrupo(), tarea.getIdAdultoMayor(),
                mensajeCitaProxima(tarea, adulto.getNombreCompleto(), detalleCita), idEstadoAbierta(), ahora);
        return Optional.of(repositorioAlertas.save(alerta).getIdAlerta());
    }

    /**
     * Clave de idempotencia de la cita próxima de una tarea (regla 1):
     * {@code CIT-{idTarea}}. Pública por el mismo motivo que {@link
     * #claveAtraso} (Fase 9, TSA-61).
     */
    public static String claveCitaProxima(long idTarea) {
        return PREFIJO_CITA + idTarea;
    }

    /**
     * Regla 3 de la Fase 5 sobre la tarea ya bloqueada: {@code
     * CITAS_CONTROLES_MEDICOS}, PENDIENTE o EN_CURSO, {@code GENERA_ALERTA},
     * con su adulto y su grupo ACTIVO, y {@code ahora < FECHA_HORARIO ≤
     * ahora + 24 h}.
     */
    private boolean esCitaProxima(TareaCuidado tarea, OffsetDateTime ahora) {
        if (!CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS.equals(tarea.getTipoTarea())) {
            return false;
        }
        long idEstado = tarea.getIdEstado();
        boolean abierta = idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE)
                || idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO);
        OffsetDateTime horario = tarea.getFechaHorario();
        boolean enVentana = horario.isAfter(ahora) && !horario.isAfter(ahora.plusHours(24));
        return abierta && Boolean.TRUE.equals(tarea.getGeneraAlerta()) && enVentana
                && verificador.adultoYGrupoActivos(tarea.getIdAdultoMayor(), tarea.getIdGrupo());
    }

    /**
     * Regla 6: {@code {nombreAdulto} tiene «{tituloTarea}» el {fecha} a las
     * {hora}.}, con la fecha y la hora en Santiago. Si la tarea tiene {@code
     * DETALLE_CITA_MEDICA}, se agrega {@code  Médico: {nombreMedico}.
     * Lugar: {lugarAtencion}.}. Truncado a 1000 bytes UTF-8.
     */
    private static String mensajeCitaProxima(TareaCuidado tarea, String nombreAdulto, DetalleCitaMedica detalleCita) {
        OffsetDateTime horario = ZonaNegocio.enZona(tarea.getFechaHorario());
        StringBuilder mensaje = new StringBuilder(nombreAdulto).append(" tiene «").append(tarea.getTituloTarea())
                .append("» el ").append(FORMATO_FECHA.format(horario)).append(" a las ")
                .append(FORMATO_HORA.format(horario)).append(".");
        if (detalleCita != null) {
            mensaje.append(" Médico: ").append(detalleCita.getNombreMedico()).append(". Lugar: ")
                    .append(detalleCita.getLugarAtencion()).append(".");
        }
        return TextoAcotado.truncarABytes(mensaje.toString(), LARGO_MAXIMO_MENSAJE);
    }

    /**
     * Crea la alerta {@code REPOSICION_MEDICAMENTO} del detalle ({@code
     * REP-{idDetalle}-{yyyyMMdd de FECHA_ESTIMADA_TERMINO}}) en su propia
     * transacción. Obtiene el id del plan con una proyección escalar por
     * JDBC ({@link RepositorioConsultaAlertas#planDeDetalle}, sin cargar la
     * entidad), bloquea el plan ({@code
     * RepositorioPlanMedicamentos#bloquearPorId}) y recién entonces carga el
     * detalle **por primera vez** con {@code findById} y verifica la regla 4
     * con su estado vigente. Cargar el detalle antes del bloqueo del plan
     * dejaría la entidad cacheada en el contexto de persistencia: la
     * verificación posterior vería la foto de antes del bloqueo, no el
     * estado vigente (mismo patrón que {@code
     * tareas.RegistroEjecuciones#marcarError} con {@code
     * RepositorioConsultaGeneracion#planDeOcurrencia}). Nunca crea una tarea
     * de compra.
     *
     * @return el id de la alerta creada, o vacío si el detalle ya no
     *         corresponde o la alerta ya existía.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> crearReposicion(long idDetalle, LocalDate hoy, OffsetDateTime ahora) {
        Long idPlan = consultaAlertas.planDeDetalle(idDetalle).orElse(null);
        if (idPlan == null) {
            return Optional.empty();
        }
        PlanMedicamentos plan = repositorioPlanes.bloquearPorId(idPlan).orElse(null);
        DetallePlan detalle = repositorioDetalles.findById(idDetalle).orElse(null);
        if (plan == null || detalle == null || !esReponible(detalle, plan, hoy)) {
            return Optional.empty();
        }
        LocalDate termino = detalle.getFechaEstimadaTermino();
        String clave = claveReposicion(idDetalle, termino);
        if (repositorioAlertas.findByClaveAlerta(clave).isPresent()) {
            return Optional.empty();
        }
        AdultoMayor adulto = repositorioAdultos.findById(plan.getIdAdultoMayor()).orElseThrow();
        AlertaCuidado alerta = AlertaCuidado.deDetalle(clave, CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, idDetalle,
                inicioDelDia(termino), plan.getIdGrupo(), plan.getIdAdultoMayor(),
                mensajeReposicion(detalle, adulto.getNombreCompleto(), termino), idEstadoAbierta(), ahora);
        return Optional.of(repositorioAlertas.save(alerta).getIdAlerta());
    }

    /**
     * Clave de idempotencia de la reposición de un detalle (regla 1): {@code
     * REP-{idDetalle}-{yyyyMMdd}}. Pública por el mismo motivo que {@link
     * #claveAtraso} (Fase 9, TSA-61).
     */
    public static String claveReposicion(long idDetalle, LocalDate termino) {
        return PREFIJO_REPOSICION + idDetalle + "-" + FORMATO_CLAVE_FECHA.format(termino);
    }

    /**
     * Regla 4 de la Fase 5 sobre el detalle y el plan ya bloqueado (el
     * plan): detalle y plan (con su adulto y su grupo) ACTIVO, con {@code
     * FECHA_ESTIMADA_TERMINO} no nulo y {@code hoy ∈ [término − 3 días,
     * término]}.
     */
    private boolean esReponible(DetallePlan detalle, PlanMedicamentos plan, LocalDate hoy) {
        LocalDate termino = detalle.getFechaEstimadaTermino();
        if (termino == null || !verificador.detalleActivo(detalle) || !verificador.planAdultoYGrupoActivos(plan)) {
            return false;
        }
        LocalDate desde = termino.minusDays(3);
        return !hoy.isBefore(desde) && !hoy.isAfter(termino);
    }

    /**
     * Regla 6: {@code El medicamento {nombreMedicamento} de {nombreAdulto}
     * se terminaría el {fecha}. El coordinador decide si crea la tarea de
     * compra o retiro.}, con la fecha en Santiago, truncado a 1000 bytes
     * UTF-8.
     */
    private static String mensajeReposicion(DetallePlan detalle, String nombreAdulto, LocalDate termino) {
        String mensaje = "El medicamento " + detalle.getNombreMedicamento() + " de " + nombreAdulto
                + " se terminaría el " + FORMATO_FECHA.format(termino)
                + ". El coordinador decide si crea la tarea de compra o retiro.";
        return TextoAcotado.truncarABytes(mensaje, LARGO_MAXIMO_MENSAJE);
    }

    /** Regla 4: las 00:00 de Santiago del día de término, para {@code FECHA_PROGRAMADA}. */
    private static OffsetDateTime inicioDelDia(LocalDate fecha) {
        return ZonedDateTime.of(fecha, LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private long idEstadoAbierta() {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO,
                CodigosAlertas.ESTADO_ALERTA_ABIERTA);
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }
}
