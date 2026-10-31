package cl.adndigital.mejorvejez.comun;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorPruebaErrores.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ManejadorErroresTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void noEncontradoDevuelve404EnEspanol() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/no-encontrado").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.detail").value("El recurso solicitado no existe."));
    }

    @Test
    void reglaDeNegocioDevuelve422() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/regla-negocio").with(jwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regla de negocio"))
                .andExpect(jsonPath("$.detail").value("La operación viola una regla de negocio."));
    }

    @Test
    void conflictoDevuelve409() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/conflicto").with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicto"))
                .andExpect(jsonPath("$.detail").value("El recurso ya existe."));
    }

    @Test
    void accesoDenegadoDevuelve403() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/acceso-denegado").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"))
                .andExpect(jsonPath("$.detail").value("No tiene permisos para esta operación."));
    }

    @Test
    void cuentasNoConfiguradasDevuelve503() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/cuentas-no-configuradas").with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Servicio no disponible"))
                .andExpect(jsonPath("$.detail").value("La creación de cuentas no está configurada"));
    }

    @Test
    void demasiadosIntentosDevuelve429() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/demasiados-intentos").with(jwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Demasiados intentos"))
                .andExpect(jsonPath("$.detail").value("Demasiados intentos. Prueba más tarde."));
    }

    @Test
    void validacionDevuelve400ConCampos() throws Exception {
        mockMvc.perform(post("/api/prueba-errores/validacion")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"));
    }

    @Test
    void errorDeIntegridadDevuelve409SinDetalleSql() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/integridad").with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La operación entra en conflicto con datos existentes."))
                .andExpect(content().string(not(containsString("ORA-"))))
                .andExpect(content().string(not(containsString("CONSTRAINT"))));
    }

    @Test
    void errorNoControladoNoFiltraDetallesInternos() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/error-no-controlado").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Error interno"))
                .andExpect(jsonPath("$.detail").value("Ocurrió un error inesperado."))
                .andExpect(content().string(not(containsString("ORA-"))))
                .andExpect(content().string(not(containsString("TABLA_X"))));
    }

    @Test
    void parametroFaltanteDevuelve400() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/parametro").with(jwt())
                        .header(FiltroCorrelacion.CABECERA_ID_CORRELACION, "id-prueba-400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.detail").value("Falta el parámetro obligatorio 'valor'."))
                .andExpect(jsonPath("$.idCorrelacion").value("id-prueba-400"));
    }

    @Test
    void jsonMalformadoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/prueba-errores/validacion")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.detail").value("El cuerpo de la solicitud no es válido."))
                .andExpect(content().string(not(containsString("SolicitudPrueba"))))
                .andExpect(content().string(not(containsString("Jackson"))));
    }

    @Test
    void rutaInexistenteDevuelve404() throws Exception {
        mockMvc.perform(get("/api/prueba-errores/no-existe").with(jwt())
                        .header(FiltroCorrelacion.CABECERA_ID_CORRELACION, "id-prueba-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.detail").value("El recurso solicitado no existe."))
                .andExpect(jsonPath("$.idCorrelacion").value("id-prueba-404"));
    }

    @Test
    void metodoNoPermitidoDevuelve405() throws Exception {
        mockMvc.perform(put("/api/prueba-errores/no-encontrado").with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Método no permitido"))
                .andExpect(jsonPath("$.detail").value("El método HTTP no está permitido para este recurso."));
    }

    @Test
    void tipoDeContenidoNoSoportadoDevuelve415() throws Exception {
        mockMvc.perform(post("/api/prueba-errores/validacion")
                        .with(jwt())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Tipo de contenido no soportado"))
                .andExpect(jsonPath("$.detail").value("El tipo de contenido de la solicitud no está soportado."));
    }
}
