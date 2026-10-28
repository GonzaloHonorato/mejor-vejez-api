package cl.adndigital.mejorvejez.interno;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.configuracion.FiltroClaveServicio;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorInternoIntentos} (Fase 9, TSA-61): el
 * servicio es un mock, mismo patrón que {@code ControladorInternoAlertasTest}.
 */
@WebMvcTest(ControladorInternoIntentos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
@TestPropertySource(properties = "CLAVE_SERVICIO=" + ControladorInternoIntentosTest.CLAVE_CORRECTA)
class ControladorInternoIntentosTest {

    static final String CLAVE_CORRECTA = "clave-de-prueba-interna-correcta";

    private static final String URL = "/api/interno/intentos";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioReservaIntentos servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void registrarResultadoDevuelve204() throws Exception {
        mockMvc.perform(put(URL + "/21").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultado\":\"ENVIADO\",\"referenciaProveedor\":\"<ref@mejorvejez.test>\"}"))
                .andExpect(status().isNoContent());

        verify(servicio).registrarResultado(21L, new SolicitudResultadoIntento("ENVIADO", null,
                "<ref@mejorvejez.test>"));
    }

    @Test
    void registrarResultadoSobreIntentoInexistenteDevuelve404() throws Exception {
        org.mockito.Mockito.doThrow(new ExcepcionNoEncontrado("El intento indicado no existe."))
                .when(servicio).registrarResultado(99L, new SolicitudResultadoIntento("ENVIADO", null, null));

        mockMvc.perform(put(URL + "/99").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultado\":\"ENVIADO\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registrarResultadoSobreOtroFinalDevuelve409() throws Exception {
        org.mockito.Mockito.doThrow(new ExcepcionConflicto("El intento ya tiene otro resultado final."))
                .when(servicio).registrarResultado(21L, new SolicitudResultadoIntento("FALLIDO", "Error", null));

        mockMvc.perform(put(URL + "/21").header(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, CLAVE_CORRECTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultado\":\"FALLIDO\",\"detalleError\":\"Error\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto"));
    }

    @Test
    void rutaExigeClave() throws Exception {
        mockMvc.perform(put(URL + "/21").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resultado\":\"ENVIADO\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Clave de servicio inválida"));

        verifyNoInteractions(servicio);
        verifyNoInteractions(jwtDecoder);
    }
}
