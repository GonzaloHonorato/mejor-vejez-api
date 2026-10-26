package cl.adndigital.mejorvejez.panel;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.adultos.ServicioAdultosMayores;
import cl.adndigital.mejorvejez.alertas.AlertaCuidado;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.EnsambladorRespuestasAlerta;
import cl.adndigital.mejorvejez.alertas.RepositorioAlertaCuidado;
import cl.adndigital.mejorvejez.alertas.RespuestaAlerta;
import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.EnsambladorRespuestasTarea;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.RespuestaTarea;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;

/**
 * Panel familiar de solo lectura (HU-10, Fase 6): tareas de hoy, próximos
 * horarios y citas (Tarea 1); alertas de atraso y alertas preventivas
 * (Tarea 2). Cada método exige solo membresía activa del grupo ({@link
 * ServicioAutorizacion#exigirMiembro(long)}) y es {@code
 * @Transactional(readOnly = true)}: nunca llama a un servicio que mute
 * (regla 7 de la Fase 6; hay una prueba que lo verifica sobre tareas y
 * alertas, su estado, sus intentos de notificación y los eventos de
 * auditoría del grupo).
 *
 * <p><b>Auditoría (regla 8, TSA-10/TSA-49).</b> Solo {@link #tareas} audita,
 * y solo cuando el actor es administrador sin membresía en el grupo ({@code
 * contexto.rol() == null}); {@link #proximosHorarios}, {@link #citas},
 * {@link #alertas} y {@link #alertasPreventivas} nunca auditan, para no
 * multiplicar por sección cada carga o refresco del panel. Los miembros
 * nunca se auditan.
 *
 * <p><b>Orden tras {@code findAllById}.</b> {@link #ensamblarEnOrden} y
 * {@link #ensamblarAlertasEnOrden} reordenan las tareas/alertas según la
 * lista de ids que ya viene ordenada por SQL (regla del plan de la Fase 6):
 * {@code findAllById} no garantiza el orden.
 */
@Service
public class ServicioPanel {

    private final ServicioAutorizacion servicioAutorizacion;
    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioAlertaCuidado repositorioAlerta;
    private final RepositorioConsultaPanel repositorioConsulta;
    private final EnsambladorRespuestasTarea ensamblador;
    private final EnsambladorRespuestasAlerta ensambladorAlertas;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioPanel(ServicioAutorizacion servicioAutorizacion, RepositorioAdultoMayor repositorioAdulto,
            RepositorioTareaCuidado repositorioTarea, RepositorioAlertaCuidado repositorioAlerta,
            RepositorioConsultaPanel repositorioConsulta, EnsambladorRespuestasTarea ensamblador,
            EnsambladorRespuestasAlerta ensambladorAlertas, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.servicioAutorizacion = servicioAutorizacion;
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioTarea = repositorioTarea;
        this.repositorioAlerta = repositorioAlerta;
        this.repositorioConsulta = repositorioConsulta;
        this.ensamblador = ensamblador;
        this.ensambladorAlertas = ensambladorAlertas;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Resumen de tareas de hoy (regla 2 de la Fase 6): conteos por estado
     * del día en curso (America/Santiago) y las tareas atrasadas (PENDIENTE
     * o EN_CURSO, vencidas, de hasta {@value
     * CodigosPanel#DIAS_RETROSPECTIVA_ATRASADAS} días atrás), las {@value
     * CodigosPanel#LIMITE_LISTAS} más antiguas primero. Audita {@code
     * CONSULTAR_PANEL} si el actor es administrador sin membresía (regla 8).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPanelTareas tareas(long idGrupo, Long idAdulto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        if (idAdulto != null) {
            validarAdultoDelGrupo(idGrupo, idAdulto);
        }
        auditarConsulta(contexto, idGrupo, idAdulto);

        LocalDate hoy = LocalDate.now(reloj.withZone(ZonaNegocio.ZONA));
        OffsetDateTime inicioHoy = hoy.atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime inicioManana = hoy.plusDays(1).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime desdeAtrasadas = hoy.minusDays(CodigosPanel.DIAS_RETROSPECTIVA_ATRASADAS)
                .atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();

        Map<Long, Integer> conteos = repositorioConsulta.conteoPorEstado(idGrupo, idAdulto, inicioHoy, inicioManana);
        int atrasadas = repositorioConsulta.contarAtrasadas(idGrupo, idAdulto, desdeAtrasadas, ahora);
        List<Long> idsAtrasadas = repositorioConsulta.idsAtrasadas(idGrupo, idAdulto, desdeAtrasadas, ahora,
                CodigosPanel.LIMITE_LISTAS);

        return new RespuestaPanelTareas(hoy, conteo(conteos, CodigosTareas.ESTADO_TAREA_PENDIENTE),
                conteo(conteos, CodigosTareas.ESTADO_TAREA_EN_CURSO),
                conteo(conteos, CodigosTareas.ESTADO_TAREA_COMPLETADA),
                conteo(conteos, CodigosTareas.ESTADO_TAREA_NO_REALIZADA),
                conteo(conteos, CodigosTareas.ESTADO_TAREA_CANCELADA), atrasadas, ensamblarEnOrden(idsAtrasadas));
    }

    /**
     * Próximos horarios (regla 4 de la Fase 6): tareas MEDICAMENTOS o
     * COMPRA_RETIRO_MEDICAMENTOS, PENDIENTE o EN_CURSO, con {@code
     * FECHA_HORARIO + MINUTOS_TOLERANCIA ≥ ahora} (todavía no atrasada;
     * decisión del controlador I-2 de la revisión final: antes el borde
     * inferior era {@code ahora ≤ FECHA_HORARIO}, que dejaba un hueco entre
     * "próximos" y "atrasadas" para una tarea dentro de su tolerancia) y
     * {@code FECHA_HORARIO < ahora +} {@value
     * CodigosPanel#HORAS_PROXIMOS_HORARIOS} {@code h} (borde superior sin
     * cambios), las {@value CodigosPanel#LIMITE_LISTAS} primeras por
     * horario. No audita (regla 8).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPanelHorarios proximosHorarios(long idGrupo, Long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        if (idAdulto != null) {
            validarAdultoDelGrupo(idGrupo, idAdulto);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime hasta = ahora.plusHours(CodigosPanel.HORAS_PROXIMOS_HORARIOS);
        List<Long> ids = repositorioConsulta.idsTareas(idGrupo, idAdulto,
                List.of(CodigosTareas.TIPO_TAREA_MEDICAMENTOS, CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS),
                ahora, hasta, CodigosPanel.LIMITE_LISTAS);
        return new RespuestaPanelHorarios(ensamblarEnOrden(ids));
    }

    /**
     * Citas próximas (regla 5 de la Fase 6): tareas CITAS_CONTROLES_MEDICOS,
     * PENDIENTE o EN_CURSO, con {@code FECHA_HORARIO + MINUTOS_TOLERANCIA ≥
     * ahora} (todavía no atrasada; misma decisión del controlador I-2 que
     * {@link #proximosHorarios}) y {@code FECHA_HORARIO < ahora +} {@value
     * CodigosPanel#DIAS_CITAS} {@code días} (borde superior sin cambios),
     * las {@value CodigosPanel#LIMITE_LISTAS} primeras por horario, con
     * médico y lugar (el ensamblador de F4 ya los incluye). No audita
     * (regla 8).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPanelCitas citas(long idGrupo, Long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        if (idAdulto != null) {
            validarAdultoDelGrupo(idGrupo, idAdulto);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime hasta = ahora.plusDays(CodigosPanel.DIAS_CITAS);
        List<Long> ids = repositorioConsulta.idsTareas(idGrupo, idAdulto,
                List.of(CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS), ahora, hasta, CodigosPanel.LIMITE_LISTAS);
        return new RespuestaPanelCitas(ensamblarEnOrden(ids));
    }

    /**
     * Alertas de atraso (regla 3 de la Fase 6; Tarea 2): solo {@code
     * ATRASO_TAREA}. {@code abiertas} cuenta todas las ABIERTA; {@code
     * cerradasUltimos7Dias} cuenta las CERRADA con {@code FECHA_REVISION ≥
     * ahora −} {@value CodigosPanel#DIAS_ALERTAS_CERRADAS} {@code días};
     * {@code recientes} son las {@value CodigosPanel#LIMITE_RECIENTES}
     * ABIERTA más nuevas, por {@code fechaDeteccion} y luego {@code
     * idAlerta} descendente. No audita (regla 8).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPanelAlertas alertas(long idGrupo, Long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        if (idAdulto != null) {
            validarAdultoDelGrupo(idGrupo, idAdulto);
        }

        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        long idEstadoCerrada = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_CERRADA);
        OffsetDateTime revisadasDesde = OffsetDateTime.now(reloj).minusDays(CodigosPanel.DIAS_ALERTAS_CERRADAS);
        List<String> tiposAtraso = List.of(CodigosAlertas.TIPO_ATRASO_TAREA);

        int abiertas = repositorioConsulta.contarAlertas(idGrupo, idAdulto, tiposAtraso, idEstadoAbierta, null);
        int cerradasUltimos7Dias = repositorioConsulta.contarAlertas(idGrupo, idAdulto, tiposAtraso, idEstadoCerrada,
                revisadasDesde);
        List<Long> idsRecientes = repositorioConsulta.idsAlertas(idGrupo, idAdulto, tiposAtraso, idEstadoAbierta,
                RepositorioConsultaPanel.ORDEN_DETECCION_DESCENDENTE, CodigosPanel.LIMITE_RECIENTES);

        return new RespuestaPanelAlertas(abiertas, cerradasUltimos7Dias, ensamblarAlertasEnOrden(idsRecientes));
    }

    /**
     * Alertas preventivas (regla 6 de la Fase 6; Tarea 2): ABIERTA de tipo
     * REPOSICION_MEDICAMENTO o CITA_PROXIMA, las {@value
     * CodigosPanel#LIMITE_LISTAS} primeras por {@code fechaProgramada} y
     * luego {@code idAlerta}, ascendente. No audita (regla 8).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPanelPreventivas alertasPreventivas(long idGrupo, Long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        if (idAdulto != null) {
            validarAdultoDelGrupo(idGrupo, idAdulto);
        }

        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        List<String> tipos = List.of(CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, CodigosAlertas.TIPO_CITA_PROXIMA);
        List<Long> ids = repositorioConsulta.idsAlertas(idGrupo, idAdulto, tipos, idEstadoAbierta,
                RepositorioConsultaPanel.ORDEN_PROGRAMADA_ASCENDENTE, CodigosPanel.LIMITE_LISTAS);

        return new RespuestaPanelPreventivas(ensamblarAlertasEnOrden(ids));
    }

    /**
     * Audita {@code CONSULTAR_PANEL} sobre {@code GRUPO_FAMILIAR} solo
     * cuando el actor es administrador sin membresía en el grupo ({@code
     * contexto.rol() == null}, regla 8 de la Fase 6; TSA-10 y TSA-49); los
     * miembros nunca se auditan. Como la transacción del llamador es de solo
     * lectura, se registra en una transacción propia ({@link
     * ServicioAuditoria#registrarEnTransaccionPropia}).
     */
    private void auditarConsulta(ContextoGrupo contexto, long idGrupo, Long idAdulto) {
        if (!DetallesAuditoria.esAccesoAdministrador(contexto)) {
            return;
        }
        Map<String, Object> detalles = idAdulto == null ? Map.of() : Map.of("idAdulto", idAdulto);
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosPanel.MODULO, "GRUPO_FAMILIAR",
                String.valueOf(idGrupo), idGrupo, contexto.usuario().getIdUsuario(), CodigosPanel.CONSULTAR_PANEL,
                ResultadoAuditoria.EXITO, DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    /**
     * @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al
     *                               grupo (mismo mensaje que {@code
     *                               tareas.ServicioConsultaTareas#validarAdultoDelGrupo}).
     *                               Un adulto INACTIVO del grupo sí se
     *                               puede consultar (regla 1).
     */
    private void validarAdultoDelGrupo(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
    }

    /** Ensambla las tareas de {@code idsOrdenados} conservando su orden: {@code findAllById} no lo garantiza. */
    private List<RespuestaTarea> ensamblarEnOrden(List<Long> idsOrdenados) {
        if (idsOrdenados.isEmpty()) {
            return List.of();
        }
        Map<Long, TareaCuidado> tareasPorId = repositorioTarea.findAllById(idsOrdenados).stream()
                .collect(Collectors.toMap(TareaCuidado::getIdTarea, Function.identity()));
        List<TareaCuidado> ordenadas = idsOrdenados.stream().map(tareasPorId::get).filter(Objects::nonNull).toList();
        return ensamblador.ensamblar(ordenadas);
    }

    private int conteo(Map<Long, Integer> conteos, String codigoEstado) {
        long idEstado = servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO,
                codigoEstado);
        return conteos.getOrDefault(idEstado, 0);
    }

    /** Ensambla las alertas de {@code idsOrdenados} conservando su orden: {@code findAllById} no lo garantiza. */
    private List<RespuestaAlerta> ensamblarAlertasEnOrden(List<Long> idsOrdenados) {
        if (idsOrdenados.isEmpty()) {
            return List.of();
        }
        Map<Long, AlertaCuidado> alertasPorId = repositorioAlerta.findAllById(idsOrdenados).stream()
                .collect(Collectors.toMap(AlertaCuidado::getIdAlerta, Function.identity()));
        List<AlertaCuidado> ordenadas = idsOrdenados.stream().map(alertasPorId::get).filter(Objects::nonNull)
                .toList();
        return ensambladorAlertas.ensamblar(ordenadas);
    }

    private long idEstadoAlerta(String codigoEstado) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO,
                codigoEstado);
    }
}
