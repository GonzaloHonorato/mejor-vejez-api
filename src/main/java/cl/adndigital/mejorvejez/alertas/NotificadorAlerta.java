package cl.adndigital.mejorvejez.alertas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.notificaciones.CanalOperativo;
import cl.adndigital.mejorvejez.notificaciones.EnviadorNotificacion;
import cl.adndigital.mejorvejez.notificaciones.MensajeNotificacion;
import cl.adndigital.mejorvejez.notificaciones.ResultadoEnvio;
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
 * Notifica UNA alerta en su propia transacción ({@code REQUIRES_NEW}, regla
 * 10 de la Fase 5). Vive en un bean aparte de {@link
 * ServicioNotificacionAlertas} para que el proxy transaccional aplique
 * cuando {@link ServicioNotificacionAlertas#notificarPendientes} la llama
 * en su recorrido (mismo patrón que {@code tareas.CreadorTareas}).
 *
 * <p>Bloquea la alerta ({@code SELECT ... FOR UPDATE}), calcula los
 * destinatarios en el momento (regla 7), sus canales operativos (regla 8) y,
 * por cada par (destinatario, canal), envía y guarda el intento con su
 * resultado (regla 9), todo en la misma transacción. El bloqueo se mantiene
 * durante el envío (aceptable en el MVP): así dos corridas simultáneas no
 * reenvían el mismo par. No toca la tarea ni el estado de la alerta.
 *
 * <p>Tras bloquear la alerta revisa que su origen siga vigente (revisión
 * final de la Fase 5): si la tarea ya no está PENDIENTE o EN_CURSO, si el
 * detalle o el plan de la reposición están inactivos, o si el adulto o el
 * grupo lo están, no envía ni registra intentos y la alerta sigue ABIERTA
 * (no cuenta como "sin destinatario"). El origen solo se lee, no se
 * bloquea. Si un enviador lanza, el par queda con un intento FALLIDO
 * ({@code ClaseSimple}, sin el mensaje ni el destino) y se sigue con el
 * siguiente, sin deshacer los ENVIADO previos de la misma alerta.
 */
@Component
class NotificadorAlerta {

    private final RepositorioAlertaCuidado repositorioAlertas;
    private final RepositorioIntentoNotificacion repositorioIntentos;
    private final RepositorioConsultaAlertas consultaAlertas;
    private final RepositorioDetallePlan repositorioDetalles;
    private final RepositorioTareaCuidado repositorioTareas;
    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final VerificadorVigencia verificador;
    private final RepositorioUsuario repositorioUsuarios;
    private final ServicioCanales servicioCanales;
    private final List<EnviadorNotificacion> enviadores;
    private final RedactorCorreoAlertas redactor;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;
    private final int maximoIntentos;

    NotificadorAlerta(RepositorioAlertaCuidado repositorioAlertas, RepositorioIntentoNotificacion repositorioIntentos,
            RepositorioConsultaAlertas consultaAlertas, RepositorioDetallePlan repositorioDetalles,
            RepositorioTareaCuidado repositorioTareas, RepositorioPlanMedicamentos repositorioPlanes,
            VerificadorVigencia verificador, RepositorioUsuario repositorioUsuarios, ServicioCanales servicioCanales,
            List<EnviadorNotificacion> enviadores, RedactorCorreoAlertas redactor, ServicioCatalogos servicioCatalogos,
            Clock reloj, @Value("${mejorvejez.notificaciones.max-intentos}") int maximoIntentos) {
        this.repositorioAlertas = repositorioAlertas;
        this.repositorioIntentos = repositorioIntentos;
        this.consultaAlertas = consultaAlertas;
        this.repositorioDetalles = repositorioDetalles;
        this.repositorioTareas = repositorioTareas;
        this.repositorioPlanes = repositorioPlanes;
        this.verificador = verificador;
        this.repositorioUsuarios = repositorioUsuarios;
        this.servicioCanales = servicioCanales;
        this.enviadores = enviadores;
        this.redactor = redactor;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
        this.maximoIntentos = maximoIntentos;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultadoNotificacion notificar(long idAlerta) {
        AlertaCuidado alerta = repositorioAlertas.bloquearPorId(idAlerta)
                .orElseThrow(() -> new NoSuchElementException("La alerta " + idAlerta + " no existe."));
        long idEstadoCerrada = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_CERRADA);
        if (alerta.getIdEstado().longValue() == idEstadoCerrada) {
            return ResultadoNotificacion.vacio();
        }
        if (!origenVigente(alerta)) {
            return ResultadoNotificacion.vacio();
        }

        List<Usuario> destinatarios = repositorioUsuarios.findAllById(destinatarios(alerta)).stream()
                .sorted(Comparator.comparing(Usuario::getIdUsuario))
                .toList();
        Map<Long, List<CanalOperativo>> canales = servicioCanales.canalesOperativos(destinatarios);
        Map<String, EnviadorNotificacion> enviadoresPorCanal = enviadoresPorCanal();

        boolean algunCanal = destinatarios.stream()
                .anyMatch(u -> !canales.getOrDefault(u.getIdUsuario(), List.of()).isEmpty());
        if (!algunCanal) {
            return new ResultadoNotificacion(0, 0, List.of(alerta.getIdAlerta()), List.of());
        }

        Map<Par, HistorialPar> historial = historialPorPar(alerta.getIdAlerta());
        long idEstadoEnviado = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        long idEstadoFallido = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_FALLIDO);
        int enviados = 0;
        int fallidos = 0;

        for (Usuario usuario : destinatarios) {
            for (CanalOperativo canal : canales.getOrDefault(usuario.getIdUsuario(), List.of())) {
                EnviadorNotificacion enviador = enviadoresPorCanal.get(canal.canal());
                if (enviador == null) {
                    continue;
                }
                HistorialPar previo = historial.getOrDefault(new Par(usuario.getIdUsuario(), canal.canal()),
                        HistorialPar.VACIO);
                if (previo.enviado() || previo.cantidad() >= maximoIntentos) {
                    continue;
                }
                MensajeNotificacion mensaje = redactor.redactar(alerta, usuario.getNombre(), canal.destino());
                ResultadoEnvio resultado;
                try {
                    resultado = enviador.enviar(mensaje);
                } catch (RuntimeException e) {
                    // Solo la clase: el mensaje de la excepción podría traer el destino.
                    resultado = ResultadoEnvio.fallido(e.getClass().getSimpleName());
                }
                repositorioIntentos.save(new IntentoNotificacion(alerta.getIdAlerta(), usuario.getIdUsuario(),
                        canal.canal(), previo.numeroMaximo() + 1, resultado.exito() ? idEstadoEnviado : idEstadoFallido,
                        OffsetDateTime.now(reloj), resultado.detalleError(), resultado.referenciaProveedor()));
                if (resultado.exito()) {
                    enviados++;
                } else {
                    fallidos++;
                }
            }
        }
        return new ResultadoNotificacion(enviados, fallidos, List.of(), List.of());
    }

    /**
     * Si el origen de la alerta sigue vigente. Tarea (atraso o cita):
     * PENDIENTE o EN_CURSO, con el adulto y el grupo ACTIVO. Reposición: el
     * detalle y su plan ACTIVO, con el adulto y el grupo ACTIVO. Solo lee.
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

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    /**
     * Destinatarios de la alerta (regla 7), deduplicados y por id
     * ascendente. Tarea (atraso o cita): el responsable con asignación
     * ACTIVO si es elegible, más los coordinadores si el grupo lo pide o si
     * no hay responsable elegible. Reposición: el responsable predeterminado
     * del detalle si es elegible, más los coordinadores siempre.
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

    private Map<Par, HistorialPar> historialPorPar(long idAlerta) {
        long idEstadoEnviado = idEstadoEnvio(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        Map<Par, HistorialPar> historial = new HashMap<>();
        for (IntentoNotificacion intento : repositorioIntentos.findByIdAlertaOrderByFechaIntentoAscIdIntentoAsc(idAlerta)) {
            Par par = new Par(intento.getIdUsuarioDestinatario(), intento.getCanal());
            HistorialPar previo = historial.getOrDefault(par, HistorialPar.VACIO);
            historial.put(par, new HistorialPar(
                    previo.enviado() || intento.getIdEstadoEnvio().longValue() == idEstadoEnviado,
                    previo.cantidad() + 1,
                    Math.max(previo.numeroMaximo(), intento.getNumeroIntento())));
        }
        return historial;
    }

    /** Enviadores por {@code canal()}, resueltos al momento de notificar. */
    private Map<String, EnviadorNotificacion> enviadoresPorCanal() {
        Map<String, EnviadorNotificacion> porCanal = new HashMap<>();
        for (EnviadorNotificacion enviador : enviadores) {
            porCanal.put(enviador.canal(), enviador);
        }
        return porCanal;
    }

    private long idEstadoAlerta(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoEnvio(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_INTENTO_NOTIFICACION,
                CodigosAlertas.CAMPO_ESTADO_ENVIO, codigo);
    }

    /** Par (destinatario, canal) de la regla 9; {@code long} para comparar ids por valor. */
    private record Par(long idUsuario, String canal) {
    }

    /** Lo que ya pasó con un par: si tiene un intento ENVIADO, cuántos intentos tiene y su número máximo. */
    private record HistorialPar(boolean enviado, int cantidad, int numeroMaximo) {
        static final HistorialPar VACIO = new HistorialPar(false, 0, 0);
    }
}
