package cl.adndigital.mejorvejez.interno;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.alertas.AlertaCuidado;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.CreadorAlertas;
import cl.adndigital.mejorvejez.alertas.RepositorioAlertaCuidado;
import cl.adndigital.mejorvejez.alertas.RepositorioConsultaAlertas;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;

/**
 * Rutas internas de alertas (Fase 9, TSA-61): el Core solo entrega
 * candidatos y protege la unicidad; la regla de atraso y las preventivas
 * las aplica el microservicio de Alertas con esos candidatos. Reutiliza
 * {@link RepositorioConsultaAlertas} (candidatos) y {@link CreadorAlertas}
 * (creación con bloqueo y revalidación, regla 5 de la Fase 5), sin
 * duplicar ninguna de las dos.
 *
 * <p>{@link #crearAlerta} es idempotente por clave (regla 1 de la Fase 5):
 * cuando {@code CreadorAlertas#crearXxx} devuelve vacío porque la alerta ya
 * existía (no porque el origen ya no corresponda), busca esa alerta por su
 * clave para devolver el mismo {@code idAlerta} con {@code creada=false}.
 */
@Service
public class ServicioInternoAlertas {

    private final RepositorioConsultaAlertas consultaAlertas;
    private final CreadorAlertas creadorAlertas;
    private final RepositorioAlertaCuidado repositorioAlertas;
    private final RepositorioDetallePlan repositorioDetalles;
    private final Clock reloj;

    public ServicioInternoAlertas(RepositorioConsultaAlertas consultaAlertas, CreadorAlertas creadorAlertas,
            RepositorioAlertaCuidado repositorioAlertas, RepositorioDetallePlan repositorioDetalles, Clock reloj) {
        this.consultaAlertas = consultaAlertas;
        this.creadorAlertas = creadorAlertas;
        this.repositorioAlertas = repositorioAlertas;
        this.repositorioDetalles = repositorioDetalles;
        this.reloj = reloj;
    }

    /** {@code GET /api/interno/alertas/candidatos-atraso?desde&hasta}. */
    public List<CandidatoAtraso> candidatosAtraso(OffsetDateTime desde, OffsetDateTime hasta) {
        return consultaAlertas.candidatosAtraso(desde, hasta).stream()
                .map(fila -> new CandidatoAtraso(fila.idTarea(), fila.idGrupo(), fila.idAdulto(),
                        fila.fechaHorario(), fila.minutosTolerancia(), fila.codigoEstado(), fila.generaAlerta()))
                .toList();
    }

    /** {@code GET /api/interno/alertas/candidatos-preventivos?ahora&horasCita&diasReposicion}. */
    public CandidatosPreventivos candidatosPreventivos(OffsetDateTime ahora, int horasCita, int diasReposicion) {
        OffsetDateTime hastaCitas = ahora.plusHours(horasCita);
        LocalDate hoy = ahora.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
        LocalDate hastaReposicion = hoy.plusDays(diasReposicion);

        List<CandidatoCita> citas = consultaAlertas.candidatosCitaProxima(ahora, hastaCitas).stream()
                .map(fila -> new CandidatoCita(fila.idTarea(), fila.fechaHorario()))
                .toList();
        List<CandidatoReposicion> reposiciones = consultaAlertas.candidatosReposicion(hoy, hastaReposicion).stream()
                .map(fila -> new CandidatoReposicion(fila.idDetallePlan(), fila.fechaEstimadaTermino()))
                .toList();
        return new CandidatosPreventivos(citas, reposiciones);
    }

    /** {@code POST /api/interno/alertas}. */
    public RespuestaCrearAlerta crearAlerta(SolicitudCrearAlerta solicitud) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return switch (solicitud.tipoAlerta()) {
            case CodigosAlertas.TIPO_ATRASO_TAREA -> crearAtraso(exigirIdTarea(solicitud), ahora);
            case CodigosAlertas.TIPO_CITA_PROXIMA -> crearCitaProxima(exigirIdTarea(solicitud), ahora);
            case CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO -> crearReposicion(exigirIdDetalle(solicitud), ahora);
            case null, default -> throw new ExcepcionReglaNegocio(
                    "El tipo de alerta '" + solicitud.tipoAlerta() + "' no es válido.");
        };
    }

    private RespuestaCrearAlerta crearAtraso(long idTarea, OffsetDateTime ahora) {
        Optional<Long> idAlerta = creadorAlertas.crearAtraso(idTarea, ahora);
        if (idAlerta.isPresent()) {
            return new RespuestaCrearAlerta(idAlerta.get(), true);
        }
        return new RespuestaCrearAlerta(idPorClave(CreadorAlertas.claveAtraso(idTarea)), false);
    }

    private RespuestaCrearAlerta crearCitaProxima(long idTarea, OffsetDateTime ahora) {
        Optional<Long> idAlerta = creadorAlertas.crearCitaProxima(idTarea, ahora);
        if (idAlerta.isPresent()) {
            return new RespuestaCrearAlerta(idAlerta.get(), true);
        }
        return new RespuestaCrearAlerta(idPorClave(CreadorAlertas.claveCitaProxima(idTarea)), false);
    }

    private RespuestaCrearAlerta crearReposicion(long idDetalle, OffsetDateTime ahora) {
        LocalDate hoy = ahora.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
        Optional<Long> idAlerta = creadorAlertas.crearReposicion(idDetalle, hoy, ahora);
        if (idAlerta.isPresent()) {
            return new RespuestaCrearAlerta(idAlerta.get(), true);
        }
        // La clave de reposición depende del término VIGENTE del detalle, así que se vuelve a
        // leer antes de buscar la alerta (el término pudo cambiar entre la verificación y esta lectura).
        Long idPrevio = repositorioDetalles.findById(idDetalle)
                .map(DetallePlan::getFechaEstimadaTermino)
                .map(termino -> CreadorAlertas.claveReposicion(idDetalle, termino))
                .map(this::idPorClave)
                .orElse(null);
        return new RespuestaCrearAlerta(idPrevio, false);
    }

    private Long idPorClave(String clave) {
        return repositorioAlertas.findByClaveAlerta(clave).map(AlertaCuidado::getIdAlerta).orElse(null);
    }

    private long exigirIdTarea(SolicitudCrearAlerta solicitud) {
        if (solicitud.idTarea() == null) {
            throw new ExcepcionReglaNegocio(
                    "Falta 'idTarea' para el tipo de alerta '" + solicitud.tipoAlerta() + "'.");
        }
        return solicitud.idTarea();
    }

    private long exigirIdDetalle(SolicitudCrearAlerta solicitud) {
        if (solicitud.idDetallePlan() == null) {
            throw new ExcepcionReglaNegocio(
                    "Falta 'idDetallePlan' para el tipo de alerta '" + solicitud.tipoAlerta() + "'.");
        }
        return solicitud.idDetallePlan();
    }
}
