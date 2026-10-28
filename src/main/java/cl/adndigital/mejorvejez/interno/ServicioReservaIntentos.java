package cl.adndigital.mejorvejez.interno;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.alertas.AlertaCuidado;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.IntentoNotificacion;
import cl.adndigital.mejorvejez.alertas.RepositorioAlertaCuidado;
import cl.adndigital.mejorvejez.alertas.RepositorioConsultaAlertas;
import cl.adndigital.mejorvejez.alertas.RepositorioIntentoNotificacion;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.notificaciones.CanalOperativo;
import cl.adndigital.mejorvejez.notificaciones.ServicioCanales;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;
import cl.adndigital.mejorvejez.tareas.VerificadorVigencia;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Reserva y resuelve los intentos de envío de una alerta (Fase 9, TSA-61):
 * el Core ya no envía correos (lo hace el microservicio de Alertas, fuera de
 * esta transacción), pero sigue siendo el único con base de datos y el que
 * protege la unicidad del par (destinatario, canal, número) y el máximo de
 * intentos.
 *
 * <p><b>No depende de ningún enviador de correo o WhatsApp</b>: el bloqueo
 * de la alerta, la vigencia del origen y el cálculo de destinatarios de
 * {@link #reservarIntentos} aplican las mismas reglas de destinatarios
 * (regla 7), de canales operativos (regla 8) y de revisión de vigencia de
 * la Fase 5, con el mismo bloqueo {@code SELECT ... FOR UPDATE} de la
 * alerta, pero sin enviar nada: deja el intento en {@code PENDIENTE} para
 * que el microservicio de Alertas lo envíe y confirme con {@link
 * #registrarResultado}.
 *
 * <p><b>Pendientes huérfanos.</b> Antes de calcular nada, {@link
 * #reservarIntentos} pasa a {@code FALLIDO} (detalle "Sin confirmación del
 * servicio de notificaciones") los {@code PENDIENTE} de la alerta con más de
 * {@value #VENTANA_CONFIRMACION_MINUTOS} minutos: el microservicio de
 * Alertas pudo haberse caído entre la reserva y el {@code PUT} del
 * resultado, y esta es la única forma de liberar ese par para un reintento
 * sin perder el aviso ("al menos una vez"). Un {@code PENDIENTE} más
 * reciente, en cambio, sigue vigente y bloquea una nueva reserva del mismo
 * par (evita duplicados).
 *
 * <p>Todo el método corre en una sola transacción: el bloqueo de la alerta
 * serializa dos reservas concurrentes de la misma alerta (la segunda ve, ya
 * confirmados, los {@code PENDIENTE} que insertó la primera), así que nunca
 * se reserva dos veces el mismo par.
 */
@Service
public class ServicioReservaIntentos {

    /** Minutos desde la reserva tras los que un {@code PENDIENTE} sin confirmar se considera huérfano. */
    static final int VENTANA_CONFIRMACION_MINUTOS = 10;

    static final String MENSAJE_SIN_CONFIRMACION = "Sin confirmación del servicio de notificaciones";
    static final String MENSAJE_INTENTO_INEXISTENTE = "El intento indicado no existe.";
    static final String MENSAJE_RESULTADO_EN_OTRO_ESTADO = "El intento ya tiene otro resultado final.";

    private final RepositorioAlertaCuidado repositorioAlertas;
    private final RepositorioIntentoNotificacion repositorioIntentos;
    private final RepositorioConsultaAlertas consultaAlertas;
    private final RepositorioDetallePlan repositorioDetalles;
    private final RepositorioTareaCuidado repositorioTareas;
    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final VerificadorVigencia verificador;
    private final RepositorioUsuario repositorioUsuarios;
    private final ServicioCanales servicioCanales;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;
    private final int maximoIntentos;

    public ServicioReservaIntentos(RepositorioAlertaCuidado repositorioAlertas,
            RepositorioIntentoNotificacion repositorioIntentos, RepositorioConsultaAlertas consultaAlertas,
            RepositorioDetallePlan repositorioDetalles, RepositorioTareaCuidado repositorioTareas,
            RepositorioPlanMedicamentos repositorioPlanes, VerificadorVigencia verificador,
            RepositorioUsuario repositorioUsuarios, ServicioCanales servicioCanales,
            ServicioCatalogos servicioCatalogos, Clock reloj,
            @Value("${mejorvejez.notificaciones.max-intentos}") int maximoIntentos) {
        this.repositorioAlertas = repositorioAlertas;
        this.repositorioIntentos = repositorioIntentos;
        this.consultaAlertas = consultaAlertas;
        this.repositorioDetalles = repositorioDetalles;
        this.repositorioTareas = repositorioTareas;
        this.repositorioPlanes = repositorioPlanes;
        this.verificador = verificador;
        this.repositorioUsuarios = repositorioUsuarios;
        this.servicioCanales = servicioCanales;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
        this.maximoIntentos = maximoIntentos;
    }

    /**
     * {@code GET /api/interno/alertas/envios-pendientes?desde}: recorre las
     * alertas {@code ABIERTA} detectadas desde {@code desde} (misma consulta
     * que usaba la Fase 5 para "notificar pendientes", {@code
     * RepositorioAlertaCuidado#idsAbiertasDesde}) y, para cada una, aplica
     * la MISMA lógica de elegibilidad que {@link #reservarIntentos}
     * ({@link #paresPorIntentar}: vigencia del origen, destinatarios
     * vigentes, canales operativos e historial por par), sin bloquear ni
     * insertar nada. Así nunca deja fuera una alerta cuyos destinatarios
     * cambiaron desde el último intento (p. ej. una reasignación, un nuevo
     * coordinador o un grupo que empieza a notificarlo): a diferencia de
     * mirar solo los intentos ya registrados, vuelve a calcular quién
     * debería recibirla hoy.
     */
    @Transactional(readOnly = true)
    public List<Long> enviosPendientes(OffsetDateTime desde) {
        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<Long> resultado = new ArrayList<>();
        for (Long idAlerta : repositorioAlertas.idsAbiertasDesde(idEstadoAbierta, desde)) {
            AlertaCuidado alerta = repositorioAlertas.findById(idAlerta).orElse(null);
            if (alerta != null && !paresPorIntentar(alerta, ahora).isEmpty()) {
                resultado.add(idAlerta);
            }
        }
        return resultado;
    }

    /**
     * {@code POST /api/interno/alertas/{idAlerta}/intentos/reservar}.
     *
     * @throws ExcepcionNoEncontrado la alerta no existe.
     */
    @Transactional
    public RespuestaReservaIntentos reservarIntentos(long idAlerta) {
        AlertaCuidado alerta = repositorioAlertas.bloquearPorId(idAlerta)
                .orElseThrow(() -> new ExcepcionNoEncontrado("La alerta " + idAlerta + " no existe."));
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        DatosAlertaCorreo datos = new DatosAlertaCorreo(alerta.getIdAlerta(), alerta.getIdGrupo(),
                alerta.getTipoAlerta(), alerta.getMensajeAlerta());

        expirarPendientesHuerfanos(idAlerta, ahora);

        long idEstadoCerrada = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_CERRADA);
        if (alerta.getIdEstado().longValue() == idEstadoCerrada) {
            return new RespuestaReservaIntentos(datos, List.of());
        }

        long idEstadoPendiente = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        List<IntentoReservado> reservados = new ArrayList<>();
        for (ParElegible par : paresPorIntentar(alerta, ahora)) {
            IntentoNotificacion intento = IntentoNotificacion.pendiente(idAlerta, par.usuario().getIdUsuario(),
                    par.canal().canal(), par.numeroIntento(), idEstadoPendiente, ahora);
            repositorioIntentos.save(intento);
            reservados.add(new IntentoReservado(intento.getIdIntento(), par.usuario().getIdUsuario(),
                    par.usuario().getNombre(), par.canal().canal(), par.canal().destino(), par.numeroIntento()));
        }
        return new RespuestaReservaIntentos(datos, reservados);
    }

    /**
     * {@code PUT /api/interno/intentos/{idIntento}}: solo pasa de {@code
     * PENDIENTE} a un estado final. Si el intento ya está en ese mismo
     * estado final responde silenciosamente (204, idempotente); si está en
     * otro estado final, {@link ExcepcionConflicto} (409).
     *
     * @throws ExcepcionNoEncontrado  el intento no existe.
     * @throws ExcepcionReglaNegocio  {@code resultado} no es {@code ENVIADO} ni {@code FALLIDO}.
     * @throws ExcepcionConflicto     el intento ya tiene otro resultado final.
     */
    @Transactional
    public void registrarResultado(long idIntento, SolicitudResultadoIntento solicitud) {
        long idEstadoResultado = idEstadoEnvioSolicitado(solicitud.resultado());

        // Bloquea la alerta ANTES de cargar el intento (no al revés): así se serializa con una
        // reserva concurrente de la misma alerta que esté expirando pendientes huérfanos, y la
        // lectura de más abajo ve el resultado ya confirmado por esa reserva, nunca una versión
        // cacheada de antes del bloqueo.
        long idAlerta = repositorioIntentos.idAlertaDe(idIntento)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_INTENTO_INEXISTENTE));
        repositorioAlertas.bloquearPorId(idAlerta).orElseThrow();

        IntentoNotificacion intento = repositorioIntentos.findById(idIntento)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_INTENTO_INEXISTENTE));

        long idEstadoPendiente = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        if (intento.esPendiente(idEstadoPendiente)) {
            intento.pasarAEstadoFinal(idEstadoResultado, solicitud.detalleError(), solicitud.referenciaProveedor());
            repositorioIntentos.save(intento);
            return;
        }
        if (intento.getIdEstadoEnvio().longValue() == idEstadoResultado) {
            return;
        }
        throw new ExcepcionConflicto(MENSAJE_RESULTADO_EN_OTRO_ESTADO);
    }

    /**
     * Pasa a {@code FALLIDO} los {@code PENDIENTE} de la alerta con más de
     * {@value #VENTANA_CONFIRMACION_MINUTOS} minutos desde su reserva (ventana de confirmación).
     */
    private void expirarPendientesHuerfanos(long idAlerta, OffsetDateTime ahora) {
        long idEstadoPendiente = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        long idEstadoFallido = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        OffsetDateTime limite = ahora.minusMinutes(VENTANA_CONFIRMACION_MINUTOS);
        for (IntentoNotificacion intento : repositorioIntentos
                .findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(idAlerta)) {
            if (intento.esPendiente(idEstadoPendiente) && intento.getFechaIntento().isBefore(limite)) {
                intento.pasarAEstadoFinal(idEstadoFallido, MENSAJE_SIN_CONFIRMACION, null);
                repositorioIntentos.save(intento);
            }
        }
    }

    /**
     * Los pares (destinatario, canal) todavía por intentar de la alerta, en
     * el instante {@code ahora}, sin bloquear nada ni insertar ningún
     * intento: la misma elegibilidad que aplica {@link #reservarIntentos}
     * (vigencia del origen, destinatarios, canales operativos e historial
     * por par), reutilizada también por {@link #enviosPendientes} para no
     * duplicar el criterio. Vacía si el origen ya no está vigente.
     */
    private List<ParElegible> paresPorIntentar(AlertaCuidado alerta, OffsetDateTime ahora) {
        if (!origenVigente(alerta)) {
            return List.of();
        }
        List<Usuario> destinatarios = repositorioUsuarios.findAllById(destinatarios(alerta)).stream()
                .sorted(Comparator.comparing(Usuario::getIdUsuario))
                .toList();
        Map<Long, List<CanalOperativo>> canales = servicioCanales.canalesOperativos(destinatarios);
        Map<Par, HistorialPar> historial = historialPorPar(alerta.getIdAlerta(), ahora);

        List<ParElegible> elegibles = new ArrayList<>();
        for (Usuario usuario : destinatarios) {
            for (CanalOperativo canal : canales.getOrDefault(usuario.getIdUsuario(), List.of())) {
                Par par = new Par(usuario.getIdUsuario(), canal.canal());
                HistorialPar previo = historial.getOrDefault(par, HistorialPar.VACIO);
                if (previo.enviado() || previo.cantidad() >= maximoIntentos || previo.tienePendienteVigente()) {
                    continue;
                }
                elegibles.add(new ParElegible(usuario, canal, previo.numeroMaximo() + 1));
            }
        }
        return elegibles;
    }

    /**
     * Si el origen de la alerta sigue vigente (regla de la Fase 5): tarea
     * PENDIENTE o EN_CURSO con el adulto y el grupo ACTIVO, o detalle y
     * plan ACTIVO con el adulto y el grupo ACTIVO. Solo lee.
     */
    private boolean origenVigente(AlertaCuidado alerta) {
        if (alerta.getIdTarea() != null) {
            TareaCuidado tarea = repositorioTareas.findById(alerta.getIdTarea()).orElse(null);
            if (tarea == null) {
                return false;
            }
            long idEstado = tarea.getIdEstado();
            boolean abierta = idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE)
                    || idEstado == estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO);
            return abierta && verificador.adultoYGrupoActivos(tarea.getIdAdultoMayor(), tarea.getIdGrupo());
        }
        return repositorioDetalles.findById(alerta.getIdDetallePlan())
                .filter(verificador::detalleActivo)
                .flatMap(detalle -> repositorioPlanes.findById(detalle.getIdPlan()))
                .filter(verificador::planAdultoYGrupoActivos)
                .isPresent();
    }

    /**
     * Destinatarios de la alerta (regla 7 de la Fase 5), deduplicados y por
     * id ascendente.
     */
    private TreeSet<Long> destinatarios(AlertaCuidado alerta) {
        long idGrupo = alerta.getIdGrupo();
        List<Long> responsables;
        boolean incluirCoordinadores;
        if (alerta.getIdTarea() != null) {
            responsables = elegibles(idGrupo, consultaAlertas.responsableActivo(alerta.getIdTarea()));
            incluirCoordinadores = responsables.isEmpty() || consultaAlertas.notificaCoordinador(idGrupo);
        } else {
            Long idPredeterminado = repositorioDetalles.findById(alerta.getIdDetallePlan())
                    .map(DetallePlan::getIdResponsablePredeterminado)
                    .orElse(null);
            responsables = idPredeterminado == null ? List.of() : elegibles(idGrupo, List.of(idPredeterminado));
            incluirCoordinadores = true;
        }
        TreeSet<Long> ids = new TreeSet<>(responsables);
        if (incluirCoordinadores) {
            ids.addAll(consultaAlertas.coordinadoresActivos(idGrupo));
        }
        return ids;
    }

    private List<Long> elegibles(long idGrupo, List<Long> idsUsuario) {
        return idsUsuario.stream().filter(id -> consultaAlertas.esIntegranteElegible(idGrupo, id)).toList();
    }

    /**
     * Historial de intentos de la alerta por par (destinatario, canal) en el
     * instante {@code ahora}: si ya tiene un {@code ENVIADO}, cuántos
     * intentos tiene en total (para el máximo), su número más alto y si
     * tiene un {@code PENDIENTE} todavía vigente (que bloquea una nueva
     * reserva del mismo par). Un {@code PENDIENTE} con más de {@value
     * #VENTANA_CONFIRMACION_MINUTOS} minutos cuenta para el máximo pero NO
     * bloquea: es el mismo criterio que {@link #expirarPendientesHuerfanos}
     * aplicaría si se llamara justo antes, así que {@link #paresPorIntentar}
     * da la misma elegibilidad se haya expirado ya el huérfano (como hace
     * {@link #reservarIntentos}) o no (como en la sola lectura de {@link
     * #enviosPendientes}).
     */
    private Map<Par, HistorialPar> historialPorPar(long idAlerta, OffsetDateTime ahora) {
        long idEstadoPendiente = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_PENDIENTE);
        long idEstadoEnviado = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        OffsetDateTime limite = ahora.minusMinutes(VENTANA_CONFIRMACION_MINUTOS);
        Map<Par, HistorialPar> historial = new HashMap<>();
        for (IntentoNotificacion intento : repositorioIntentos
                .findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(idAlerta)) {
            Par par = new Par(intento.getIdUsuarioDestinatario(), intento.getCanal());
            HistorialPar previo = historial.getOrDefault(par, HistorialPar.VACIO);
            boolean pendienteVigente = intento.esPendiente(idEstadoPendiente)
                    && !intento.getFechaIntento().isBefore(limite);
            historial.put(par, new HistorialPar(
                    previo.enviado() || intento.getIdEstadoEnvio().longValue() == idEstadoEnviado,
                    previo.cantidad() + 1,
                    Math.max(previo.numeroMaximo(), intento.getNumeroIntento()),
                    previo.tienePendienteVigente() || pendienteVigente));
        }
        return historial;
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoAlerta(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoEnvio(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_INTENTO_NOTIFICACION,
                CodigosAlertas.CAMPO_ESTADO_ENVIO, codigo);
    }

    /** @throws ExcepcionReglaNegocio {@code resultado} no es {@code ENVIADO} ni {@code FALLIDO}. */
    private long idEstadoEnvioSolicitado(String resultado) {
        return switch (resultado) {
            case CodigosAlertas.ESTADO_ENVIO_ENVIADO, CodigosAlertas.ESTADO_ENVIO_FALLIDO ->
                    idEstadoEnvio(resultado);
            case null, default -> throw new ExcepcionReglaNegocio(
                    "El resultado '" + resultado + "' no es válido: debe ser ENVIADO o FALLIDO.");
        };
    }

    /** Par (destinatario, canal); {@code long} para comparar ids por valor. */
    private record Par(long idUsuario, String canal) {
    }

    /** Un par elegible, con el número de intento que le corresponde. */
    private record ParElegible(Usuario usuario, CanalOperativo canal, int numeroIntento) {
    }

    /** Lo que ya pasó con un par: si tiene un ENVIADO, cuántos intentos tiene, su número máximo y si tiene un PENDIENTE vigente. */
    private record HistorialPar(boolean enviado, int cantidad, int numeroMaximo, boolean tienePendienteVigente) {
        static final HistorialPar VACIO = new HistorialPar(false, 0, 0, false);
    }
}
