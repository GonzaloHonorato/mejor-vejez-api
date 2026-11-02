package cl.adndigital.mejorvejez.usuarios;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionCampoInvalido;
import cl.adndigital.mejorvejez.comun.ExcepcionNoAutenticado;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;
import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;

@WebMvcTest(ControladorYo.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorYoTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioUsuarioActual servicioUsuarioActual;

    @MockitoBean
    private ServicioPerfil servicioPerfil;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void yoDevuelveDatosDelUsuario() throws Exception {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getIdUsuario()).thenReturn(7L);
        when(usuario.getNombre()).thenReturn("Ana");
        when(usuario.getEmail()).thenReturn("a@b.cl");
        when(usuario.getEsAdministrador()).thenReturn(false);
        when(servicioUsuarioActual.obtener()).thenReturn(usuario);

        mockMvc.perform(get("/api/yo")
                        .with(jwt().jwt(j -> j.subject("uid-1").claim("email", "a@b.cl").claim("name", "Ana"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idUsuario").value(7))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.email").value("a@b.cl"))
                .andExpect(jsonPath("$.esAdministrador").value(false));
    }

    @Test
    void sesionInvalidaDevuelve401ConProblemDetail() throws Exception {
        when(servicioUsuarioActual.obtener())
                .thenThrow(new ExcepcionNoAutenticado("Se requiere una sesión válida."));

        mockMvc.perform(get("/api/yo").with(jwt()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.detail").value("Se requiere una sesión válida."));
    }

    @Test
    void yoSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/yo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nombreVacioDevuelve400ConCampo() throws Exception {
        mockMvc.perform(patch("/api/yo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"\", \"telefono\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"));
    }

    @Test
    void telefonoInvalidoDevuelve400() throws Exception {
        when(servicioPerfil.actualizar(any(), eq("Ana"), eq("12345678")))
                .thenThrow(new ExcepcionCampoInvalido("telefono", "Ingresa un teléfono chileno: +56 y 9 dígitos."));

        mockMvc.perform(patch("/api/yo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"Ana\", \"telefono\": \"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));
    }

    @Test
    void telefonoValidoLlegaNormalizadoAlServicio() throws Exception {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getIdUsuario()).thenReturn(7L);
        when(usuario.getNombre()).thenReturn("Ana");
        when(usuario.getEmail()).thenReturn("a@b.cl");
        when(usuario.getTelefono()).thenReturn("+56912345678");
        when(usuario.getEsAdministrador()).thenReturn(false);
        when(servicioUsuarioActual.obtener()).thenReturn(usuario);
        when(servicioPerfil.actualizar(any(Usuario.class), eq("Ana"), eq("+56912345678"))).thenReturn(usuario);

        mockMvc.perform(patch("/api/yo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"Ana\", \"telefono\": \"+56 9 1234 5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefono").value("+56912345678"));
    }

    @Test
    void telefonoVacioSeAcepta() throws Exception {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getIdUsuario()).thenReturn(7L);
        when(usuario.getNombre()).thenReturn("Ana");
        when(usuario.getEmail()).thenReturn("a@b.cl");
        when(usuario.getTelefono()).thenReturn(null);
        when(usuario.getEsAdministrador()).thenReturn(false);
        when(servicioUsuarioActual.obtener()).thenReturn(usuario);
        when(servicioPerfil.actualizar(any(Usuario.class), eq("Ana"), eq(""))).thenReturn(usuario);

        mockMvc.perform(patch("/api/yo")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"Ana\", \"telefono\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefono").value(Matchers.nullValue()));
    }

    @Test
    void patchSinTokenDevuelve401() throws Exception {
        mockMvc.perform(patch("/api/yo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"Ana\"}"))
                .andExpect(status().isUnauthorized());
    }
}
