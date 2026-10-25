package cl.adndigital.mejorvejez.comun;

import java.util.List;

/**
 * Página de resultados de un listado administrativo paginado: {@code
 * elementos} es la página actual, {@code total} es la cantidad de
 * elementos en todo el listado (sin paginar).
 */
public record RespuestaPagina<T>(List<T> elementos, int pagina, int tamano, long total) {
}
