package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.adultos.AdultoMayor;
import cl.adndigital.mejorvejez.adultos.RepositorioAdultoMayor;
import cl.adndigital.mejorvejez.adultos.ServicioAdultosMayores;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Consulta de la agenda de tareas de cuidado y del detalle de una tarea
 * (HU-05/06/07). Solo lectura: la autorización exige únicamente membresía
 * activa del grupo ({@link ServicioAutorizacion#exigirMiembro(long)}); ni
 * {@code listar} ni {@code obtener} auditan (igual que {@code
 * planificacion.ServicioPlanes#listar}/{@code #obtener}).
 *
 * <p><b>Rango de la agenda.</b> {@code desde} nulo se toma como hoy; {@code
 * hasta} nulo, igual a {@code desde}. El rango no puede invertirse ni superar
 * los 31 días, y se traduce a instantes {@code [desde 00:00, hasta + 1
 * 00:00)} en {@link ZonaNegocio#ZONA}, nunca en la zona del servidor.
 *
 * <p><b>Sin N+1.</b> El ensamblado de la respuesta lo delega por completo en
 * {@link EnsambladorRespuestasTarea}, que resuelve adultos, responsables,
 * líneas de compra y citas en consultas por lote.
 */
@Service
public class ServicioConsultaTareas {

    private static final int DIAS_MAXIMO_RANGO = 31;

    static final String MENSAJE_RANGO_INVALIDO = "El rango de fechas no es válido";
    static final String MENSAJE_RANGO_MAXIMO = "El rango máximo es de 31 días";
    static final String MENSAJE_NO_EXISTE = "La tarea no existe";

    private static final String TABLA_ESTADO_TAREA = CodigosTareas.TABLA_TAREA_CUIDADO;
    private static final String TABLA_ESTADO_ASIGNACION = CodigosTareas.TABLA_ASIGNACION_TAREA;
    private static final String CAMPO_ESTADO = CodigosTareas.CAMPO_ESTADO;

    private final RepositorioTareaCuidado repositorioTarea;
    private final RepositorioAdultoMayor repositorioAdulto;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final EnsambladorRespuestasTarea ensamblador;
    private final Clock reloj;

    public ServicioConsultaTareas(RepositorioTareaCuidado repositorioTarea, RepositorioAdultoMayor repositorioAdulto,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            EnsambladorRespuestasTarea ensamblador, Clock reloj) {
        this.repositorioTarea = repositorioTarea;
        this.repositorioAdulto = repositorioAdulto;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.ensamblador = ensamblador;
        this.reloj = reloj;
    }

    /**
     * Agenda del grupo en el rango indicado, ordenada por horario.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionReglaNegocio   el rango es inválido, supera los 31
     *                                 días, o {@code idEstado} no pertenece al
     *                                 dominio {@code TAREA_CUIDADO}.
     * @throws ExcepcionNoEncontrado   {@code idAdulto} no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public List<RespuestaTarea> listar(long idGrupo, FiltroTareas filtro) {
        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);

        LocalDate desde = filtro.desde() != null ? filtro.desde() : LocalDate.now(reloj);
        LocalDate hasta = filtro.hasta() != null ? filtro.hasta() : desde;
        validarRango(desde, hasta);

        if (filtro.idEstado() != null) {
            servicioCatalogos.validarEstado(filtro.idEstado(), TABLA_ESTADO_TAREA, CAMPO_ESTADO);
        }
        if (filtro.idAdulto() != null) {
            validarAdultoDelGrupo(idGrupo, filtro.idAdulto());
        }

        OffsetDateTime inicioRango = desde.atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime finRango = hasta.plusDays(1).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();

        Long idUsuarioAsignado = filtro.soloMias() ? contexto.usuario().getIdUsuario() : null;
        long idEstadoAsignacionActiva = idEstadoAsignacionActiva();

        List<TareaCuidado> tareas = repositorioTarea.buscarAgenda(idGrupo, inicioRango, finRango, filtro.idAdulto(),
                filtro.idEstado(), idUsuarioAsignado, idEstadoAsignacionActiva);
        return ensamblador.ensamblar(tareas);
    }

    /**
     * El detalle de la tarea, con su historial de estados y sus asignaciones.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   la tarea no existe o no pertenece al grupo.
     */
    @Transactional(readOnly = true)
    public RespuestaTareaDetallada obtener(long idGrupo, long idTarea) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        TareaCuidado tarea = buscarTarea(idGrupo, idTarea);
        return ensamblador.detallar(tarea);
    }

    /** @throws ExcepcionReglaNegocio {@code hasta} anterior a {@code desde}, o el rango supera los 31 días. */
    private void validarRango(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) {
            throw new ExcepcionReglaNegocio(MENSAJE_RANGO_INVALIDO);
        }
        long diasDelRango = ChronoUnit.DAYS.between(desde, hasta) + 1;
        if (diasDelRango > DIAS_MAXIMO_RANGO) {
            throw new ExcepcionReglaNegocio(MENSAJE_RANGO_MAXIMO);
        }
    }

    /** @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al grupo. */
    private void validarAdultoDelGrupo(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
    }

    /** @throws ExcepcionNoEncontrado la tarea no existe o no pertenece al grupo. */
    private TareaCuidado buscarTarea(long idGrupo, long idTarea) {
        TareaCuidado tarea = repositorioTarea.findById(idTarea)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (tarea.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return tarea;
    }

    private long idEstadoAsignacionActiva() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_ASIGNACION, CAMPO_ESTADO,
                CodigosTareas.ESTADO_ASIGNACION_ACTIVO);
    }
}
