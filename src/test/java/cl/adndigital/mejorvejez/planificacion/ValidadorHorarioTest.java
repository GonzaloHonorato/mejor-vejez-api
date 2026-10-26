package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ValidadorHorario} como lógica pura (sin Spring ni BD). El
 * orden de las reglas y los mensajes exactos están fijados por el ruling del
 * controlador ({@code planes/.sdd/fase-3/horario-orden.md}), idéntico al de
 * {@code nucleo/planificacion/horario.ts} en el front.
 */
class ValidadorHorarioTest {

    private static final JsonMapper MAPEADOR = JsonMapper.builder().build();

    private final ValidadorHorario validador = new ValidadorHorario();

    private JsonNode json(String texto) {
        return MAPEADOR.readTree(texto);
    }

    @Test
    void horasFijasValidasSeOrdenan() {
        List<LocalTime> horas = validador.validarYExpandir(json("""
                {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["22:00", "08:00"]}
                """));

        assertThat(horas).containsExactly(LocalTime.of(8, 0), LocalTime.of(22, 0));
    }

    @Test
    void intervaloDelEjemploDelContrato() {
        List<LocalTime> horas = validador.validarYExpandir(json("""
                {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", "frecuencia_horas": 8}
                """));

        assertThat(horas).containsExactly(LocalTime.of(8, 0), LocalTime.of(16, 0));
    }

    @Test
    void intervaloDeFrecuencia24GeneraSoloElInicio() {
        List<LocalTime> horas = validador.validarYExpandir(json("""
                {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", "frecuencia_horas": 24}
                """));

        assertThat(horas).containsExactly(LocalTime.of(8, 0));
    }

    @Test
    void rechazaHorarioNulo() {
        assertThatThrownBy(() -> validador.validarYExpandir(null))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El horario es obligatorio");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casosFueraDeContrato")
    void rechazaFueraDeContrato(String descripcion, String horarioJson, String mensajeEsperado) {
        assertThatThrownBy(() -> validador.validarYExpandir(json(horarioJson)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(mensajeEsperado);
    }

    static Stream<Arguments> casosFueraDeContrato() {
        return Stream.of(
                Arguments.of("horario no es un objeto (arreglo)", "[]", "El horario es obligatorio"),
                Arguments.of("version ausente",
                        """
                        {"tipo": "HORAS_FIJAS", "horas": ["08:00"]}
                        """,
                        "Versión de horario no soportada (se espera 1)"),
                Arguments.of("version distinta de 1",
                        """
                        {"version": 2, "tipo": "HORAS_FIJAS", "horas": ["08:00"]}
                        """,
                        "Versión de horario no soportada (se espera 1)"),
                Arguments.of("tipo ausente",
                        """
                        {"version": 1}
                        """,
                        "Tipo de horario no válido"),
                Arguments.of("tipo desconocido",
                        """
                        {"version": 1, "tipo": "OTRO"}
                        """,
                        "Tipo de horario no válido"),
                Arguments.of("atributos de la otra modalidad mezclados en HORAS_FIJAS (2, orden alfabético)",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["08:00"], "hora_inicio": "08:00", \
                        "frecuencia_horas": 8}
                        """,
                        "El horario tiene atributos no permitidos: frecuencia_horas, hora_inicio"),
                Arguments.of("atributo de la otra modalidad mezclado en INTERVALO",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 8, "horas": ["08:00"]}
                        """,
                        "El horario tiene atributos no permitidos: horas"),
                Arguments.of("HORAS_FIJAS sin horas",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS"}
                        """,
                        "Indica al menos una hora"),
                Arguments.of("HORAS_FIJAS con horas vacío",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": []}
                        """,
                        "Indica al menos una hora"),
                Arguments.of("HORAS_FIJAS con hora de un solo dígito de hora",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["8:00"]}
                        """,
                        "Hora no válida: 8:00 (usa HH:mm)"),
                Arguments.of("HORAS_FIJAS con hora 24:00",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["24:00"]}
                        """,
                        "Hora no válida: 24:00 (usa HH:mm)"),
                Arguments.of("HORAS_FIJAS con minutos inválidos",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["08:60"]}
                        """,
                        "Hora no válida: 08:60 (usa HH:mm)"),
                Arguments.of("HORAS_FIJAS con horas repetidas",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["08:00", "08:00"]}
                        """,
                        "Hay horas repetidas"),
                Arguments.of("INTERVALO con frecuencia 0",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 0}
                        """,
                        "La frecuencia debe ser un número entero de horas entre 1 y 24"),
                Arguments.of("INTERVALO con frecuencia 25",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 25}
                        """,
                        "La frecuencia debe ser un número entero de horas entre 1 y 24"),
                Arguments.of("INTERVALO con frecuencia decimal",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 1.5}
                        """,
                        "La frecuencia debe ser un número entero de horas entre 1 y 24"),
                Arguments.of("INTERVALO con hora_fin igual a hora_inicio",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "08:00", \
                        "frecuencia_horas": 8}
                        """,
                        "La hora de fin debe ser posterior a la de inicio (sin cruzar medianoche)"),
                Arguments.of("INTERVALO con hora_fin anterior a hora_inicio",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "20:00", "hora_fin": "08:00", \
                        "frecuencia_horas": 8}
                        """,
                        "La hora de fin debe ser posterior a la de inicio (sin cruzar medianoche)"),
                // Adenda (horario-orden.md): enteros fuera de rango de int
                // (version/frecuencia_horas) son inválidos con su mensaje de
                // regla, nunca un 500 por el asInt() de Jackson.
                Arguments.of("version fuera de rango de int",
                        """
                        {"version": 4294967297, "tipo": "HORAS_FIJAS", "horas": ["08:00"]}
                        """,
                        "Versión de horario no soportada (se espera 1)"),
                Arguments.of("frecuencia_horas fuera de rango de int",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 4294967304}
                        """,
                        "La frecuencia debe ser un número entero de horas entre 1 y 24"),
                Arguments.of("frecuencia_horas como BigInteger enorme",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "20:00", \
                        "frecuencia_horas": 99999999999999999999999999}
                        """,
                        "La frecuencia debe ser un número entero de horas entre 1 y 24"),
                // Adenda: un texto vacío (o solo espacios) se representa
                // como "(vacía)", igual que una hora ausente.
                Arguments.of("hora_inicio vacía (texto en blanco)",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "", "hora_fin": "20:00", \
                        "frecuencia_horas": 8}
                        """,
                        "Hora no válida: (vacía) (usa HH:mm)"),
                Arguments.of("hora_inicio con solo espacios",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "   ", "hora_fin": "20:00", \
                        "frecuencia_horas": 8}
                        """,
                        "Hora no válida: (vacía) (usa HH:mm)"),
                Arguments.of("hora_fin vacía (texto en blanco), hora_inicio válida",
                        """
                        {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "hora_fin": "", \
                        "frecuencia_horas": 8}
                        """,
                        "Hora no válida: (vacía) (usa HH:mm)"),
                Arguments.of("elemento vacío dentro de horas (HORAS_FIJAS)",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": [""]}
                        """,
                        "Hora no válida: (vacía) (usa HH:mm)"),
                Arguments.of("un valor no textual dentro de horas se representa como su JSON",
                        """
                        {"version": 1, "tipo": "HORAS_FIJAS", "horas": [{"a": 1}]}
                        """,
                        "Hora no válida: {\"a\":1} (usa HH:mm)"));
    }

    @Test
    void intervaloConHoraInicioInvalidaYHoraFinAusenteReportaHoraInicio() {
        // Ruling (horario-orden.md, paso 6): INTERVALO valida hora_inicio
        // antes de hora_fin, así que aunque hora_fin también esté ausente,
        // el mensaje es sobre hora_inicio.
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 1, "tipo": "INTERVALO", "hora_inicio": "8:00", "frecuencia_horas": 8}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Hora no válida: 8:00 (usa HH:mm)");
    }

    @Test
    void intervaloConHoraFinAusenteYHoraInicioValidaReportaVacia() {
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 1, "tipo": "INTERVALO", "hora_inicio": "08:00", "frecuencia_horas": 8}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Hora no válida: (vacía) (usa HH:mm)");
    }

    @Test
    void versionYTipoInvalidosJuntosReportaVersion() {
        // Ruling: la versión se valida antes del tipo (paso 2 antes del 3).
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 2, "tipo": "OTRO"}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Versión de horario no soportada (se espera 1)");
    }

    @Test
    void atributosNoPermitidosConHorasVaciaReportaAtributos() {
        // Ruling: los atributos no permitidos (paso 4) se validan antes de
        // las reglas propias de HORAS_FIJAS (paso 5, "horas" vacío).
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 1, "tipo": "HORAS_FIJAS", "horas": [], "hora_inicio": "08:00"}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El horario tiene atributos no permitidos: hora_inicio");
    }

    @Test
    void primeraHoraInvalidaEnArregloSeReportaPrimero() {
        // Ruling (paso 5): cada hora se valida en orden; "24:00" (índice 1)
        // nunca se reporta porque "8:00" (índice 0) ya falla.
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 1, "tipo": "HORAS_FIJAS", "horas": ["8:00", "24:00"]}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Hora no válida: 8:00 (usa HH:mm)");
    }

    @Test
    void frecuenciaInvalidaYFinAntesDeInicioJuntosReportaFrecuencia() {
        // Ruling (paso 6): frecuencia_horas se valida antes que hora_fin ≤
        // hora_inicio, aunque ambas reglas estén violadas a la vez.
        assertThatThrownBy(() -> validador.validarYExpandir(json("""
                {"version": 1, "tipo": "INTERVALO", "hora_inicio": "20:00", "hora_fin": "08:00", \
                "frecuencia_horas": 0}
                """)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La frecuencia debe ser un número entero de horas entre 1 y 24");
    }
}
