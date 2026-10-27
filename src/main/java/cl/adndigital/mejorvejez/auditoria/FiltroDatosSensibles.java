package cl.adndigital.mejorvejez.auditoria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Quita datos sensibles de un mapa de detalles de auditoría (HU-12, TSA-52).
 * Pública (movida desde {@code ServicioAuditoria}) porque la usan tanto la
 * escritura ({@link ServicioAuditoria}, sin cambio de comportamiento) como la
 * lectura ({@code ServicioConsultaAuditoria}, defensa en profundidad: filtra
 * de nuevo lo que ya está guardado, por si llegó por otra vía o fue escrito
 * antes de este filtro).
 */
public final class FiltroDatosSensibles {

    /**
     * Fragmentos de clave (en minúsculas) que nunca se guardan ni se
     * exponen en {@code DETALLES_JSON}. {@code contrasena}/{@code
     * contraseña} son nuevos en esta tarea (regla 12 de la Fase 7); los
     * demás ya existían en {@code ServicioAuditoria}.
     */
    public static final Set<String> FRAGMENTOS_CLAVE_SENSIBLE =
            Set.of("token", "clave", "password", "secret", "authorization", "contrasena", "contraseña");

    private FiltroDatosSensibles() {
    }

    /**
     * Quita, recursivamente (mapas y listas anidadas, a cualquier
     * profundidad), las claves cuyo nombre contenga (sin distinguir
     * mayúsculas) alguno de {@link #FRAGMENTOS_CLAVE_SENSIBLE}. {@code
     * origen} nulo devuelve {@code null}; nunca modifica {@code origen}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> filtrar(Map<String, Object> origen) {
        if (origen == null) {
            return null;
        }
        Map<String, Object> filtrado = new LinkedHashMap<>();
        origen.forEach((clave, valor) -> {
            if (esClaveSensible(clave)) {
                return;
            }
            filtrado.put(clave, filtrarValor(valor));
        });
        return filtrado;
    }

    @SuppressWarnings("unchecked")
    private static Object filtrarValor(Object valor) {
        if (valor instanceof Map<?, ?> mapaAnidado) {
            return filtrar((Map<String, Object>) mapaAnidado);
        }
        if (valor instanceof List<?> listaAnidada) {
            List<Object> listaFiltrada = new ArrayList<>();
            for (Object elemento : listaAnidada) {
                listaFiltrada.add(filtrarValor(elemento));
            }
            return listaFiltrada;
        }
        return valor;
    }

    private static boolean esClaveSensible(String clave) {
        String claveEnMinusculas = clave.toLowerCase(Locale.ROOT);
        return FRAGMENTOS_CLAVE_SENSIBLE.stream().anyMatch(claveEnMinusculas::contains);
    }
}
