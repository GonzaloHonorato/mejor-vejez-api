package cl.adndigital.mejorvejez.comun;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prueba {@link FiltroCorrelacion} de forma aislada: MockMvc standalone, sin
 * contexto Spring completo, porque el filtro no depende de ningún bean (no
 * necesita seguridad ni base de datos).
 */
class FiltroCorrelacionTest {

    private static final String PATRON_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
            .addFilter(new FiltroCorrelacion())
            .build();

    @Test
    void respetaCabeceraEntrante() throws Exception {
        mockMvc.perform(get("/prueba-correlacion")
                        .header(FiltroCorrelacion.CABECERA_ID_CORRELACION, "mi-id-123"))
                .andExpect(header().string(FiltroCorrelacion.CABECERA_ID_CORRELACION, "mi-id-123"));
    }

    @Test
    void generaCabeceraSiNoViene() throws Exception {
        mockMvc.perform(get("/prueba-correlacion"))
                .andExpect(header().string(FiltroCorrelacion.CABECERA_ID_CORRELACION, matchesPattern(PATRON_UUID)));
    }

    /** Controlador mínimo, solo para que MockMvc standalone tenga una ruta que atravesar. */
    @RestController
    static class ControladorDePrueba {

        @GetMapping("/prueba-correlacion")
        public void prueba() {
        }
    }
}
