package cl.adndigital.mejorvejez.catalogos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;

/**
 * Resuelve y valida IDs de catálogo (estados y opciones de negocio) para el
 * resto de los servicios de la aplicación. Los catálogos los siembra
 * {@code V2__catalogos.sql} y no cambian en caliente, así que {@code
 * idEstado} los cachea en memoria con carga perezosa por dominio ({@code
 * NOMBRE_TABLA.NOMBRE_CAMPO}): la primera consulta de cada dominio trae el
 * catálogo completo (activos e inactivos); las siguientes solo leen el mapa
 * en memoria. La caché usa {@link ConcurrentHashMap#computeIfAbsent}, que es
 * atómico por clave, así que varios hilos resolviendo el mismo dominio (o
 * dominios distintos) en paralelo no corrompen el mapa ni cargan el mismo
 * dominio dos veces.
 */
@Service
public class ServicioCatalogos {

    private final RepositorioCatalogoEstado repositorioEstado;
    private final RepositorioOpcionCatalogo repositorioOpcion;
    private final Map<String, Map<String, Long>> cacheEstados = new ConcurrentHashMap<>();

    public ServicioCatalogos(RepositorioCatalogoEstado repositorioEstado,
                             RepositorioOpcionCatalogo repositorioOpcion) {
        this.repositorioEstado = repositorioEstado;
        this.repositorioOpcion = repositorioOpcion;
    }

    /**
     * Resuelve el ID del estado {@code codigo} dentro del dominio {@code
     * tabla.campo}. Un código que no existe en el catálogo es un error de
     * programación (código hardcodeado mal escrito, o catálogo no
     * sembrado), no una regla de negocio de la petición: por eso lanza
     * {@link IllegalStateException} en vez de una excepción de negocio.
     */
    public long idEstado(String tabla, String campo, String codigo) {
        Map<String, Long> catalogoDelDominio = cacheEstados.computeIfAbsent(
                claveDominio(tabla, campo), clave -> cargarCatalogo(tabla, campo));
        Long idEstado = catalogoDelDominio.get(codigo);
        if (idEstado == null) {
            throw new IllegalStateException(
                    "No existe el estado '" + codigo + "' en el catálogo " + tabla + "." + campo);
        }
        return idEstado;
    }

    /**
     * Verifica que {@code idEstado} pertenezca al dominio {@code
     * tabla.campo}. Un ID de otro dominio (o inexistente) es un dato
     * inválido de la petición, no un error de programación: por eso lanza
     * {@link ExcepcionReglaNegocio}.
     */
    public void validarEstado(long idEstado, String tabla, String campo) {
        boolean perteneceAlDominio = repositorioEstado.findById(idEstado)
                .filter(estado -> estado.getNombreTabla().equals(tabla) && estado.getNombreCampo().equals(campo))
                .isPresent();
        if (!perteneceAlDominio) {
            throw new ExcepcionReglaNegocio("El estado indicado no corresponde a " + tabla + "." + campo);
        }
    }

    /** Verifica que {@code idOpcion} pertenezca al catálogo {@code tipo}. */
    public void validarOpcion(long idOpcion, TipoCatalogo tipo) {
        boolean perteneceAlTipo = repositorioOpcion.findById(idOpcion)
                .filter(opcion -> opcion.getTipoCatalogo() == tipo)
                .isPresent();
        if (!perteneceAlTipo) {
            throw new ExcepcionReglaNegocio("La opción indicada no pertenece al catálogo " + tipo);
        }
    }

    /** Estados activos del dominio {@code tabla.campo}, en orden de presentación. */
    public List<RespuestaEstado> listarEstados(String tabla, String campo) {
        return repositorioEstado.findByNombreTablaAndNombreCampoAndActivoTrueOrderByOrdenAsc(tabla, campo)
                .stream()
                .map(estado -> new RespuestaEstado(estado.getIdEstado(), estado.getCodigoEstado(),
                        estado.getDescripcion(), estado.getOrden()))
                .toList();
    }

    /** Opciones activas del catálogo {@code tipo}, en orden de presentación. */
    public List<RespuestaOpcion> listarOpciones(TipoCatalogo tipo) {
        return repositorioOpcion.findByTipoCatalogoAndActivoTrueOrderByOrdenAsc(tipo)
                .stream()
                .map(opcion -> new RespuestaOpcion(opcion.getIdOpcion(), opcion.getCodigo(),
                        opcion.getDescripcion(), opcion.getOrden()))
                .toList();
    }

    private Map<String, Long> cargarCatalogo(String tabla, String campo) {
        return repositorioEstado.findByNombreTablaAndNombreCampo(tabla, campo).stream()
                .collect(Collectors.toMap(CatalogoEstado::getCodigoEstado, CatalogoEstado::getIdEstado));
    }

    private String claveDominio(String tabla, String campo) {
        return tabla + "." + campo;
    }
}
