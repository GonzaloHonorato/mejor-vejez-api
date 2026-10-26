package cl.adndigital.mejorvejez.tareas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorTareasManuales}. El servicio es un
 * mock: estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON y la traducción de excepciones (igual que {@code
 * ControladorAsignacionTareasTest}).
 */
@WebMvcTest(ControladorTareasManuales.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorTareasManualesTest {

    private static final String URL = "/api/grupos/1/tareas";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioTareasManuales servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinTipoTareaDevuelve400() throws Exception {
        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[*].campo").value(org.hamcrest.Matchers.hasItem("tipoTarea")));
    }

    @Test
    void cantidadCeroDevuelve400ConElErrorDeCampo() throws Exception {
        String cuerpo = "{"
                + "\"tipoTarea\":\"COMPRA_RETIRO_MEDICAMENTOS\","
                + "\"idAdultoMayor\":7,"
                + "\"titulo\":\"Comprar medicamentos\","
                + "\"fechaHorario\":\"2026-11-02T08:00:00-03:00\","
                + "\"compra\":[{\"idMedicamento\":1,\"idPresentacion\":2,\"cantidadSolicitada\":0,"
                + "\"idUnidadCantidad\":3}]"
                + "}";

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("compra[0].cantidadSolicitada"));
    }

    @Test
    void fechaHorarioSinOffsetDevuelve400() throws Exception {
        String cuerpo = "{"
                + "\"tipoTarea\":\"CITAS_CONTROLES_MEDICOS\","
                + "\"idAdultoMayor\":7,"
                + "\"titulo\":\"Control médico\","
                + "\"fechaHorario\":\"2026-11-02T08:00:00\","
                + "\"cita\":{\"nombreMedico\":\"Dra. Pérez\",\"lugarAtencion\":\"Clínica Central\"}"
                + "}";

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reglaDeNegocioDevuelve422ConDetail() throws Exception {
        when(servicio.crear(eq(1L), any(SolicitudCrearTareaManual.class)))
                .thenThrow(new ExcepcionReglaNegocio("Indica el médico y el lugar de la cita"));

        String cuerpo = "{"
                + "\"tipoTarea\":\"CITAS_CONTROLES_MEDICOS\","
                + "\"idAdultoMayor\":7,"
                + "\"titulo\":\"Control médico\","
                + "\"fechaHorario\":\"2026-11-02T08:00:00-03:00\""
                + "}";

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Indica el médico y el lugar de la cita"));
    }

    @Test
    void crearDevuelve201() throws Exception {
        RespuestaTarea respuesta = respuesta(5L, 7L);
        when(servicio.crear(eq(1L), any(SolicitudCrearTareaManual.class))).thenReturn(respuesta);

        String cuerpo = "{"
                + "\"tipoTarea\":\"CITAS_CONTROLES_MEDICOS\","
                + "\"idAdultoMayor\":7,"
                + "\"titulo\":\"Control médico\","
                + "\"fechaHorario\":\"2026-11-02T08:00:00-03:00\","
                + "\"cita\":{\"nombreMedico\":\"Dra. Pérez\",\"lugarAtencion\":\"Clínica Central\"}"
                + "}";

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idTarea").value(5))
                .andExpect(jsonPath("$.tipoTarea").value(CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS));
    }

    @Test
    void crearExigeToken() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaTarea respuesta(long idTarea, long idAdulto) {
        return new RespuestaTarea(idTarea, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, idAdulto,
                "Adulto Ejemplo", null, "Control médico", null, OffsetDateTime.parse("2026-11-02T08:00:00-03:00"),
                30, 1L, false, null, null, null, null, null, List.of(),
                new RespuestaCita("Dra. Pérez", "Clínica Central", null));
    }
}
