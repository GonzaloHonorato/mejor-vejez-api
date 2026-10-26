package cl.adndigital.mejorvejez.alertas;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Convierte {@link AlertaCuidado} (y sus {@link IntentoNotificacion}) en las
 * respuestas HTTP de la bandeja y el detalle (TSA-47). Lo usan {@link
 * ServicioConsultaAlertas} y {@link ServicioCierreAlertas} para no duplicar
 * el ensamblado.
 *
 * <p><b>Sin N+1.</b> {@link #ensamblar(List)} resuelve adultos, tareas (para
 * {@code tituloTarea}/{@code idEstadoTarea}), detalles del plan (para
 * {@code nombreMedicamento}, {@code DetallePlan.nombreMedicamento}, la misma
 * fuente que {@code tareas.EnsambladorRespuestasTarea}) y revisores, cada uno
 * en una sola consulta por lote ({@code findAllById}), sin importar cuántas
 * alertas se ensamblen. {@link #detallar} hace lo mismo para los nombres de
 * los destinatarios de los intentos.
 */
@Service
public class EnsambladorRespuestasAlerta {

    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioDetallePlan repositorioDetallePlan;
    private final RepositorioUsuario repositorioUsuario;

    public EnsambladorRespuestasAlerta(RepositorioAdultoMayor repositorioAdulto,
            RepositorioTareaCuidado repositorioTarea, RepositorioDetallePlan repositorioDetallePlan,
            RepositorioUsuario repositorioUsuario) {
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioTarea = repositorioTarea;
        this.repositorioDetallePlan = repositorioDetallePlan;
        this.repositorioUsuario = repositorioUsuario;
    }

    /** Ensambla varias alertas a la vez, sin N+1 (ver la documentación de la clase). */
    public List<RespuestaAlerta> ensamblar(List<AlertaCuidado> alertas) {
        if (alertas.isEmpty()) {
            return List.of();
        }

        Map<Long, String> nombresAdulto = nombresAdultoPorId(
                alertas.stream().map(AlertaCuidado::getIdAdultoMayor).distinct().toList());

        List<Long> idsTarea = alertas.stream().map(AlertaCuidado::getIdTarea).filter(Objects::nonNull).distinct()
                .toList();
        Map<Long, TareaCuidado> tareasPorId = tareasPorId(idsTarea);

        List<Long> idsDetalle = alertas.stream().map(AlertaCuidado::getIdDetallePlan).filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, DetallePlan> detallesPorId = detallesPorId(idsDetalle);

        Set<Long> idsRevisor = new HashSet<>();
        alertas.forEach(alerta -> {
            if (alerta.getIdUsuarioRevisor() != null) {
                idsRevisor.add(alerta.getIdUsuarioRevisor());
            }
        });
        Map<Long, String> nombresRevisor = nombresUsuarioPorId(idsRevisor);

        return alertas.stream()
                .map(alerta -> construir(alerta, nombresAdulto, tareasPorId, detallesPorId, nombresRevisor))
                .toList();
    }

    /** Ensambla una sola alerta (conveniencia sobre {@link #ensamblar(List)}). */
    public RespuestaAlerta ensamblar(AlertaCuidado alerta) {
        return ensamblar(List.of(alerta)).get(0);
    }

    /**
     * El detalle de la alerta, con sus intentos de notificación en el orden
     * en que se enviaron. {@code incluyeDetalleError} decide si el {@code
     * detalleError} de cada intento viaja o llega {@code null} (regla 14 de
     * la Fase 5: solo coordinador o administrador lo ven).
     */
    public RespuestaAlertaDetallada detallar(AlertaCuidado alerta, List<IntentoNotificacion> intentos,
            boolean incluyeDetalleError) {
        RespuestaAlerta respuestaAlerta = ensamblar(alerta);

        Set<Long> idsDestinatario = intentos.stream().map(IntentoNotificacion::getIdUsuarioDestinatario)
                .collect(Collectors.toSet());
        Map<Long, String> nombresDestinatario = nombresUsuarioPorId(idsDestinatario);

        List<RespuestaIntento> respuestaIntentos = intentos.stream()
                .map(intento -> new RespuestaIntento(intento.getIdIntento(), intento.getIdUsuarioDestinatario(),
                        nombresDestinatario.get(intento.getIdUsuarioDestinatario()), intento.getCanal(),
                        intento.getNumeroIntento(), intento.getIdEstadoEnvio(), intento.getFechaIntento(),
                        incluyeDetalleError ? intento.getDetalleError() : null))
                .toList();

        return new RespuestaAlertaDetallada(respuestaAlerta, respuestaIntentos);
    }

    private RespuestaAlerta construir(AlertaCuidado alerta, Map<Long, String> nombresAdulto,
            Map<Long, TareaCuidado> tareasPorId, Map<Long, DetallePlan> detallesPorId,
            Map<Long, String> nombresRevisor) {
        TareaCuidado tarea = alerta.getIdTarea() == null ? null : tareasPorId.get(alerta.getIdTarea());
        DetallePlan detalle = alerta.getIdDetallePlan() == null ? null : detallesPorId.get(alerta.getIdDetallePlan());

        String tituloTarea = tarea == null ? null : tarea.getTituloTarea();
        Long idEstadoTarea = tarea == null ? null : tarea.getIdEstado();
        String nombreMedicamento = detalle == null ? null : detalle.getNombreMedicamento();

        RespuestaRevisor revisor = alerta.getIdUsuarioRevisor() == null ? null
                : new RespuestaRevisor(alerta.getIdUsuarioRevisor(),
                        nombresRevisor.get(alerta.getIdUsuarioRevisor()));

        return new RespuestaAlerta(alerta.getIdAlerta(), alerta.getTipoAlerta(), alerta.getIdTarea(), tituloTarea,
                idEstadoTarea, alerta.getIdDetallePlan(), nombreMedicamento, alerta.getIdAdultoMayor(),
                nombresAdulto.get(alerta.getIdAdultoMayor()), alerta.getFechaProgramada(), alerta.getMensajeAlerta(),
                alerta.getIdEstado(), alerta.getFechaDeteccion(), revisor, alerta.getFechaRevision(),
                alerta.getObservacionCierre());
    }

    private Map<Long, String> nombresAdultoPorId(Collection<Long> idsAdulto) {
        if (idsAdulto.isEmpty()) {
            return Map.of();
        }
        return repositorioAdulto.findAllById(idsAdulto).stream()
                .collect(Collectors.toMap(AdultoMayor::getIdAdultoMayor, AdultoMayor::getNombreCompleto));
    }

    private Map<Long, TareaCuidado> tareasPorId(Collection<Long> idsTarea) {
        if (idsTarea.isEmpty()) {
            return Map.of();
        }
        return repositorioTarea.findAllById(idsTarea).stream()
                .collect(Collectors.toMap(TareaCuidado::getIdTarea, Function.identity()));
    }

    private Map<Long, DetallePlan> detallesPorId(Collection<Long> idsDetalle) {
        if (idsDetalle.isEmpty()) {
            return Map.of();
        }
        return repositorioDetallePlan.findAllById(idsDetalle).stream()
                .collect(Collectors.toMap(DetallePlan::getIdDetallePlan, Function.identity()));
    }

    private Map<Long, String> nombresUsuarioPorId(Collection<Long> idsUsuario) {
        if (idsUsuario.isEmpty()) {
            return Map.of();
        }
        return repositorioUsuario.findAllById(idsUsuario).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Usuario::getNombre));
    }
}
