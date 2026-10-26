package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;

/**
 * Notificador de alertas (TSA-45, reglas 7 a 10 de la Fase 5). {@link
 * #notificar} notifica una alerta en su propia transacción; {@link
 * #notificarPendientes} es el paso "notificar pendientes" con que terminan
 * las corridas de detección: recorre las alertas ABIERTA del alcance
 * detectadas desde {@code desde} y las notifica una por una, cubriendo las
 * nuevas y los reintentos.
 *
 * <p>{@link #notificarPendientes} NO es {@code @Transactional}: cada alerta
 * abre la suya ({@code REQUIRES_NEW}, en {@link NotificadorAlerta}), así que
 * un fallo inesperado en una deshace solo esa, se registra en el log (solo
 * el id y la clase de la excepción; la traza, en {@code DEBUG}) y se
 * cuenta como un error ({@code Alerta {id}: {claseSimple}}, sin mensaje ni
 * destinos), y la corrida sigue con las demás.
 */
@Service
public class ServicioNotificacionAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioNotificacionAlertas.class);

    private final NotificadorAlerta notificador;
    private final RepositorioAlertaCuidado repositorioAlertas;
    private final ServicioCatalogos servicioCatalogos;

    ServicioNotificacionAlertas(NotificadorAlerta notificador, RepositorioAlertaCuidado repositorioAlertas,
            ServicioCatalogos servicioCatalogos) {
        this.notificador = notificador;
        this.repositorioAlertas = repositorioAlertas;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Notifica la alerta en su propia transacción ({@code REQUIRES_NEW}):
     * envía a cada par (destinatario, canal operativo) que no tenga ya un
     * intento ENVIADO ni haya llegado al máximo de intentos, y guarda cada
     * intento con su resultado. Una alerta CERRADA devuelve {@link
     * ResultadoNotificacion#vacio()}.
     */
    public ResultadoNotificacion notificar(long idAlerta) {
        return notificador.notificar(idAlerta);
    }

    /**
     * Notifica las alertas ABIERTA con {@code FECHA_DETECCION ≥ desde} del
     * grupo {@code idGrupo} (todas si es {@code null}) y suma sus
     * resultados.
     */
    public ResultadoNotificacion notificarPendientes(OffsetDateTime desde, Long idGrupo) {
        long idEstadoAbierta = servicioCatalogos.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO,
                CodigosAlertas.CAMPO_ESTADO, CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        List<Long> ids = repositorioAlertas.idsAbiertasDesde(idEstadoAbierta, desde, idGrupo);
        ResultadoNotificacion total = ResultadoNotificacion.vacio();
        for (Long idAlerta : ids) {
            try {
                total = total.sumar(notificador.notificar(idAlerta));
            } catch (RuntimeException e) {
                // Sin el mensaje crudo ni la traza en ERROR: podrían traer destinos (regla 14).
                LOG.error("Fallo inesperado al notificar la alerta {}: {}", idAlerta, e.getClass().getSimpleName());
                LOG.debug("Traza del fallo al notificar la alerta {}", idAlerta, e);
                total = total.sumar(
                        ResultadoNotificacion.conError("Alerta " + idAlerta + ": " + e.getClass().getSimpleName()));
            }
        }
        return total;
    }
}
