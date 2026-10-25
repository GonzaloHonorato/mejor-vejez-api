package cl.adndigital.mejorvejez.auditoria;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.FiltroCorrelacion;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registra eventos de auditoría (HU-12) en {@code EVENTO_AUDITORIA}, append-only
 * (el trigger {@code TRG_AUDITORIA_INMUTABLE} impide modificarlos o
 * eliminarlos). El ID de correlación no lo recibe el llamador: lo toma del
 * MDC (clave {@link FiltroCorrelacion#CLAVE_MDC_ID_CORRELACION}), donde lo
 * deja {@code FiltroCorrelacion} por cada petición.
 */
@Service
public class ServicioAuditoria {

    /** Fragmentos de clave (en minúsculas) que nunca se guardan en {@code DETALLES_JSON} (HU-12). */
    private static final Set<String> FRAGMENTOS_CLAVE_SENSIBLE =
            Set.of("token", "clave", "password", "secret", "authorization");

    private final RepositorioEventoAuditoria repositorio;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;
    private final JsonMapper jsonMapper;

    public ServicioAuditoria(RepositorioEventoAuditoria repositorio, ServicioCatalogos servicioCatalogos,
            Clock reloj, JsonMapper jsonMapper) {
        this.repositorio = repositorio;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
        this.jsonMapper = jsonMapper;
    }

    /** Registra el evento participando en la transacción del llamador. */
    @Transactional
    public void registrar(EventoARegistrar evento) {
        persistir(evento);
    }

    /**
     * Registra el evento en una transacción propia ({@code REQUIRES_NEW}),
     * que confirma aunque la transacción del llamador haga rollback. Úsalo
     * para fallos y rechazos que deben quedar registrados igual.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarEnTransaccionPropia(EventoARegistrar evento) {
        persistir(evento);
    }

    private void persistir(EventoARegistrar evento) {
        long idResultado = servicioCatalogos.idEstado("EVENTO_AUDITORIA", "RESULTADO", evento.resultado().name());
        String idCorrelacion = MDC.get(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION);
        String detallesJson = serializarDetalles(evento.detalles());

        EventoAuditoria entidad = new EventoAuditoria(
                evento.modulo(),
                evento.tipoEntidad(),
                evento.idEntidad(),
                evento.idGrupo(),
                evento.idUsuarioActor(),
                evento.codigoAccion(),
                OffsetDateTime.now(reloj),
                idResultado,
                idCorrelacion,
                detallesJson);

        repositorio.save(entidad);
    }

    private String serializarDetalles(Map<String, Object> detalles) {
        if (detalles == null) {
            return null;
        }
        return jsonMapper.writeValueAsString(omitirClavesSensibles(detalles));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> omitirClavesSensibles(Map<String, Object> origen) {
        Map<String, Object> filtrado = new LinkedHashMap<>();
        origen.forEach((clave, valor) -> {
            if (esClaveSensible(clave)) {
                return;
            }
            if (valor instanceof Map<?, ?> mapaAnidado) {
                filtrado.put(clave, omitirClavesSensibles((Map<String, Object>) mapaAnidado));
            } else {
                filtrado.put(clave, valor);
            }
        });
        return filtrado;
    }

    private boolean esClaveSensible(String clave) {
        String claveEnMinusculas = clave.toLowerCase(Locale.ROOT);
        return FRAGMENTOS_CLAVE_SENSIBLE.stream().anyMatch(claveEnMinusculas::contains);
    }
}
