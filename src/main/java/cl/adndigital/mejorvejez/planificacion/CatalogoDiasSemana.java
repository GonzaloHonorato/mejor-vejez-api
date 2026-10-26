package cl.adndigital.mejorvejez.planificacion;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.catalogos.RespuestaOpcion;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.catalogos.TipoCatalogo;

/**
 * Catálogo {@code DIA_SEMANA} en memoria, para el módulo {@code
 * planificacion}: ordena los días de un detalle por el {@code ORDEN} del
 * catálogo (nunca por id) y traduce cada id a {@link DayOfWeek} para {@link
 * CalculadoraTermino} (ignorando los ids desconocidos). Se resuelve con {@link
 * ServicioCatalogos#listarOpciones} una sola vez, en el primer uso, y queda
 * cacheado: el catálogo lo siembra {@code V2__catalogos.sql} y no cambia en
 * caliente (misma premisa que la caché de estados de {@link
 * ServicioCatalogos}). Si dos hilos lo cargan a la vez, ambos obtienen el
 * mismo contenido y gana cualquiera de las dos asignaciones (carga
 * idempotente, sin estado parcial visible gracias a {@code volatile}).
 */
@Component
class CatalogoDiasSemana {

    private final ServicioCatalogos servicioCatalogos;
    private final CalculadoraTermino calculadoraTermino;

    private volatile Map<Long, RespuestaOpcion> diasPorId;

    CatalogoDiasSemana(ServicioCatalogos servicioCatalogos, CalculadoraTermino calculadoraTermino) {
        this.servicioCatalogos = servicioCatalogos;
        this.calculadoraTermino = calculadoraTermino;
    }

    /** Los ids indicados, ordenados por el {@code ORDEN} del catálogo (desempate por id). */
    List<Long> ordenar(Collection<Long> idsDia) {
        Map<Long, RespuestaOpcion> dias = dias();
        Comparator<Long> porOrden = Comparator.comparing(
                (Long id) -> dias.containsKey(id) ? dias.get(id).orden() : Integer.MAX_VALUE)
                .thenComparing(Comparator.naturalOrder());
        return idsDia.stream().sorted(porOrden).toList();
    }

    /**
     * Si el id es un día activo del catálogo (los ids ya pasaron por {@link
     * ServicioCatalogos#validarOpcion}; esto descarta además un día
     * desactivado en el catálogo, que {@code listarOpciones} no trae).
     */
    boolean esDiaActivo(long idDia) {
        return dias().containsKey(idDia);
    }

    /**
     * {@link DayOfWeek} de cada id que sea un día activo del catálogo. Los
     * ids desconocidos (p. ej. un día guardado que luego se desactivó en el
     * catálogo) se ignoran en vez de fallar: quien estima el término recibe
     * solo los días que puede interpretar.
     */
    Set<DayOfWeek> diasDeSemanaConocidos(Collection<Long> idsDia) {
        Map<Long, RespuestaOpcion> dias = dias();
        return idsDia.stream()
                .map(dias::get)
                .filter(Objects::nonNull)
                .map(dia -> calculadoraTermino.diaDeSemana(dia.codigo()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    private Map<Long, RespuestaOpcion> dias() {
        Map<Long, RespuestaOpcion> cargados = diasPorId;
        if (cargados == null) {
            cargados = servicioCatalogos.listarOpciones(TipoCatalogo.DIA_SEMANA).stream()
                    .collect(Collectors.toUnmodifiableMap(RespuestaOpcion::idOpcion, Function.identity()));
            diasPorId = cargados;
        }
        return cargados;
    }
}
