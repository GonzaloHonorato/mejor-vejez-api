package cl.adndigital.mejorvejez.comun;

import java.util.regex.Pattern;

/**
 * Regla única del teléfono chileno (contactos de emergencia, perfil de
 * usuario y WhatsApp): {@code +56} seguido de 9 dígitos. Se acepta con o sin
 * el prefijo ({@code +56} o {@code 56}) y con espacios o guiones entre los
 * dígitos ({@code +56 9 1234 5678}, {@code 912345678}, {@code 9-1234-5678});
 * se guarda siempre normalizado como {@code +56XXXXXXXXX}. El front aplica
 * la misma regla con el mismo mensaje.
 */
public final class TelefonoChileno {

    public static final String MENSAJE = "Ingresa un teléfono chileno: +56 y 9 dígitos.";

    private static final Pattern SEPARADORES = Pattern.compile("(?U)[\\s-]");
    private static final String CAMPO = "telefono";
    private static final Pattern FORMATO = Pattern.compile("^(?:\\+?56)?(\\d{9})$");

    private TelefonoChileno() {
    }

    /** Si {@code telefono} cumple la regla (un texto nulo o en blanco no la cumple). */
    public static boolean esValido(String telefono) {
        return normalizar(telefono) != null;
    }

    /** {@code +56XXXXXXXXX} si {@code telefono} cumple la regla; {@code null} si no. */
    public static String normalizar(String telefono) {
        if (telefono == null) {
            return null;
        }
        var coincidencia = FORMATO.matcher(SEPARADORES.matcher(telefono).replaceAll(""));
        return coincidencia.matches() ? "+56" + coincidencia.group(1) : null;
    }

    /**
     * Si {@code entrante} es el mismo teléfono que {@code actual}: igual en
     * crudo (recortado) o, si ambos cumplen la regla, igual normalizado. Un
     * teléfono antiguo que no cumple la regla solo es "el mismo" si llega
     * tal cual.
     */
    public static boolean mismoTelefono(String entrante, String actual) {
        if (entrante == null || actual == null) {
            return false;
        }
        if (entrante.strip().equals(actual.strip())) {
            return true;
        }
        String normalizado = normalizar(entrante);
        return normalizado != null && normalizado.equals(normalizar(actual));
    }

    /**
     * Teléfono opcional a guardar en lugar de {@code actual} (perfil y
     * administración de usuarios): {@code null} si viene vacío; {@code
     * actual} si es el mismo ({@link #mismoTelefono}), para que un teléfono
     * antiguo que no cumple la regla no impida guardar los demás cambios; si
     * no, el nuevo normalizado.
     *
     * @throws ExcepcionCampoInvalido el teléfono cambió y no cumple la regla.
     */
    public static String aGuardar(String entrante, String actual) {
        if (entrante == null || entrante.isBlank()) {
            return null;
        }
        if (mismoTelefono(entrante, actual)) {
            return actual;
        }
        String normalizado = normalizar(entrante);
        if (normalizado == null) {
            throw new ExcepcionCampoInvalido(CAMPO, MENSAJE);
        }
        return normalizado;
    }

    /**
     * Para los constructores compactos de las solicitudes: el valor
     * normalizado si es válido; si no, el texto recortado tal cual, para que
     * {@link TelefonoChilenoValido} lo rechace con el mensaje de la regla.
     * Un nulo o un texto vacío se devuelven sin cambios.
     */
    public static String normalizarSiEsValido(String telefono) {
        if (telefono == null || telefono.isBlank()) {
            return telefono == null ? null : telefono.strip();
        }
        String normalizado = normalizar(telefono);
        return normalizado != null ? normalizado : telefono.strip();
    }
}
