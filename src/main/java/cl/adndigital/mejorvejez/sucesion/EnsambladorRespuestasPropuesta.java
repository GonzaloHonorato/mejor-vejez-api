package cl.adndigital.mejorvejez.sucesion;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.tareas.EnsambladorRespuestasTarea;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.RespuestaTarea;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Convierte {@link PropuestaSucesion} en {@link RespuestaPropuesta} (regla
 * 24 de la Fase 7). Lo usan {@link ServicioSucesion} y el servicio de
 * completar de la Tarea 4, para no duplicar el ensamblado.
 *
 * <p><b>Condiciones pendientes (regla 19).</b> Solo si la propuesta está
 * {@code PENDIENTE} o {@code ACEPTADA}: ids con {@link
 * RepositorioConsultaSucesion#idsCondicionesPendientes} → {@code
 * findAllById} → {@link EnsambladorRespuestasTarea#ensamblar(List)} (sin
 * N+1), conservando el orden de la consulta ({@code fechaHorario} y luego
 * {@code idTarea}). En cualquier otro estado la lista va vacía.
 *
 * <p>Debe llamarse dentro de la transacción del servicio ({@code
 * open-in-view=false}).
 */
@Service
public class EnsambladorRespuestasPropuesta {

    private static final List<String> ESTADOS = List.of(CodigosSucesion.ESTADO_PENDIENTE,
            CodigosSucesion.ESTADO_ACEPTADA, CodigosSucesion.ESTADO_RECHAZADA, CodigosSucesion.ESTADO_CANCELADA,
            CodigosSucesion.ESTADO_COMPLETADA);

    private final RepositorioConsultaSucesion repositorioConsulta;
    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioUsuario repositorioUsuario;
    private final EnsambladorRespuestasTarea ensambladorTareas;
    private final ServicioCatalogos servicioCatalogos;

    EnsambladorRespuestasPropuesta(RepositorioConsultaSucesion repositorioConsulta,
            RepositorioTareaCuidado repositorioTarea, RepositorioUsuario repositorioUsuario,
            EnsambladorRespuestasTarea ensambladorTareas, ServicioCatalogos servicioCatalogos) {
        this.repositorioConsulta = repositorioConsulta;
        this.repositorioTarea = repositorioTarea;
        this.repositorioUsuario = repositorioUsuario;
        this.ensambladorTareas = ensambladorTareas;
        this.servicioCatalogos = servicioCatalogos;
    }

    /** La propuesta con sus condiciones pendientes calculadas en vivo (vacías si no está activa). */
    public RespuestaPropuesta ensamblar(PropuestaSucesion propuesta) {
        List<Long> idsCondiciones = estaActiva(propuesta)
                ? repositorioConsulta.idsCondicionesPendientes(propuesta.getIdGrupo(),
                        propuesta.getIdUsuarioSaliente())
                : List.of();
        return ensamblar(propuesta, idsCondiciones);
    }

    /**
     * Igual que {@link #ensamblar(PropuestaSucesion)}, con los ids de las
     * condiciones ya calculados por el llamador (p. ej. {@code aceptar}, que
     * también los audita), para no repetir la consulta. Si la propuesta no
     * está activa, los ids se ignoran y la lista va vacía.
     */
    RespuestaPropuesta ensamblar(PropuestaSucesion propuesta, List<Long> idsCondiciones) {
        Map<Long, String> nombres = nombresUsuarioPorId(
                List.of(propuesta.getIdUsuarioSaliente(), propuesta.getIdUsuarioCandidato()));
        List<RespuestaTarea> condiciones = estaActiva(propuesta) ? condiciones(idsCondiciones) : List.of();
        return construir(propuesta, nombres, codigosEstado(), condiciones);
    }

    /** Varias propuestas a la vez, sin condiciones (historial, regla 15), resolviendo los nombres en lote. */
    List<RespuestaPropuesta> ensamblarSinCondiciones(List<PropuestaSucesion> propuestas) {
        if (propuestas.isEmpty()) {
            return List.of();
        }
        Set<Long> idsUsuario = new HashSet<>();
        propuestas.forEach(propuesta -> {
            idsUsuario.add(propuesta.getIdUsuarioSaliente());
            idsUsuario.add(propuesta.getIdUsuarioCandidato());
        });
        Map<Long, String> nombres = nombresUsuarioPorId(idsUsuario);
        Map<Long, String> codigos = codigosEstado();
        return propuestas.stream().map(propuesta -> construir(propuesta, nombres, codigos, List.of())).toList();
    }

    /** {@code true} si la propuesta está {@code PENDIENTE} o {@code ACEPTADA}. */
    boolean estaActiva(PropuestaSucesion propuesta) {
        return Objects.equals(propuesta.getIdEstado(), idEstado(CodigosSucesion.ESTADO_PENDIENTE))
                || Objects.equals(propuesta.getIdEstado(), idEstado(CodigosSucesion.ESTADO_ACEPTADA));
    }

    private List<RespuestaTarea> condiciones(List<Long> idsTarea) {
        if (idsTarea.isEmpty()) {
            return List.of();
        }
        Map<Long, TareaCuidado> tareasPorId = repositorioTarea.findAllById(idsTarea).stream()
                .collect(Collectors.toMap(TareaCuidado::getIdTarea, Function.identity()));
        List<TareaCuidado> ordenadas = idsTarea.stream().map(tareasPorId::get).filter(Objects::nonNull).toList();
        return ensambladorTareas.ensamblar(ordenadas);
    }

    private RespuestaPropuesta construir(PropuestaSucesion propuesta, Map<Long, String> nombres,
            Map<Long, String> codigosEstado, List<RespuestaTarea> condiciones) {
        return new RespuestaPropuesta(propuesta.getIdPropuesta(), propuesta.getIdGrupo(),
                persona(propuesta.getIdUsuarioSaliente(), nombres),
                persona(propuesta.getIdUsuarioCandidato(), nombres), propuesta.getIdEstado(),
                codigosEstado.get(propuesta.getIdEstado()), propuesta.getFechaCreacion(),
                propuesta.getFechaRespuesta(), propuesta.getFechaCancelacion(), propuesta.getFechaCompletado(),
                propuesta.getMotivoCancelacion(), condiciones);
    }

    private RespuestaPersona persona(Long idUsuario, Map<Long, String> nombres) {
        return new RespuestaPersona(idUsuario, nombres.get(idUsuario));
    }

    private Map<Long, String> nombresUsuarioPorId(Collection<Long> idsUsuario) {
        return repositorioUsuario.findAllById(new HashSet<>(idsUsuario)).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Usuario::getNombre));
    }

    /**
     * {@code ID_ESTADO} → {@code CODIGO_ESTADO} de {@code
     * PROPUESTA_SUCESION.ESTADO}, armado con {@link ServicioCatalogos#idEstado}
     * (en caché) a partir de los cinco códigos conocidos, sin consultar la BD.
     */
    private Map<Long, String> codigosEstado() {
        return ESTADOS.stream().collect(Collectors.toMap(this::idEstado, Function.identity()));
    }

    private long idEstado(String codigo) {
        return servicioCatalogos.idEstado(CodigosSucesion.TABLA_PROPUESTA_SUCESION, CodigosSucesion.CAMPO_ESTADO,
                codigo);
    }
}
