package cl.adndigital.mejorvejez.relevos;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.adultos.ServicioAdultosMayores;
import cl.adndigital.mejorvejez.alertas.AlertaCuidado;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.RepositorioAlertaCuidado;
import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.RespuestaEstado;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.comun.TextoAcotado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.GrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.EnsambladorRespuestasTarea;
import cl.adndigital.mejorvejez.tareas.RepositorioTareaCuidado;
import cl.adndigital.mejorvejez.tareas.TareaCuidado;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Registrar, listar, consultar y cancelar relevos del turno de cuidado
 * (HU-11, TSA-51). El patrón de autorización es el de {@code
 * grupos.ServicioIntegrantes}: bloquear el grupo (serializa las mutaciones
 * concurrentes del mismo grupo) → autorizar → leer/validar.
 *
 * <p><b>Foto de solo lectura (regla 5 de la Fase 7).</b> {@link
 * #registrar} nunca llama a un servicio de tareas o alertas que mute: solo
 * lee ids con {@link RepositorioConsultaRelevos} y entidades con {@code
 * findAllById}. Hay una prueba que lo verifica: los estados de tareas,
 * alertas y asignaciones, y la cantidad de asignaciones, quedan iguales
 * antes y después de registrar un relevo.
 *
 * <p><b>Aislamiento (foco de revisión 5).</b> La foto se arma con consultas
 * que filtran siempre por grupo y adulto ({@link RepositorioConsultaRelevos}),
 * así queda validada la referencia polimórfica de {@code DETALLE_TRASPASO}
 * sin que pueda arrastrar tareas o alertas de otro adulto o de otro grupo.
 *
 * <p><b>Auditoría (regla 8).</b> {@code REGISTRAR_RELEVO} y {@code
 * CANCELAR_RELEVO} llevan {@code DetallesAuditoria#conTrazabilidad}; las
 * notas nunca se auditan (minimización de datos).
 *
 * <p><b>Consulta del administrador auditada (foco de revisión 3, ronda de
 * corrección 1).</b> {@link #obtener} audita {@code CONSULTAR_RELEVO} solo
 * cuando el actor es administrador sin membresía en el grupo ({@code
 * contexto.rol() == null}), en una transacción propia (la del llamador es de
 * solo lectura), igual que {@code panel.ServicioPanel#auditarConsulta}.
 * {@link #listar} nunca audita (mismo patrón del panel: solo audita una
 * sección); los miembros nunca se auditan.
 */
@Service
public class ServicioRelevos {

    static final String MENSAJE_ADULTO_INACTIVO = "El adulto mayor no está activo";
    static final String MENSAJE_MISMAS_PERSONAS = "Quien entrega y quien recibe deben ser personas distintas";
    static final String MENSAJE_PERSONAS_NO_ACTIVAS =
            "Quien entrega y quien recibe deben ser integrantes activos del grupo";
    static final String MENSAJE_SOLO_PROPIO_SALIENTE = "Solo puedes registrar relevos en los que entregas el turno";
    static final String MENSAJE_RELEVO_NO_EXISTE = "El relevo no existe";
    static final String MENSAJE_SOLO_CREADOR_O_COORDINADOR =
            "Solo quien creó el relevo, un coordinador o un administrador pueden cancelarlo";
    static final String MENSAJE_YA_CANCELADO = "El relevo ya está cancelado";

    private static final String MENSAJE_SIN_ASIGNAR = "sin asignar";
    private static final DateTimeFormatter FORMATO_FECHA_HORA = DateTimeFormatter.ofPattern("dd-MM HH:mm");

    private static final String TABLA_INTEGRANTE_GRUPO = "INTEGRANTE_GRUPO";
    private static final String TABLA_USUARIO = "USUARIO";
    private static final String TABLA_ADULTO_MAYOR = "ADULTO_MAYOR";
    private static final String CODIGO_ACTIVO = "ACTIVO";

    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioIntegranteGrupo repositorioIntegrante;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioTraspasoFamiliar repositorioTraspaso;
    private final RepositorioDetalleTraspaso repositorioDetalle;
    private final RepositorioConsultaRelevos repositorioConsulta;
    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioAlertaCuidado repositorioAlerta;
    private final EnsambladorRespuestasTarea ensambladorTareas;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioRelevos(RepositorioGrupoFamiliar repositorioGrupo, RepositorioAdultoMayor repositorioAdulto,
            RepositorioIntegranteGrupo repositorioIntegrante, RepositorioUsuario repositorioUsuario,
            RepositorioTraspasoFamiliar repositorioTraspaso, RepositorioDetalleTraspaso repositorioDetalle,
            RepositorioConsultaRelevos repositorioConsulta, RepositorioTareaCuidado repositorioTarea,
            RepositorioAlertaCuidado repositorioAlerta, EnsambladorRespuestasTarea ensambladorTareas,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioGrupo = repositorioGrupo;
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioIntegrante = repositorioIntegrante;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioTraspaso = repositorioTraspaso;
        this.repositorioDetalle = repositorioDetalle;
        this.repositorioConsulta = repositorioConsulta;
        this.repositorioTarea = repositorioTarea;
        this.repositorioAlerta = repositorioAlerta;
        this.ensambladorTareas = ensambladorTareas;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Registra un relevo, con su foto de tareas y alertas pendientes del
     * adulto (regla 4 de la Fase 7). Orden de validaciones (regla 2):
     * bloquear el grupo → {@code exigirMiembro} → adulto del grupo y activo
     * → saliente/entrante distintos → ambos integrantes activos con cuenta
     * activa → si el actor es colaborador, debe ser el saliente.
     *
     * @throws ExcepcionNoEncontrado   el grupo no existe, o el adulto no
     *                                 existe o no pertenece al grupo.
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo,
     *                                 o es colaborador y no es el saliente.
     * @throws ExcepcionReglaNegocio   el adulto no está activo, saliente y
     *                                 entrante son la misma persona, o
     *                                 alguno no es integrante activo del
     *                                 grupo con cuenta activa.
     */
    @Transactional
    public RespuestaRelevo registrar(long idGrupo, SolicitudRegistrarRelevo solicitud) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        GrupoFamiliar grupo = contexto.grupo();

        AdultoMayor adulto = validarAdultoActivoDelGrupo(idGrupo, solicitud.idAdulto());

        long idSaliente = solicitud.idUsuarioSaliente();
        long idEntrante = solicitud.idUsuarioEntrante();
        if (idSaliente == idEntrante) {
            throw new ExcepcionReglaNegocio(MENSAJE_MISMAS_PERSONAS);
        }
        exigirIntegranteActivoConCuentaActiva(idGrupo, idSaliente);
        exigirIntegranteActivoConCuentaActiva(idGrupo, idEntrante);

        if (RolGrupo.COLABORADOR.equals(contexto.rol()) && idSaliente != contexto.usuario().getIdUsuario()) {
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_PROPIO_SALIENTE);
        }

        long idEstadoCompletada = idEstadoTraspaso(CodigosRelevos.ESTADO_TRASPASO_COMPLETADA);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        TraspasoFamiliar traspaso = TraspasoFamiliar.nuevo(idGrupo, adulto.getIdAdultoMayor(), idSaliente, idEntrante,
                solicitud.notas(), contexto.usuario().getIdUsuario(), idEstadoCompletada, ahora);
        TraspasoFamiliar guardado = repositorioTraspaso.save(traspaso);

        List<DetalleTraspaso> detalles =
                construirFoto(guardado.getIdTraspaso(), idGrupo, adulto.getIdAdultoMayor(), ahora);
        if (!detalles.isEmpty()) {
            repositorioDetalle.saveAll(detalles);
        }

        int totalTareas = contarTipo(detalles, CodigosRelevos.TIPO_ELEMENTO_TAREA);
        int totalAlertas = detalles.size() - totalTareas;
        auditarRegistro(contexto, idGrupo, guardado, totalTareas, totalAlertas);

        return ensamblar(guardado, grupo, adulto, detalles);
    }

    /**
     * Relevos del grupo, con filtro opcional por adulto, ordenados de más
     * reciente a más antiguo (regla 1).
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaPagina<RespuestaRelevoResumen> listar(long idGrupo, Long idAdulto, int pagina, int tamano) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        GrupoFamiliar grupo = contexto.grupo();

        Page<TraspasoFamiliar> paginaTraspasos =
                repositorioTraspaso.buscar(idGrupo, idAdulto, PageRequest.of(pagina, tamano));
        List<TraspasoFamiliar> traspasos = paginaTraspasos.getContent();
        if (traspasos.isEmpty()) {
            return new RespuestaPagina<>(List.of(), pagina, tamano, paginaTraspasos.getTotalElements());
        }

        Map<Long, AdultoMayor> adultosPorId = repositorioAdulto
                .findAllById(traspasos.stream().map(TraspasoFamiliar::getIdAdultoMayor).distinct().toList())
                .stream().collect(Collectors.toMap(AdultoMayor::getIdAdultoMayor, Function.identity()));

        Set<Long> idsUsuario = new HashSet<>();
        traspasos.forEach(traspaso -> {
            idsUsuario.add(traspaso.getIdUsuarioSaliente());
            idsUsuario.add(traspaso.getIdUsuarioEntrante());
            idsUsuario.add(traspaso.getIdUsuarioCreador());
        });
        Map<Long, String> nombresUsuario = repositorioUsuario.findAllById(idsUsuario).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Usuario::getNombre));

        List<Long> idsTraspaso = traspasos.stream().map(TraspasoFamiliar::getIdTraspaso).toList();
        // Ronda de corrección 1 (minor 5): cuenta agregada por tipo, sin cargar los RESUMEN
        // completos (hasta 1000 bytes cada uno) de cada DetalleTraspaso solo para contarlos.
        Map<Long, Map<String, Long>> conteosPorTraspaso = repositorioConsulta.conteosDeDetallePorTraspaso(idsTraspaso);

        List<RespuestaRelevoResumen> elementos = traspasos.stream()
                .map(traspaso -> ensamblarResumen(traspaso, grupo, adultosPorId.get(traspaso.getIdAdultoMayor()),
                        nombresUsuario, totalPorTipo(conteosPorTraspaso, traspaso.getIdTraspaso(),
                                CodigosRelevos.TIPO_ELEMENTO_TAREA),
                        totalPorTipo(conteosPorTraspaso, traspaso.getIdTraspaso(), CodigosRelevos.TIPO_ELEMENTO_ALERTA)))
                .toList();
        return new RespuestaPagina<>(elementos, pagina, tamano, paginaTraspasos.getTotalElements());
    }

    /**
     * El relevo, con su foto completa. Audita {@code CONSULTAR_RELEVO} si el
     * actor es administrador sin membresía en el grupo (foco de revisión 3,
     * ronda de corrección 1); los miembros nunca se auditan, y {@link
     * #listar} tampoco audita (mismo patrón del panel).
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   el relevo no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaRelevo obtener(long idGrupo, long idRelevo) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        TraspasoFamiliar traspaso = buscarTraspasoDelGrupo(idGrupo, idRelevo);
        auditarConsultaDeAdministrador(contexto, idGrupo, idRelevo);
        AdultoMayor adulto = adultoDelRelevo(traspaso);
        List<DetalleTraspaso> detalles = repositorioDetalle.findByIdTraspasoOrderByIdDetalleTraspaso(idRelevo);
        return ensamblar(traspaso, contexto.grupo(), adulto, detalles);
    }

    /**
     * Cancela el relevo (regla 7); la foto se conserva igual.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo,
     *                                 o no es el creador, un coordinador ni
     *                                 un administrador.
     * @throws ExcepcionNoEncontrado   el relevo no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      el relevo ya está cancelado.
     */
    @Transactional
    public RespuestaRelevo cancelar(long idGrupo, long idRelevo) {
        bloquearGrupo(idGrupo);
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);
        TraspasoFamiliar traspaso = buscarTraspasoDelGrupo(idGrupo, idRelevo);

        // Ronda de corrección 1 (Important 1): Objects.equals, no "==" (ambos lados son Long;
        // con ids > 127, fuera de la caché de Long, "==" compara la referencia, no el valor).
        boolean puedeCancelar = contexto.esAdministrador() || RolGrupo.COORDINADOR.equals(contexto.rol())
                || Objects.equals(traspaso.getIdUsuarioCreador(), contexto.usuario().getIdUsuario());
        if (!puedeCancelar) {
            throw new ExcepcionAccesoDenegado(MENSAJE_SOLO_CREADOR_O_COORDINADOR);
        }

        long idCancelada = idEstadoTraspaso(CodigosRelevos.ESTADO_TRASPASO_CANCELADA);
        if (traspaso.getIdEstado() == idCancelada) {
            throw new ExcepcionConflicto(MENSAJE_YA_CANCELADO);
        }

        traspaso.cancelar(idCancelada);
        TraspasoFamiliar guardado = repositorioTraspaso.save(traspaso);

        servicioAuditoria.registrar(new EventoARegistrar(CodigosRelevos.MODULO, CodigosRelevos.TIPO_ENTIDAD_RELEVO,
                String.valueOf(idRelevo), idGrupo, contexto.usuario().getIdUsuario(),
                CodigosRelevos.ACCION_CANCELAR_RELEVO, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, null)));

        AdultoMayor adulto = adultoDelRelevo(guardado);
        List<DetalleTraspaso> detalles = repositorioDetalle.findByIdTraspasoOrderByIdDetalleTraspaso(idRelevo);
        return ensamblar(guardado, contexto.grupo(), adulto, detalles);
    }

    // ------------------------------------------------------------------
    // Foto (regla 4)
    // ------------------------------------------------------------------

    /**
     * Arma la foto de tareas y alertas pendientes del adulto, lista para
     * guardar en {@code DETALLE_TRASPASO}: tareas primero, luego alertas,
     * cada una con su texto de resumen ya truncado.
     */
    private List<DetalleTraspaso> construirFoto(long idTraspaso, long idGrupo, long idAdulto, OffsetDateTime ahora) {
        OffsetDateTime desde = ahora.minusDays(CodigosRelevos.DIAS_RETROSPECTIVA_RESUMEN);
        OffsetDateTime hasta = ahora.plusHours(CodigosRelevos.HORAS_PROSPECTIVA_RESUMEN);
        long idPendiente = idEstadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        long idEnCurso = idEstadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO);
        long idAsignacionActiva = servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA,
                CodigosTareas.CAMPO_ESTADO, CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
        long idAlertaAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);

        List<Long> idsTarea = repositorioConsulta.idsTareasParaResumen(idGrupo, idAdulto, desde, hasta, idPendiente,
                idEnCurso, CodigosRelevos.LIMITE_ELEMENTOS_RESUMEN);
        List<Long> idsAlerta = repositorioConsulta.idsAlertasParaResumen(idGrupo, idAdulto, idAlertaAbierta,
                CodigosRelevos.LIMITE_ELEMENTOS_RESUMEN);

        List<DetalleTraspaso> detalles = new ArrayList<>();
        detalles.addAll(fotoDeTareas(idTraspaso, idsTarea, idPendiente, idEnCurso, idAsignacionActiva, ahora));
        detalles.addAll(fotoDeAlertas(idTraspaso, idsAlerta));
        return detalles;
    }

    private List<DetalleTraspaso> fotoDeTareas(long idTraspaso, List<Long> idsTarea, long idPendiente,
            long idEnCurso, long idAsignacionActiva, OffsetDateTime ahora) {
        if (idsTarea.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nombresResponsable = repositorioConsulta.nombresResponsablesPorTarea(idsTarea,
                idAsignacionActiva);
        Map<Long, TareaCuidado> tareasPorId = repositorioTarea.findAllById(idsTarea).stream()
                .collect(Collectors.toMap(TareaCuidado::getIdTarea, Function.identity()));
        Map<Long, String> descripcionesEstado = servicioCatalogos
                .listarEstados(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO).stream()
                .collect(Collectors.toMap(RespuestaEstado::idEstado, RespuestaEstado::descripcion));

        List<DetalleTraspaso> detalles = new ArrayList<>();
        for (Long idTarea : idsTarea) {
            TareaCuidado tarea = tareasPorId.get(idTarea);
            if (tarea == null) {
                continue;
            }
            String resumen = resumenTarea(tarea, idPendiente, idEnCurso, ahora, descripcionesEstado,
                    nombresResponsable.get(idTarea));
            detalles.add(new DetalleTraspaso(idTraspaso, CodigosRelevos.TIPO_ELEMENTO_TAREA, idTarea, resumen));
        }
        return detalles;
    }

    private List<DetalleTraspaso> fotoDeAlertas(long idTraspaso, List<Long> idsAlerta) {
        if (idsAlerta.isEmpty()) {
            return List.of();
        }
        Map<Long, AlertaCuidado> alertasPorId = repositorioAlerta.findAllById(idsAlerta).stream()
                .collect(Collectors.toMap(AlertaCuidado::getIdAlerta, Function.identity()));

        List<DetalleTraspaso> detalles = new ArrayList<>();
        for (Long idAlerta : idsAlerta) {
            AlertaCuidado alerta = alertasPorId.get(idAlerta);
            if (alerta == null) {
                continue;
            }
            detalles.add(new DetalleTraspaso(idTraspaso, CodigosRelevos.TIPO_ELEMENTO_ALERTA, idAlerta,
                    resumenAlerta(alerta)));
        }
        return detalles;
    }

    /**
     * {@code "{titulo} · {dd-MM HH:mm en Santiago} · {descripción del
     * estado}[ · Atrasada] · Responsable: {nombre | sin asignar}"},
     * truncado a {@value CodigosRelevos#MAXIMO_BYTES_RESUMEN} bytes. La
     * regla de atrasada es la misma de la Fase 4, reutilizada vía {@link
     * EnsambladorRespuestasTarea#estaAtrasada} (ronda de corrección 1,
     * minor 2: ya no se duplica el cálculo).
     */
    private String resumenTarea(TareaCuidado tarea, long idPendiente, long idEnCurso, OffsetDateTime ahora,
            Map<Long, String> descripcionesEstado, String nombreResponsable) {
        String fecha = tarea.getFechaHorario().atZoneSameInstant(ZonaNegocio.ZONA).format(FORMATO_FECHA_HORA);
        String descripcionEstado = descripcionesEstado.getOrDefault(tarea.getIdEstado(), "");
        boolean atrasada = ensambladorTareas.estaAtrasada(tarea, idPendiente, idEnCurso, ahora);

        StringBuilder texto = new StringBuilder(tarea.getTituloTarea()).append(" · ").append(fecha).append(" · ")
                .append(descripcionEstado);
        if (atrasada) {
            texto.append(" · Atrasada");
        }
        texto.append(" · Responsable: ").append(nombreResponsable != null ? nombreResponsable : MENSAJE_SIN_ASIGNAR);
        return TextoAcotado.truncarABytes(texto.toString(), CodigosRelevos.MAXIMO_BYTES_RESUMEN);
    }

    /** {@code "{etiqueta del tipo} · {MENSAJE_ALERTA}"}, truncado a {@value CodigosRelevos#MAXIMO_BYTES_RESUMEN} bytes. */
    private String resumenAlerta(AlertaCuidado alerta) {
        String texto = etiquetaTipoAlerta(alerta.getTipoAlerta()) + " · " + alerta.getMensajeAlerta();
        return TextoAcotado.truncarABytes(texto, CodigosRelevos.MAXIMO_BYTES_RESUMEN);
    }

    private String etiquetaTipoAlerta(String tipoAlerta) {
        return switch (tipoAlerta) {
            case CodigosAlertas.TIPO_ATRASO_TAREA -> "Atraso de tarea";
            case CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO -> "Reposición de medicamento";
            case CodigosAlertas.TIPO_CITA_PROXIMA -> "Cita próxima";
            default -> tipoAlerta;
        };
    }

    // ------------------------------------------------------------------
    // Validaciones (regla 2)
    // ------------------------------------------------------------------

    /** @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al grupo. */
    private AdultoMayor validarAdultoActivoDelGrupo(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
        if (adulto.getIdEstado() != servicioCatalogos.idEstado(TABLA_ADULTO_MAYOR, CodigosRelevos.CAMPO_ESTADO,
                CODIGO_ACTIVO)) {
            throw new ExcepcionReglaNegocio(MENSAJE_ADULTO_INACTIVO);
        }
        return adulto;
    }

    /** @throws ExcepcionReglaNegocio no es integrante {@code ACTIVO} del grupo, o su cuenta no está {@code ACTIVO}. */
    private void exigirIntegranteActivoConCuentaActiva(long idGrupo, long idUsuario) {
        long idEstadoIntegranteActivo =
                servicioCatalogos.idEstado(TABLA_INTEGRANTE_GRUPO, CodigosRelevos.CAMPO_ESTADO, CODIGO_ACTIVO);
        boolean integranteActivo = repositorioIntegrante.findByIdGrupoAndIdUsuario(idGrupo, idUsuario)
                .filter(integrante -> integrante.getIdEstado() == idEstadoIntegranteActivo)
                .isPresent();

        long idEstadoUsuarioActivo = servicioCatalogos.idEstado(TABLA_USUARIO, CodigosRelevos.CAMPO_ESTADO,
                CODIGO_ACTIVO);
        boolean cuentaActiva = repositorioUsuario.findById(idUsuario)
                .filter(usuario -> usuario.getIdEstado() == idEstadoUsuarioActivo)
                .isPresent();

        if (!integranteActivo || !cuentaActiva) {
            throw new ExcepcionReglaNegocio(MENSAJE_PERSONAS_NO_ACTIVAS);
        }
    }

    /** @throws ExcepcionNoEncontrado el relevo no existe o no pertenece al grupo. */
    private TraspasoFamiliar buscarTraspasoDelGrupo(long idGrupo, long idRelevo) {
        TraspasoFamiliar traspaso = repositorioTraspaso.findById(idRelevo)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_RELEVO_NO_EXISTE));
        if (traspaso.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_RELEVO_NO_EXISTE);
        }
        return traspaso;
    }

    /** Bloquea la fila del grupo ({@code SELECT ... FOR UPDATE}); ver javadoc de la clase. */
    private void bloquearGrupo(long idGrupo) {
        repositorioGrupo.bloquearPorId(idGrupo).orElseThrow(() -> new ExcepcionNoEncontrado("El grupo no existe"));
    }

    // ------------------------------------------------------------------
    // Ensamblado de respuestas
    // ------------------------------------------------------------------

    private RespuestaRelevo ensamblar(TraspasoFamiliar traspaso, GrupoFamiliar grupo, AdultoMayor adulto,
            List<DetalleTraspaso> detalles) {
        Map<Long, String> nombresUsuario = nombresPersonas(traspaso);
        int totalTareas = contarTipo(detalles, CodigosRelevos.TIPO_ELEMENTO_TAREA);
        int totalAlertas = detalles.size() - totalTareas;
        List<RespuestaDetalleRelevo> respuestaDetalles = detalles.stream()
                .map(detalle -> new RespuestaDetalleRelevo(detalle.getIdDetalleTraspaso(), detalle.getTipoElemento(),
                        detalle.getIdReferencia(), detalle.getResumen()))
                .toList();
        return new RespuestaRelevo(traspaso.getIdTraspaso(), grupo.getIdGrupo(), grupo.getNombreGrupo(),
                adulto.getIdAdultoMayor(), adulto.getNombreCompleto(),
                persona(traspaso.getIdUsuarioSaliente(), nombresUsuario),
                persona(traspaso.getIdUsuarioEntrante(), nombresUsuario),
                persona(traspaso.getIdUsuarioCreador(), nombresUsuario),
                ZonaNegocio.enZona(traspaso.getFechaTraspaso()), traspaso.getNotas(), traspaso.getIdEstado(),
                codigoEstado(traspaso.getIdEstado()), totalTareas, totalAlertas, respuestaDetalles);
    }

    private RespuestaRelevoResumen ensamblarResumen(TraspasoFamiliar traspaso, GrupoFamiliar grupo,
            AdultoMayor adulto, Map<Long, String> nombresUsuario, int totalTareas, int totalAlertas) {
        return new RespuestaRelevoResumen(traspaso.getIdTraspaso(), grupo.getIdGrupo(), grupo.getNombreGrupo(),
                adulto.getIdAdultoMayor(), adulto.getNombreCompleto(),
                persona(traspaso.getIdUsuarioSaliente(), nombresUsuario),
                persona(traspaso.getIdUsuarioEntrante(), nombresUsuario),
                persona(traspaso.getIdUsuarioCreador(), nombresUsuario),
                ZonaNegocio.enZona(traspaso.getFechaTraspaso()), traspaso.getIdEstado(),
                codigoEstado(traspaso.getIdEstado()), totalTareas, totalAlertas);
    }

    private Map<Long, String> nombresPersonas(TraspasoFamiliar traspaso) {
        Set<Long> idsPersona = new HashSet<>(List.of(traspaso.getIdUsuarioSaliente(), traspaso.getIdUsuarioEntrante(),
                traspaso.getIdUsuarioCreador()));
        return repositorioUsuario.findAllById(idsPersona).stream()
                .collect(Collectors.toMap(Usuario::getIdUsuario, Usuario::getNombre));
    }

    private RespuestaPersona persona(long idUsuario, Map<Long, String> nombres) {
        return new RespuestaPersona(idUsuario, nombres.get(idUsuario));
    }

    private AdultoMayor adultoDelRelevo(TraspasoFamiliar traspaso) {
        return repositorioAdulto.findById(traspaso.getIdAdultoMayor())
                .orElseThrow(() -> new IllegalStateException("El adulto mayor del relevo no existe"));
    }

    private int contarTipo(List<DetalleTraspaso> detalles, String tipoElemento) {
        return (int) detalles.stream().filter(detalle -> tipoElemento.equals(detalle.getTipoElemento())).count();
    }

    /** Total de {@code tipoElemento} del relevo {@code idTraspaso} en {@code conteosPorTraspaso}, o 0 si no hay. */
    private int totalPorTipo(Map<Long, Map<String, Long>> conteosPorTraspaso, long idTraspaso, String tipoElemento) {
        Map<String, Long> porTipo = conteosPorTraspaso.get(idTraspaso);
        if (porTipo == null) {
            return 0;
        }
        return porTipo.getOrDefault(tipoElemento, 0L).intValue();
    }

    /**
     * Audita {@code CONSULTAR_RELEVO} sobre {@code TRASPASO_FAMILIAR} solo
     * cuando el actor es administrador sin membresía en el grupo (foco de
     * revisión 3 de la Fase 7); los miembros nunca se auditan. Como la
     * transacción del llamador ({@link #obtener}) es de solo lectura, se
     * registra en una transacción propia, igual que {@code
     * panel.ServicioPanel#auditarConsulta}.
     */
    private void auditarConsultaDeAdministrador(ContextoGrupo contexto, long idGrupo, long idRelevo) {
        if (!DetallesAuditoria.esAccesoAdministrador(contexto)) {
            return;
        }
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(CodigosRelevos.MODULO,
                CodigosRelevos.TIPO_ENTIDAD_RELEVO, String.valueOf(idRelevo), idGrupo,
                contexto.usuario().getIdUsuario(), CodigosRelevos.ACCION_CONSULTAR_RELEVO, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, null)));
    }

    private String codigoEstado(long idEstado) {
        if (idEstado == idEstadoTraspaso(CodigosRelevos.ESTADO_TRASPASO_CANCELADA)) {
            return CodigosRelevos.ESTADO_TRASPASO_CANCELADA;
        }
        return CodigosRelevos.ESTADO_TRASPASO_COMPLETADA;
    }

    private void auditarRegistro(ContextoGrupo contexto, long idGrupo, TraspasoFamiliar traspaso, int totalTareas,
            int totalAlertas) {
        Map<String, Object> detalles = new LinkedHashMap<>();
        detalles.put("idAdulto", traspaso.getIdAdultoMayor());
        detalles.put("idUsuarioSaliente", traspaso.getIdUsuarioSaliente());
        detalles.put("idUsuarioEntrante", traspaso.getIdUsuarioEntrante());
        detalles.put("tareas", totalTareas);
        detalles.put("alertas", totalAlertas);
        servicioAuditoria.registrar(new EventoARegistrar(CodigosRelevos.MODULO, CodigosRelevos.TIPO_ENTIDAD_RELEVO,
                String.valueOf(traspaso.getIdTraspaso()), idGrupo, contexto.usuario().getIdUsuario(),
                CodigosRelevos.ACCION_REGISTRAR_RELEVO, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    private long idEstadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoAlerta(String codigo) {
        return servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoTraspaso(String codigo) {
        return servicioCatalogos.idEstado(CodigosRelevos.TABLA_TRASPASO_FAMILIAR, CodigosRelevos.CAMPO_ESTADO,
                codigo);
    }
}
