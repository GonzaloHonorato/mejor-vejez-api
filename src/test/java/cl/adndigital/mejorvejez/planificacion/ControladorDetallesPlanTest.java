package cl.adndigital.mejorvejez.planificacion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorDetallesPlan}. El servicio es un mock;
 * en las pruebas de {@code horario} el mock delega en el {@link
 * ValidadorHorario} real con el {@code JsonNode} que Jackson ligó, para
 * comprobar de punta a punta que un horario con forma inesperada llega al
 * validador (422) y no muere antes en la deserialización (400) ni en un
 * error interno (500).
 */
@WebMvcTest(ControladorDetallesPlan.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class, ValidadorHorario.class})
class ControladorDetallesPlanTest {

    private static final String URL = "/api/grupos/1/planes/7/detalles";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ValidadorHorario validadorHorario;

    @MockitoBean
    private ServicioDetallesPlan servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void horarioMalformadoComoTextoDevuelve422NoDe500() throws Exception {
        delegarHorarioEnElValidador();

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("\"horario\":\"08:00\",", "\"dosisCantidad\":1,")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("El horario es obligatorio"));
    }

    /**
     * {@code "horario": null} explícito: Jackson liga un {@code NullNode}
     * (no un {@code null} de Java) en un campo {@code JsonNode}, así que
     * {@code @NotNull} no lo rechaza y el validador responde 422 "El horario
     * es obligatorio", igual que con cualquier otro valor que no es objeto.
     */
    @Test
    void horarioNuloExplicitoDevuelve422() throws Exception {
        delegarHorarioEnElValidador();

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("\"horario\":null,", "\"dosisCantidad\":1,")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("El horario es obligatorio"));
    }

    /** Sin la clave {@code horario}: el campo queda {@code null} y {@code @NotNull} responde 400. */
    @Test
    void horarioAusenteDevuelve400() throws Exception {
        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("", "\"dosisCantidad\":1,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("horario"));
        verifyNoInteractions(servicio);
    }

    @Test
    void dosisCeroDevuelve400() throws Exception {
        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(HORARIO_VALIDO, "\"dosisCantidad\":0,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("dosisCantidad"));
        verifyNoInteractions(servicio);
    }

    @Test
    void modoDeTerminoDesconocidoDevuelve400() throws Exception {
        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(HORARIO_VALIDO, "\"dosisCantidad\":1,")
                                .replace("\"AUTOMATICO\"", "\"A_VECES\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("modoCalculoTermino"));
    }

    @Test
    void crearDevuelve201() throws Exception {
        when(servicio.crear(eq(1L), eq(7L), any())).thenReturn(respuesta(42L));

        mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(HORARIO_VALIDO, "\"dosisCantidad\":1.5,")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", URL + "/42"))
                .andExpect(jsonPath("$.idDetallePlan").value(42))
                .andExpect(jsonPath("$.horas[0]").value("08:00"));
    }

    @Test
    void actualizarCantidadNegativaDevuelve400() throws Exception {
        mockMvc.perform(put(URL + "/42/cantidad").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadDisponible\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("cantidadDisponible"));
    }

    @Test
    void actualizarCantidadDevuelve200() throws Exception {
        when(servicio.actualizarCantidad(1L, 7L, 42L, new SolicitudActualizarCantidad(new BigDecimal("3"))))
                .thenReturn(respuesta(42L));

        mockMvc.perform(put(URL + "/42/cantidad").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadDisponible\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePlan").value(42));
    }

    @Test
    void eliminarDevuelve204() throws Exception {
        mockMvc.perform(delete(URL + "/42").with(jwt()))
                .andExpect(status().isNoContent());
        verify(servicio).eliminar(1L, 7L, 42L);
    }

    @Test
    void crearExigeToken() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(HORARIO_VALIDO, "\"dosisCantidad\":1,")))
                .andExpect(status().isUnauthorized());
    }

    private static final String HORARIO_VALIDO =
            "\"horario\":{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\"]},";

    private void delegarHorarioEnElValidador() {
        when(servicio.crear(eq(1L), eq(7L), any())).thenAnswer(invocacion -> {
            SolicitudGuardarDetalle solicitud = invocacion.getArgument(2);
            validadorHorario.validarYExpandir(solicitud.horario());
            return respuesta(42L);
        });
    }

    private String cuerpo(String horario, String dosis) {
        return "{\"idMedicamento\":3,\"idPresentacion\":4," + dosis + "\"idUnidadDosis\":5,"
                + "\"diasSemana\":[1,3]," + horario + "\"fechaInicio\":\"2026-11-02\","
                + "\"modoCalculoTermino\":\"AUTOMATICO\"}";
    }

    private RespuestaDetallePlan respuesta(long idDetalle) {
        return new RespuestaDetallePlan(idDetalle, 7L, 3L, "Paracetamol", 4L, "500 mg", null,
                new BigDecimal("1.5"), 5L, List.of(1L, 3L), List.of("08:00"), LocalDate.parse("2026-11-02"), null,
                null, null, null, "AUTOMATICO", null, 1L, OffsetDateTime.now(), null);
    }
}
