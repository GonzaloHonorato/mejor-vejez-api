package cl.adndigital.mejorvejez.cuentas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import cl.adndigital.mejorvejez.comun.ExcepcionDemasiadosIntentos;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * {@code @WebMvcTest} real (con un servlet de prueba, sin el contenedor
 * Tomcat de producción): no ejecuta la válvula de IP remota de Tomcat, así
 * que no puede probar que esa válvula decida confiar o no en un proxy. Lo
 * que sí prueba es que el controlador nunca lee {@code X-Forwarded-For} por
 * su cuenta: siempre usa {@code request.getRemoteAddr()}, que es lo único
 * que la válvula reescribe cuando corresponde.
 */
@WebMvcTest(ControladorRecuperarClave.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorRecuperarClaveTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioRecuperarClave servicioRecuperarClave;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rutaEsPublicaSinToken() throws Exception {
        mockMvc.perform(post("/api/publico/recuperar-clave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isAccepted());

        verify(servicioRecuperarClave).solicitar(eq("ana@correo.cl"), any());
    }

    @Test
    void unXForwardedForDeUnClienteDirectoNoCambiaLaIpUsada() throws Exception {
        mockMvc.perform(post("/api/publico/recuperar-clave")
                        .header("X-Forwarded-For", "9.9.9.9")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isAccepted());

        verify(servicioRecuperarClave).solicitar("ana@correo.cl", "203.0.113.9");
    }

    @Test
    void cuerpoSinEmailAunRespondeAceptado() throws Exception {
        mockMvc.perform(post("/api/publico/recuperar-clave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        verify(servicioRecuperarClave).solicitar(isNull(), any());
    }

    @Test
    void demasiadosIntentosDevuelve429ConSuTexto() throws Exception {
        doThrow(new ExcepcionDemasiadosIntentos()).when(servicioRecuperarClave).solicitar(any(), any());

        mockMvc.perform(post("/api/publico/recuperar-clave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Demasiados intentos. Prueba más tarde."));
    }

    @Test
    void bearerInvalidoNoRompeLaRutaPublica() throws Exception {
        mockMvc.perform(post("/api/publico/recuperar-clave")
                        .header("Authorization", "Bearer basura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isAccepted());

        verify(servicioRecuperarClave).solicitar(eq("ana@correo.cl"), any());
    }
}
