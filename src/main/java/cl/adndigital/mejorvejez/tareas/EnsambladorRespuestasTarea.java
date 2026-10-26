package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.planificacion.Medicamento;
import cl.adndigital.mejorvejez.planificacion.RepositorioMedicamento;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Convierte {@link TareaCuidado} (y su historial/asignaciones) en las
 * respuestas HTTP de la agenda y el detalle (HU-05/06/07). Lo usan {@link
 * ServicioConsultaTareas} y las Tareas 6–8 (confirmar, asignar, tareas
 * manuales) para no duplicar el ensamblado.
 *
 * <p><b>Sin N+1.</b> {@link #ensamblar(List)} resuelve adultos, asignaciones
 * {@code ACTIVO}, usuarios (responsables y confirmadores), líneas de compra
 * (con el nombre de su medicamento) y citas, cada uno en una sola consulta
 * por lote ({@code findAllById} / {@code IN}), sin importar cuántas tareas se
 * ensamblen. {@link #detallar(TareaCuidado)} hace lo mismo para el historial
 * y las asignaciones de una tarea puntual.
 *
 * <p><b>Atrasada (regla 12 de la Fase 4).</b> Una tarea está atrasada si está
 * {@code PENDIENTE} o {@code EN_CURSO} y {@code ahora > fechaHorario +
 * minutosTolerancia}; se calcula con el {@code Clock} de negocio inyectado,
 * nunca con la hora del servidor.
 */
@Service
public class EnsambladorRespuestasTarea {

    private static final String CAMPO_ESTADO = CodigosTareas.CAMPO_ESTADO;

    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioAsignacionTarea repositorioAsignacion;
    private final RepositorioHistorialEstadoTarea repositorioHistorial;
    private final RepositorioDetalleCompraMedicamento repositorioDetalleCompra;
    private final RepositorioDetalleCitaMedica repositorioDetalleCita;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioMedicamento repositorioMedicamento;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;

    public EnsambladorRespuestasTarea(RepositorioAdultoMayor repositorioAdulto,
            RepositorioAsignacionTarea repositorioAsignacion, RepositorioHistorialEstadoTarea repositorioHistorial,
            RepositorioDetalleCompraMedicamento repositorioDetalleCompra,
            RepositorioDetalleCitaMedica repositorioDetalleCita, RepositorioUsuario repositorioUsuario,
            RepositorioMedicamento repositorioMedicamento, ServicioCatalogos servicioCatalogos, Clock reloj) {
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioAsignacion = repositorioAsignacion;
        this.repositorioHistorial = repositorioHistorial;
        this.repositorioDetalleCompra = repositorioDetalleCompra;
        this.repositorioDetalleCita = repositorioDetalleCita;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioMedicamento = repositorioMedicamento;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
    }

    /** Ensambla varias tareas a la vez, sin N+1 (ver la documentación de la clase). */
    public List<RespuestaTarea> ensamblar(List<TareaCuidado> tareas) {
        if (tareas.isEmpty()) {
            return List.of();
        }

        List<Long> idsTarea = tareas.stream().map(TareaCuidado::getIdTarea).toList();
        Map<Long, String> nombresAdulto = nombresAdultoPorId(
                tareas.stream().map(TareaCuidado::getIdAdultoMayor).distinct().toList());
        Map<Long, AsignacionTarea> asignacionesActivas = asignacionActivaPorTarea(idsTarea);
        Map<Long, List<DetalleCompraMedicamento>> comprasPorTarea = comprasPorTarea(idsTarea);
        Map<Long, DetalleCitaMedica> citasPorTarea = citasPorTarea(idsTarea);

        Set<Long> idsUsuario = new HashSet<>();
        asignacionesActivas.values().forEach(asignacion -> idsUsuario.add(asignacion.getIdUsuarioAsignado()));
        tareas.forEach(tarea -> {
            if (tarea.getIdUsuarioConfirmador() != null) {
                idsUsuario.add(tarea.getIdUsuarioConfirmador());
            }
        });
        Map<Long, String> nombresUsuario = nombresUsuarioPorId(idsUsuario);

        Set<Long> idsMedicamento = comprasPorTarea.values().stream().flatMap(List::stream)
                .map(DetalleCompraMedicamento::getIdMedicamento).collect(Collectors.toSet());
        Map<Long, String> nombresMedicamento = nombresMedicamentoPorId(idsMedicamento);

        long idPendiente = idEstadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idEnCurso = idEstadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);

        return tareas.stream()
                .map(tarea -> construir(tarea, nombresAdulto, asignacionesActivas, nombresUsuario, comprasPorTarea,
                        nombresMedicamento, citasPorTarea, idPendiente, idEnCurso, ahora))
                .toList();
    }

    /** Ensambla una sola tarea (conveniencia sobre {@link #ensamblar(List)}). */
    public RespuestaTarea ensamblar(TareaCuidado tarea) {
        return ensamblar(List.of(tarea)).get(0);
    }

    /** La tarea ensamblada, con su historial de estados y sus asignaciones, ambos en orden cronológico. */
    public RespuestaTareaDetallada detallar(TareaCuidado tarea) {
        RespuestaTarea respuestaTarea = ensamblar(tarea);

        List<HistorialEstadoTarea> historial = repositorioHistorial
                .findByIdTareaOrderByFechaCambioAscIdHistorialAsc(tarea.getIdTarea());
        List<AsignacionTarea> asignaciones = repositorioAsignacion
                .findByIdTareaOrderByFechaAsignacionAscIdAsignacionAsc(tarea.getIdTarea());

        Set<Long> idsUsuario = new HashSet<>();
        historial.forEach(fila -> idsUsuario.add(fila.getIdUsuarioCambio()));
        asignaciones.forEach(asignacion -> {
            idsUsuario.add(asignacion.getIdUsuarioAsignado());
            idsUsuario.add(asignacion.getIdUsuarioAsignador());
        });
        Map<Long, String> nombresUsuario = nombresUsuarioPorId(idsUsuario);

        List<RespuestaHistorialEstado> respuestaHistorial = historial.stream()
                .map(fila -> new RespuestaHistorialEstado(fila.getIdEstadoAnterior(), fila.getIdEstadoNuevo(),
                        nombresUsuario.get(fila.getIdUsuarioCambio()), fila.getFechaCambio(), fila.getObservacion()))
                .toList();
        List<RespuestaAsignacion> respuestaAsignaciones = asignaciones.stream()
                .map(asignacion -> new RespuestaAsignacion(asignacion.getIdAsignacion(),
                        asignacion.getIdUsuarioAsignado(), nombresUsuario.get(asignacion.getIdUsuarioAsignado()),
                        nombresUsuario.get(asignacion.getIdUsuarioAsignador()), asignacion.getFechaAsignacion(),
                        asignacion.getFechaTermino(), asignacion.getIdEstado(), asignacion.getMotivo()))
                .toList();

        return new RespuestaTareaDetallada(respuestaTarea, respuestaHistorial, respuestaAsignaciones);
    }

    private RespuestaTarea construir(TareaCuidado tarea, Map<Long, String> nombresAdulto,
            Map<Long, AsignacionTarea> asignacionesActivas, Map<Long, String> nombresUsuario,
            Map<Long, List<DetalleCompraMedicamento>> comprasPorTarea, Map<Long, String> nombresMedicamento,
            Map<Long, DetalleCitaMedica> citasPorTarea, long idPendiente, long idEnCurso, OffsetDateTime ahora) {

        AsignacionTarea asignacionActiva = asignacionesActivas.get(tarea.getIdTarea());
        RespuestaResponsable responsable = asignacionActiva == null ? null
                : new RespuestaResponsable(asignacionActiva.getIdUsuarioAsignado(),
                        nombresUsuario.get(asignacionActiva.getIdUsuarioAsignado()));

        String nombreConfirmador = tarea.getIdUsuarioConfirmador() == null ? null
                : nombresUsuario.get(tarea.getIdUsuarioConfirmador());

        List<RespuestaLineaCompra> compra = comprasPorTarea.getOrDefault(tarea.getIdTarea(), List.of()).stream()
                .map(linea -> new RespuestaLineaCompra(linea.getIdDetalleCompra(), linea.getIdMedicamento(),
                        nombresMedicamento.get(linea.getIdMedicamento()), linea.getIdPresentacion(),
                        linea.getDosisIndicada(), linea.getCantidadSolicitada(), linea.getIdUnidadCantidad(),
                        linea.getObservaciones()))
                .toList();

        DetalleCitaMedica detalleCita = citasPorTarea.get(tarea.getIdTarea());
        RespuestaCita cita = detalleCita == null ? null
                : new RespuestaCita(detalleCita.getNombreMedico(), detalleCita.getLugarAtencion(),
                        detalleCita.getObservaciones());

        boolean atrasada = estaAtrasada(tarea, idPendiente, idEnCurso, ahora);

        return new RespuestaTarea(tarea.getIdTarea(), tarea.getTipoTarea(), tarea.getIdAdultoMayor(),
                nombresAdulto.get(tarea.getIdAdultoMayor()), tarea.getIdDetallePlan(), tarea.getTituloTarea(),
                tarea.getDescripcionTarea(), tarea.getFechaHorario(), tarea.getMinutosTolerancia(),
                tarea.getIdEstado(), atrasada, responsable, tarea.getObservacionCierre(), nombreConfirmador,
                tarea.getFechaConfirmacion(), tarea.getMotivoCancelacion(), compra, cita);
    }

    /** Regla 12 de la Fase 4: {@code PENDIENTE} o {@code EN_CURSO} y {@code ahora > fechaHorario + minutosTolerancia}. */
    private boolean estaAtrasada(TareaCuidado tarea, long idPendiente, long idEnCurso, OffsetDateTime ahora) {
        boolean enEstadoAtrasable = tarea.getIdEstado() == idPendiente || tarea.getIdEstado() == idEnCurso;
        if (!enEstadoAtrasable) {
            return false;
        }
        OffsetDateTime limite = tarea.getFechaHorario().plusMinutes(tarea.getMinutosTolerancia());
        return ahora.isAfter(limite);
    }

    private Map<Long, String> nombresAdultoPorId(Collection<Long> idsAdulto) {
        if (idsAdulto.isEmpty()) {
            return Map.of();
        }
        return repositorioAdulto.findAllById(idsAdulto).stream()
                .collect(Collectors.toMap(AdultoMayor::getIdAdultoMayor, AdultoMayor::getNombreCompleto));
    }

    private Map<Long, String> nombresUsuarioPorId(Collection<Long> idsUsuario) {
        if (idsUsuario.isEmpty()) {
            return Map.of();
        }
        return repositorioUsuario.findAllById(idsUsuario).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Usuario::getNombre));
    }

    private Map<Long, String> nombresMedicamentoPorId(Collection<Long> idsMedicamento) {
        if (idsMedicamento.isEmpty()) {
            return Map.of();
        }
        return repositorioMedicamento.findAllById(idsMedicamento).stream()
                .collect(Collectors.toMap(Medicamento::getIdMedicamento, Medicamento::getNombre));
    }

    private Map<Long, AsignacionTarea> asignacionActivaPorTarea(Collection<Long> idsTarea) {
        long idActivo = servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CAMPO_ESTADO,
                CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
        return repositorioAsignacion.findByIdTareaInAndIdEstado(idsTarea, idActivo).stream()
                .collect(Collectors.toMap(AsignacionTarea::getIdTarea, Function.identity()));
    }

    private Map<Long, List<DetalleCompraMedicamento>> comprasPorTarea(Collection<Long> idsTarea) {
        return repositorioDetalleCompra.findByIdTareaInOrderByIdDetalleCompra(idsTarea).stream()
                .collect(Collectors.groupingBy(DetalleCompraMedicamento::getIdTarea, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private Map<Long, DetalleCitaMedica> citasPorTarea(Collection<Long> idsTarea) {
        return repositorioDetalleCita.findAllById(idsTarea).stream()
                .collect(Collectors.toMap(DetalleCitaMedica::getIdTarea, Function.identity()));
    }

    private long idEstadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CAMPO_ESTADO, codigo);
    }
}
