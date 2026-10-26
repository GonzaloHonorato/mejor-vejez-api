package cl.adndigital.mejorvejez.planificacion;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;

import tools.jackson.databind.JsonNode;

/**
 * Valida el contrato versionado {@code HORARIO_JSON} (HU-04/HU-05, anexo
 * "6. Contrato versionado HORARIO_JSON") y lo expande a la lista de horas
 * que representa. El objeto respeta las claves literales del contrato
 * ({@code version}, {@code tipo}, {@code horas}, {@code hora_inicio},
 * {@code hora_fin}, {@code frecuencia_horas}), no el camelCase español del
 * resto de la API.
 *
 * <p>El orden de las reglas (y el mensaje exacto de cada una) está fijado
 * por el ruling del controlador y es idéntico al de
 * {@code nucleo/planificacion/horario.ts} en el front: 1) horario
 * obligatorio, 2) versión, 3) tipo, 4) atributos no permitidos, 5) reglas
 * propias de {@code HORAS_FIJAS}, o 6) reglas propias de {@code INTERVALO}.
 * Nunca termina en 400 ni 500: todo caso fuera de contrato es una
 * {@link ExcepcionReglaNegocio} (422).
 */
@Component
public class ValidadorHorario {

    static final String TIPO_HORAS_FIJAS = "HORAS_FIJAS";
    static final String TIPO_INTERVALO = "INTERVALO";

    private static final Set<String> ATRIBUTOS_HORAS_FIJAS = Set.of("version", "tipo", "horas");
    private static final Set<String> ATRIBUTOS_INTERVALO =
            Set.of("version", "tipo", "hora_inicio", "hora_fin", "frecuencia_horas");

    private static final Pattern PATRON_HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static final int FRECUENCIA_MINIMA = 1;
    private static final int FRECUENCIA_MAXIMA = 24;

    /**
     * Valida {@code horario} contra el contrato y devuelve las horas que
     * representa, ordenadas y sin duplicados. En {@code HORAS_FIJAS} son las
     * propias horas declaradas; en {@code INTERVALO} son la expansión desde
     * {@code hora_inicio}, sumando {@code frecuencia_horas}, mientras el
     * resultado sea {@code ≤ hora_fin} (ejemplo del contrato: 08:00 / 20:00 /
     * 8 → [08:00, 16:00]).
     *
     * @throws ExcepcionReglaNegocio el horario no respeta el contrato (ver mensajes en la clase).
     */
    public List<LocalTime> validarYExpandir(JsonNode horario) {
        if (horario == null || !horario.isObject()) {
            throw new ExcepcionReglaNegocio("El horario es obligatorio");
        }

        JsonNode nodoVersion = horario.get("version");
        if (nodoVersion == null || !nodoVersion.isIntegralNumber() || !nodoVersion.canConvertToInt()
                || nodoVersion.asInt() != 1) {
            throw new ExcepcionReglaNegocio("Versión de horario no soportada (se espera 1)");
        }

        JsonNode nodoTipo = horario.get("tipo");
        String tipo = nodoTipo != null && nodoTipo.isTextual() ? nodoTipo.asText() : null;
        if (!TIPO_HORAS_FIJAS.equals(tipo) && !TIPO_INTERVALO.equals(tipo)) {
            throw new ExcepcionReglaNegocio("Tipo de horario no válido");
        }

        validarAtributosPermitidos(horario, tipo);

        return TIPO_HORAS_FIJAS.equals(tipo) ? validarHorasFijas(horario) : validarIntervalo(horario);
    }

    private void validarAtributosPermitidos(JsonNode horario, String tipo) {
        Set<String> permitidos = TIPO_HORAS_FIJAS.equals(tipo) ? ATRIBUTOS_HORAS_FIJAS : ATRIBUTOS_INTERVALO;
        List<String> noPermitidos = horario.propertyNames().stream()
                .filter(nombre -> !permitidos.contains(nombre))
                .sorted()
                .toList();
        if (!noPermitidos.isEmpty()) {
            throw new ExcepcionReglaNegocio(
                    "El horario tiene atributos no permitidos: " + String.join(", ", noPermitidos));
        }
    }

    private List<LocalTime> validarHorasFijas(JsonNode horario) {
        JsonNode nodoHoras = horario.get("horas");
        if (nodoHoras == null || !nodoHoras.isArray() || nodoHoras.isEmpty()) {
            throw new ExcepcionReglaNegocio("Indica al menos una hora");
        }

        List<String> horas = new ArrayList<>();
        for (JsonNode nodoHora : nodoHoras) {
            horas.add(validarHora(nodoHora));
        }
        if (new HashSet<>(horas).size() != horas.size()) {
            throw new ExcepcionReglaNegocio("Hay horas repetidas");
        }

        return horas.stream().map(LocalTime::parse).sorted().toList();
    }

    private List<LocalTime> validarIntervalo(JsonNode horario) {
        String horaInicio = validarHora(horario.get("hora_inicio"));
        String horaFin = validarHora(horario.get("hora_fin"));

        JsonNode nodoFrecuencia = horario.get("frecuencia_horas");
        if (nodoFrecuencia == null || !nodoFrecuencia.isIntegralNumber() || !nodoFrecuencia.canConvertToInt()
                || nodoFrecuencia.asInt() < FRECUENCIA_MINIMA || nodoFrecuencia.asInt() > FRECUENCIA_MAXIMA) {
            throw new ExcepcionReglaNegocio("La frecuencia debe ser un número entero de horas entre 1 y 24");
        }
        int frecuencia = nodoFrecuencia.asInt();

        LocalTime inicio = LocalTime.parse(horaInicio);
        LocalTime fin = LocalTime.parse(horaFin);
        if (!fin.isAfter(inicio)) {
            throw new ExcepcionReglaNegocio(
                    "La hora de fin debe ser posterior a la de inicio (sin cruzar medianoche)");
        }

        return expandirIntervalo(inicio, fin, frecuencia);
    }

    /**
     * Expande en minutos (no en {@link LocalTime}, que da la vuelta a las
     * 24:00) para no confundir el fin del intervalo con medianoche del día
     * siguiente.
     */
    private List<LocalTime> expandirIntervalo(LocalTime inicio, LocalTime fin, int frecuenciaHoras) {
        int minutoInicio = inicio.getHour() * 60 + inicio.getMinute();
        int minutoFin = fin.getHour() * 60 + fin.getMinute();
        int pasoMinutos = frecuenciaHoras * 60;

        List<LocalTime> horas = new ArrayList<>();
        for (int minuto = minutoInicio; minuto <= minutoFin; minuto += pasoMinutos) {
            horas.add(LocalTime.of(minuto / 60, minuto % 60));
        }
        return horas;
    }

    /** Valida una hora en formato {@code HH:mm}; si no es válida, lanza el mensaje con la representación de `nodo`. */
    private String validarHora(JsonNode nodo) {
        if (nodo != null && nodo.isTextual() && PATRON_HORA.matcher(nodo.asText()).matches()) {
            return nodo.asText();
        }
        throw new ExcepcionReglaNegocio("Hora no válida: " + representarValor(nodo) + " (usa HH:mm)");
    }

    /**
     * Ausente (no se declaró la clave) o texto vacío/solo espacios →
     * "(vacía)" (adenda del ruling); texto no vacío → tal cual; otro tipo →
     * su representación JSON ({@code nodo.toString()}).
     */
    private String representarValor(JsonNode nodo) {
        if (nodo == null) {
            return "(vacía)";
        }
        if (nodo.isTextual()) {
            String texto = nodo.asText();
            return texto.isBlank() ? "(vacía)" : texto;
        }
        return nodo.toString();
    }
}
