package cl.adndigital.mejorvejez.interno;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ExcepcionSolicitudInvalida;
import cl.adndigital.mejorvejez.comun.TextoAcotado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.GrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaAlerta;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaAsignacion;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaGrupo;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaHistorialAlCierre;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaRelevo;
import cl.adndigital.mejorvejez.interno.RepositorioConsultaReportes.FilaTarea;
import cl.adndigital.mejorvejez.relevos.CodigosRelevos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Rutas internas de reportes (Fase 9, TSA-61, HU-14): el Core le entrega al
 * microservicio de Reportes los grupos y destinatarios, los datos de la
 * semana reconstruidos AL CIERRE del período desde el historial, y lleva el
 * registro anti-duplicados del envío ({@code ENVIO_REPORTE} / {@code
 * ENVIO_REPORTE_DESTINATARIO}, V5). El Core nunca guarda el PDF.
 *
 * <p><b>Cierre.</b> {@code cierre = inicio + 7 días} a las 00:00 en
 * Santiago, instante EXCLUSIVO, calculado con {@code
 * LocalDate.atStartOfDay(ZonaNegocio.ZONA)} (nunca sumando 168 horas): así
 * el período cubre siempre 7 días de calendario, aunque dure 167 o 169
 * horas reales en una semana con cambio de horario. Todas las
 * comparaciones "≤ cierre" del contrato se implementan como {@code <
 * cierre}.
 *
 * <p><b>Reconstrucción desde el historial.</b> {@link #semana} nunca lee el
 * estado ACTUAL de una tarea o una alerta: para cada tarea del período
 * busca la última entrada de {@code HISTORIAL_ESTADO_TAREA} con {@code
 * FECHA_CAMBIO < cierre} (si no hay ninguna, {@code PENDIENTE}) y la última
 * asignación vigente a esa fecha; para cada alerta, compara su {@code
 * FECHA_REVISION} contra el cierre. Así el reporte de una semana pasada no
 * cambia aunque la tarea o la alerta sigan evolucionando después.
 */
@Service
public class ServicioInternoReportes {

    static final String MENSAJE_INICIO_REQUERIDO = "El inicio del período es obligatorio.";
    static final String MENSAJE_INICIO_NO_LUNES = "El inicio del período debe ser un lunes.";
    static final String MENSAJE_GRUPO_NO_EXISTE = "El grupo no existe.";
    static final String MENSAJE_ENVIO_NO_EXISTE = "El envío no existe.";
    static final String MENSAJE_ENVIO_CERRADO = "El envío ya está cerrado (ENVIADO o FALLIDO).";
    static final String MENSAJE_RESULTADOS_VACIOS = "La lista de resultados no puede venir vacía.";
    static final String MENSAJE_RESULTADO_NULO = "La lista de resultados no puede contener elementos nulos.";
    static final String MENSAJE_SIN_DESTINATARIOS = "Sin destinatarios activos";

    private static final int MAXIMO_BYTES_DETALLE_ERROR = 500;

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioConsultaReportes consulta;
    private final RepositorioEnvioReporte repositorioEnvio;
    private final RepositorioEnvioReporteDestinatario repositorioDestinatario;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;

    public ServicioInternoReportes(RepositorioGrupoFamiliar repositorioGrupo, RepositorioUsuario repositorioUsuario,
            RepositorioConsultaReportes consulta, RepositorioEnvioReporte repositorioEnvio,
            RepositorioEnvioReporteDestinatario repositorioDestinatario, ServicioCatalogos servicioCatalogos,
            Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioUsuario = repositorioUsuario;
        this.consulta = consulta;
        this.repositorioEnvio = repositorioEnvio;
        this.repositorioDestinatario = repositorioDestinatario;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
    }

    /** {@code GET /api/interno/reportes/grupos}: los grupos {@code ACTIVO} con sus destinatarios vigentes. */
    @Transactional(readOnly = true)
    public List<GrupoReporte> grupos() {
        List<GrupoReporte> resultado = new ArrayList<>();
        for (FilaGrupo fila : consulta.gruposActivos()) {
            resultado.add(new GrupoReporte(fila.idGrupo(), fila.nombreGrupo(),
                    consulta.destinatariosActivos(fila.idGrupo())));
        }
        return resultado;
    }

    /**
     * {@code GET /api/interno/reportes/grupos/{idGrupo}/semana?inicio}: los
     * datos de la semana de {@code idGrupo} reconstruidos al cierre del
     * período {@code [inicio, inicio+7 días)} en Santiago.
     *
     * @throws ExcepcionSolicitudInvalida {@code inicio} no es un lunes.
     * @throws ExcepcionNoEncontrado      el grupo no existe (uno INACTIVO
     *                                    igual responde).
     */
    @Transactional(readOnly = true)
    public DatosSemana semana(long idGrupo, LocalDate inicio) {
        exigirLunes(inicio);
        GrupoFamiliar grupo =
                repositorioGrupo.findById(idGrupo).orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_GRUPO_NO_EXISTE));

        OffsetDateTime inicioInstante = inicio.atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime cierre = inicio.plusDays(7).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        LocalDate fin = inicio.plusDays(6);

        List<FilaTarea> filasTarea = consulta.tareasDelPeriodo(idGrupo, inicioInstante, cierre);
        List<Long> idsTarea = filasTarea.stream().map(FilaTarea::idTarea).toList();
        Map<Long, FilaHistorialAlCierre> historialPorTarea =
                indexarPorIdTarea(consulta.ultimoHistorialAntesDe(idsTarea, cierre));
        Map<Long, List<FilaAsignacion>> asignacionesPorTarea = agruparAsignacionesPorTarea(idsTarea, cierre);

        List<FilaRelevo> filasRelevo = consulta.relevosDelPeriodo(idGrupo, inicioInstante, cierre);
        List<Long> idsTraspaso = filasRelevo.stream().map(FilaRelevo::idTraspaso).toList();
        Map<Long, Map<String, Long>> conteosTraspaso = consulta.conteosDetalleTraspaso(idsTraspaso);

        Map<Long, String> nombresUsuario = nombresDePersonasInvolucradas(historialPorTarea, asignacionesPorTarea,
                filasRelevo);

        List<TareaSemana> tareas = filasTarea.stream()
                .map(f -> ensamblarTarea(f, cierre, historialPorTarea, asignacionesPorTarea, nombresUsuario))
                .toList();

        Set<Long> idsAdultoConTarea = new TreeSet<>();
        tareas.forEach(t -> idsAdultoConTarea.add(t.idAdulto()));
        Map<Long, String> nombresAdulto = consulta.nombresAdultos(idsAdultoConTarea);
        List<AdultoSemana> adultos = idsAdultoConTarea.stream()
                .map(id -> new AdultoSemana(id, nombresAdulto.get(id)))
                .toList();

        List<RelevoSemana> relevos = filasRelevo.stream()
                .map(f -> ensamblarRelevo(f, conteosTraspaso, nombresUsuario))
                .toList();

        List<FilaAlerta> filasAlerta = consulta.alertasDelPeriodo(idGrupo, inicioInstante, cierre);
        List<AlertaSemana> alertas = filasAlerta.stream().map(f -> ensamblarAlerta(f, cierre)).toList();

        return new DatosSemana(idGrupo, grupo.getNombreGrupo(), inicio, fin, adultos, tareas, relevos, alertas);
    }

    /**
     * {@code POST /api/interno/reportes/envios}: crea el envío (con la foto
     * actual de destinatarios) o devuelve el existente, idempotente por
     * {@code (idGrupo, inicioPeriodo)}.
     *
     * @throws ExcepcionSolicitudInvalida {@code inicioPeriodo} no es un
     *                                    lunes.
     * @throws ExcepcionNoEncontrado      el grupo no existe.
     */
    @Transactional
    public RespuestaReservaEnvio reservarEnvio(SolicitudReservarEnvio solicitud) {
        long idGrupo = solicitud.idGrupo();
        LocalDate inicio = solicitud.inicioPeriodo();
        exigirLunes(inicio);
        // Bloquea el GRUPO ANTES de buscar/crear: así dos POST concurrentes del mismo grupo
        // (cualquier período) se serializan y nunca chocan con UQ_ENVIO_REPORTE_GRUPO_PERIODO.
        repositorioGrupo.bloquearPorId(idGrupo).orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_GRUPO_NO_EXISTE));

        EnvioReporte envio = repositorioEnvio.findByIdGrupoAndInicioPeriodo(idGrupo, inicio)
                .orElseGet(() -> crearEnvioConFoto(idGrupo, inicio));
        List<EnvioReporteDestinatario> destinatarios = repositorioDestinatario.findByIdEnvioOrderByIdUsuario(
                envio.getIdEnvio());

        List<Destinatario> pendientes = calcularPendientes(envio, destinatarios);
        return new RespuestaReservaEnvio(envio.getIdEnvio(), codigoEstadoEnvio(envio.getIdEstado()),
                envio.getIntentos(), pendientes);
    }

    /**
     * {@code PUT /api/interno/reportes/envios/{idEnvio}/resultado}: marca a
     * cada destinatario indicado como {@code ENVIADO} o {@code FALLIDO} y
     * suma 1 a {@code INTENTOS} del envío.
     *
     * @throws ExcepcionReglaNegocio {@code resultados} viene vacía, o algún
     *                               {@code idUsuario} no pertenece a la foto
     *                               del envío (422).
     * @throws ExcepcionNoEncontrado el envío no existe (404).
     * @throws ExcepcionConflicto    el envío ya está {@code ENVIADO} o
     *                               {@code FALLIDO} (409).
     */
    @Transactional
    public RespuestaResultadoEnvio registrarResultado(long idEnvio, SolicitudResultadoEnvio solicitud) {
        if (solicitud.resultados() == null || solicitud.resultados().isEmpty()) {
            throw new ExcepcionReglaNegocio(MENSAJE_RESULTADOS_VACIOS);
        }
        if (solicitud.resultados().stream().anyMatch(Objects::isNull)) {
            throw new ExcepcionSolicitudInvalida(MENSAJE_RESULTADO_NULO);
        }
        // Bloquea la fila del envío.
        EnvioReporte envio =
                repositorioEnvio.bloquearPorId(idEnvio).orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_ENVIO_NO_EXISTE));

        long idEstadoEnviado = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        long idEstadoFallido = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_FALLIDO);
        long idEstadoPendiente = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
        if (envio.getIdEstado() == idEstadoEnviado || envio.getIdEstado() == idEstadoFallido) {
            throw new ExcepcionConflicto(MENSAJE_ENVIO_CERRADO);
        }

        List<EnvioReporteDestinatario> destinatarios = repositorioDestinatario.findByIdEnvioOrderByIdUsuario(idEnvio);
        Map<Long, EnvioReporteDestinatario> destinatariosPorUsuario = new HashMap<>();
        destinatarios.forEach(d -> destinatariosPorUsuario.put(d.getIdUsuario(), d));

        for (ResultadoDestinatario resultado : solicitud.resultados()) {
            if (!destinatariosPorUsuario.containsKey(resultado.idUsuario())) {
                throw new ExcepcionReglaNegocio(
                        "El usuario " + resultado.idUsuario() + " no pertenece a la foto de este envío.");
            }
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long idEstadoDestinatarioEnviado = idEstadoEnvioDestinatario(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        long idEstadoDestinatarioFallido = idEstadoEnvioDestinatario(CodigosReportes.ESTADO_ENVIO_FALLIDO);
        for (ResultadoDestinatario resultado : solicitud.resultados()) {
            EnvioReporteDestinatario destinatario = destinatariosPorUsuario.get(resultado.idUsuario());
            if (destinatario.getIdEstado() == idEstadoDestinatarioEnviado) {
                // Ya está ENVIADO (estado final de éxito): un resultado nuevo sobre él se ignora
                // entero, nunca lo devuelve a FALLIDO. Un FALLIDO sí puede pasar a ENVIADO (reintento).
                continue;
            }
            long idEstadoNuevo = resultado.enviado() ? idEstadoDestinatarioEnviado : idEstadoDestinatarioFallido;
            String detalleError = TextoAcotado.truncarABytes(resultado.detalleError(), MAXIMO_BYTES_DETALLE_ERROR);
            destinatario.marcarResultado(idEstadoNuevo, detalleError, ahora);
            repositorioDestinatario.save(destinatario);
        }

        long cantidadFallidos = destinatariosPorUsuario.values().stream()
                .filter(d -> d.getIdEstado() != idEstadoDestinatarioEnviado).count();

        long idEstadoResultante;
        String detalleResumen;
        if (cantidadFallidos == 0) {
            idEstadoResultante = idEstadoEnviado;
            detalleResumen = null;
        } else if (envio.getIntentos() + 1 >= CodigosReportes.MAXIMO_INTENTOS) {
            idEstadoResultante = idEstadoFallido;
            detalleResumen = cantidadFallidos + " destinatarios fallidos";
        } else {
            idEstadoResultante = idEstadoPendiente;
            detalleResumen = cantidadFallidos + " destinatarios fallidos";
        }
        envio.registrarResultado(idEstadoResultante, detalleResumen, ahora);
        repositorioEnvio.save(envio);
        return new RespuestaResultadoEnvio(codigoEstadoEnvio(idEstadoResultante));
    }

    // ------------------------------------------------------------------
    // POST .../envios: creación con foto
    // ------------------------------------------------------------------

    private EnvioReporte crearEnvioConFoto(long idGrupo, LocalDate inicio) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<Destinatario> foto = consulta.destinatariosActivos(idGrupo);

        if (foto.isEmpty()) {
            long idEstadoEnviado = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_ENVIADO);
            EnvioReporte envioSinDestinatarios =
                    new EnvioReporte(idGrupo, inicio, inicio.plusDays(6), idEstadoEnviado, ahora);
            envioSinDestinatarios.marcarSinDestinatarios(idEstadoEnviado, MENSAJE_SIN_DESTINATARIOS, ahora);
            return repositorioEnvio.save(envioSinDestinatarios);
        }

        long idEstadoPendiente = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
        EnvioReporte envio = repositorioEnvio.save(
                new EnvioReporte(idGrupo, inicio, inicio.plusDays(6), idEstadoPendiente, ahora));

        long idEstadoDestinatarioPendiente = idEstadoEnvioDestinatario(CodigosReportes.ESTADO_ENVIO_PENDIENTE);
        for (Destinatario destinatario : foto) {
            repositorioDestinatario.save(
                    new EnvioReporteDestinatario(envio.getIdEnvio(), destinatario.idUsuario(),
                            idEstadoDestinatarioPendiente));
        }
        return envio;
    }

    /**
     * Los destinatarios de la foto que todavía no están {@code ENVIADO};
     * vacía si el envío ya quedó {@code ENVIADO} o {@code FALLIDO} (con 3
     * intentos, el único modo en que existe un {@code FALLIDO}).
     */
    private List<Destinatario> calcularPendientes(EnvioReporte envio, List<EnvioReporteDestinatario> destinatarios) {
        long idEstadoEnviado = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        long idEstadoFallido = idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_FALLIDO);
        if (envio.getIdEstado() == idEstadoEnviado || envio.getIdEstado() == idEstadoFallido) {
            return List.of();
        }
        long idEstadoDestinatarioEnviado = idEstadoEnvioDestinatario(CodigosReportes.ESTADO_ENVIO_ENVIADO);
        List<Long> idsPendientes = destinatarios.stream()
                .filter(d -> d.getIdEstado() != idEstadoDestinatarioEnviado)
                .map(EnvioReporteDestinatario::getIdUsuario)
                .toList();
        if (idsPendientes.isEmpty()) {
            return List.of();
        }
        Map<Long, Usuario> usuariosPorId = new HashMap<>();
        repositorioUsuario.findAllById(idsPendientes).forEach(u -> usuariosPorId.put(u.getIdUsuario(), u));
        return idsPendientes.stream()
                .map(id -> {
                    Usuario usuario = usuariosPorId.get(id);
                    return new Destinatario(id, usuario != null ? usuario.getNombre() : null,
                            usuario != null ? usuario.getEmail() : null);
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // GET .../semana: ensamblado
    // ------------------------------------------------------------------

    private void exigirLunes(LocalDate fecha) {
        if (fecha == null) {
            throw new ExcepcionSolicitudInvalida(MENSAJE_INICIO_REQUERIDO);
        }
        if (fecha.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ExcepcionSolicitudInvalida(MENSAJE_INICIO_NO_LUNES);
        }
    }

    private Map<Long, FilaHistorialAlCierre> indexarPorIdTarea(List<FilaHistorialAlCierre> filas) {
        Map<Long, FilaHistorialAlCierre> resultado = new HashMap<>();
        filas.forEach(f -> resultado.put(f.idTarea(), f));
        return resultado;
    }

    private Map<Long, List<FilaAsignacion>> agruparAsignacionesPorTarea(List<Long> idsTarea, OffsetDateTime cierre) {
        Map<Long, List<FilaAsignacion>> resultado = new HashMap<>();
        for (FilaAsignacion fila : consulta.asignacionesAntesDe(idsTarea, cierre)) {
            resultado.computeIfAbsent(fila.idTarea(), ignorado -> new ArrayList<>()).add(fila);
        }
        return resultado;
    }

    private Map<Long, String> nombresDePersonasInvolucradas(Map<Long, FilaHistorialAlCierre> historialPorTarea,
            Map<Long, List<FilaAsignacion>> asignacionesPorTarea, List<FilaRelevo> filasRelevo) {
        Set<Long> ids = new HashSet<>();
        historialPorTarea.values().forEach(h -> ids.add(h.idUsuarioCambio()));
        asignacionesPorTarea.values().forEach(lista -> lista.forEach(a -> ids.add(a.idUsuarioAsignado())));
        filasRelevo.forEach(r -> {
            ids.add(r.idUsuarioSaliente());
            ids.add(r.idUsuarioEntrante());
        });
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> resultado = new HashMap<>();
        repositorioUsuario.findAllById(ids).forEach(u -> resultado.put(u.getIdUsuario(), u.getNombre()));
        return resultado;
    }

    private TareaSemana ensamblarTarea(FilaTarea f, OffsetDateTime cierre,
            Map<Long, FilaHistorialAlCierre> historialPorTarea, Map<Long, List<FilaAsignacion>> asignacionesPorTarea,
            Map<Long, String> nombresUsuario) {
        FilaHistorialAlCierre historial = historialPorTarea.get(f.idTarea());
        String codigoEstadoAlCierre =
                historial != null ? historial.codigoEstado() : CodigosTareas.ESTADO_TAREA_PENDIENTE;

        OffsetDateTime fechaConfirmacion = null;
        Persona confirmadoPor = null;
        if (historial != null && (CodigosTareas.ESTADO_TAREA_COMPLETADA.equals(historial.codigoEstado())
                || CodigosTareas.ESTADO_TAREA_NO_REALIZADA.equals(historial.codigoEstado()))) {
            fechaConfirmacion = historial.fechaCambio();
            confirmadoPor = new Persona(historial.idUsuarioCambio(), nombresUsuario.get(historial.idUsuarioCambio()));
        }

        List<FilaAsignacion> asignaciones = asignacionesPorTarea.getOrDefault(f.idTarea(), List.of());
        boolean reasignada = asignaciones.size() > 1;
        Persona responsableAlCierre = asignaciones.stream()
                .filter(a -> a.fechaTermino() == null || !a.fechaTermino().isBefore(cierre))
                .findFirst()
                .map(a -> new Persona(a.idUsuarioAsignado(), nombresUsuario.get(a.idUsuarioAsignado())))
                .orElse(null);

        return new TareaSemana(f.idTarea(), f.idAdulto(), f.tipoTarea(), f.titulo(), f.fechaHorario(),
                f.minutosTolerancia(), codigoEstadoAlCierre, fechaConfirmacion, confirmadoPor, responsableAlCierre,
                reasignada);
    }

    private RelevoSemana ensamblarRelevo(FilaRelevo f, Map<Long, Map<String, Long>> conteosTraspaso,
            Map<Long, String> nombresUsuario) {
        Map<String, Long> porTipo = conteosTraspaso.getOrDefault(f.idTraspaso(), Map.of());
        int totalTareas = porTipo.getOrDefault(CodigosRelevos.TIPO_ELEMENTO_TAREA, 0L).intValue();
        int totalAlertas = porTipo.getOrDefault(CodigosRelevos.TIPO_ELEMENTO_ALERTA, 0L).intValue();
        return new RelevoSemana(f.idTraspaso(), f.idAdulto(), f.fechaTraspaso(),
                new Persona(f.idUsuarioSaliente(), nombresUsuario.get(f.idUsuarioSaliente())),
                new Persona(f.idUsuarioEntrante(), nombresUsuario.get(f.idUsuarioEntrante())), totalTareas,
                totalAlertas, f.codigoEstado());
    }

    private AlertaSemana ensamblarAlerta(FilaAlerta f, OffsetDateTime cierre) {
        boolean cerradaAntesDeCierre = f.fechaRevision() != null && f.fechaRevision().isBefore(cierre);
        String codigoEstadoAlCierre =
                cerradaAntesDeCierre ? CodigosAlertas.ESTADO_ALERTA_CERRADA : CodigosAlertas.ESTADO_ALERTA_ABIERTA;
        return new AlertaSemana(f.idAlerta(), f.idAdulto(), f.tipoAlerta(), f.fechaDeteccion(), codigoEstadoAlCierre,
                f.fechaRevision());
    }

    // ------------------------------------------------------------------
    // Catálogo (ENVIO_REPORTE / ENVIO_REPORTE_DESTINATARIO)
    // ------------------------------------------------------------------

    private long idEstadoEnvio(String codigo) {
        return servicioCatalogos.idEstado(CodigosReportes.TABLA_ENVIO_REPORTE, CodigosReportes.CAMPO_ESTADO, codigo);
    }

    private long idEstadoEnvioDestinatario(String codigo) {
        return servicioCatalogos.idEstado(CodigosReportes.TABLA_ENVIO_REPORTE_DESTINATARIO,
                CodigosReportes.CAMPO_ESTADO, codigo);
    }

    /** El código de {@code ENVIO_REPORTE.ESTADO} correspondiente a {@code idEstado}. */
    private String codigoEstadoEnvio(long idEstado) {
        if (idEstado == idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_ENVIADO)) {
            return CodigosReportes.ESTADO_ENVIO_ENVIADO;
        }
        if (idEstado == idEstadoEnvio(CodigosReportes.ESTADO_ENVIO_FALLIDO)) {
            return CodigosReportes.ESTADO_ENVIO_FALLIDO;
        }
        return CodigosReportes.ESTADO_ENVIO_PENDIENTE;
    }
}
