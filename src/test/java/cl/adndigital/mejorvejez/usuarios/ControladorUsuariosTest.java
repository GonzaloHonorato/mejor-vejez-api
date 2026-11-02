package cl.adndigital.mejorvejez.usuarios;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionCampoInvalido;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionCuentasNoConfiguradas;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.cuentas.CuentaCreada;
import cl.adndigital.mejorvejez.cuentas.ServicioAltaCuentas;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorUsuarios.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorUsuariosTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioAdministracionUsuarios servicio;

    @MockitoBean
    private ServicioAltaCuentas servicioAltaCuentas;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/usuarios"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamanoMayorA100SeLimitaA100() throws Exception {
        when(servicio.listar(isNull(), eq(0), eq(100))).thenReturn(new PageImpl<>(List.<Usuario>of()));

        mockMvc.perform(get("/api/usuarios").param("tamano", "500").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamano").value(100));

        verify(servicio).listar(null, 0, 100);
    }

    @Test
    void estadoNuloDevuelve400() throws Exception {
        mockMvc.perform(put("/api/usuarios/1/estado")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idEstado"));
    }

    @Test
    void paginaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get("/api/usuarios").param("pagina", "-1").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("La página no puede ser negativa."))
                .andExpect(content().string(not(containsString("must be"))));
    }

    @Test
    void tamanoCeroDevuelve400() throws Exception {
        mockMvc.perform(get("/api/usuarios").param("tamano", "0").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"));
    }

    @Test
    void fechasSalenConElOffsetDeSantiagoNoEnZ() throws Exception {
        OffsetDateTime fechaCreacionUtc = OffsetDateTime.parse("2026-10-27T00:23:04.066576Z");
        OffsetDateTime fechaActualizacionUtc = OffsetDateTime.parse("2026-10-27T03:10:05.123456Z");
        Usuario usuario = new Usuario("uid-1", "ana@correo.cl", "Ana", 1L, fechaCreacionUtc);
        ReflectionTestUtils.setField(usuario, "idUsuario", 1L);
        usuario.actualizarPerfil("Ana", null, fechaActualizacionUtc);

        when(servicio.listar(isNull(), eq(0), eq(20))).thenReturn(new PageImpl<>(List.of(usuario)));

        mockMvc.perform(get("/api/usuarios").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].fechaCreacion")
                        .value(ZonaNegocio.enZona(fechaCreacionUtc).toString()))
                .andExpect(jsonPath("$.elementos[0].fechaActualizacion")
                        .value(ZonaNegocio.enZona(fechaActualizacionUtc).toString()))
                .andExpect(jsonPath("$.elementos[0].fechaCreacion", not(containsString("Z"))))
                .andExpect(jsonPath("$.elementos[0].fechaActualizacion", not(containsString("Z"))));
    }

    // ------------------------------------------------------------------
    // Alta de cuentas y reenvío de accesos (HU-01)
    // ------------------------------------------------------------------

    @Test
    void crearCuentaDevuelve201ConLaClaveYElUsuario() throws Exception {
        Usuario usuario = new Usuario("uid-nuevo", "ana@correo.cl", "Ana", "+56912345678", 1L, OffsetDateTime.now());
        ReflectionTestUtils.setField(usuario, "idUsuario", 7L);
        when(servicioAltaCuentas.crearCuenta("Ana", "ana@correo.cl", "+56912345678"))
                .thenReturn(new CuentaCreada(usuario, "Clave-Temporal-1", true));

        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\",\"telefono\":\"+56912345678\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario.email").value("ana@correo.cl"))
                .andExpect(jsonPath("$.claveTemporal").value("Clave-Temporal-1"))
                .andExpect(jsonPath("$.correoEnviado").value(true));
    }

    @Test
    void crearCuentaSinNombreDevuelve400() throws Exception {
        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"));
    }

    @Test
    void crearCuentaConTelefonoNoChilenoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\",\"telefono\":\"+1 555 123 4567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));
    }

    @Test
    void crearCuentaNormalizaElTelefono() throws Exception {
        Usuario usuario = new Usuario("uid-nuevo", "ana@correo.cl", "Ana", "+56912345678", 1L, OffsetDateTime.now());
        ReflectionTestUtils.setField(usuario, "idUsuario", 7L);
        when(servicioAltaCuentas.crearCuenta("Ana", "ana@correo.cl", "+56912345678"))
                .thenReturn(new CuentaCreada(usuario, "Clave-Temporal-1", true));

        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\",\"telefono\":\"9 1234 5678\"}"))
                .andExpect(status().isCreated());

        verify(servicioAltaCuentas).crearCuenta("Ana", "ana@correo.cl", "+56912345678");
    }

    @Test
    void editarUsuarioConTelefonoInvalidoDevuelve400() throws Exception {
        when(servicio.actualizar(7L, "Ana", "12-34", false))
                .thenThrow(new ExcepcionCampoInvalido("telefono", "Ingresa un teléfono chileno: +56 y 9 dígitos."));

        mockMvc.perform(patch("/api/usuarios/7").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"telefono\":\"12-34\",\"esAdministrador\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));
    }

    @Test
    void crearCuentaExigeToken() throws Exception {
        mockMvc.perform(post("/api/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crearCuentaRecortaElCorreoAntesDeValidarlo() throws Exception {
        Usuario usuario = new Usuario("uid-nuevo", "ana@correo.cl", "Ana", 1L, OffsetDateTime.now());
        ReflectionTestUtils.setField(usuario, "idUsuario", 7L);
        when(servicioAltaCuentas.crearCuenta("Ana", "Ana@Correo.CL", null))
                .thenReturn(new CuentaCreada(usuario, "Clave-Temporal-1", true));

        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"  Ana@Correo.CL \"}"))
                .andExpect(status().isCreated());

        verify(servicioAltaCuentas).crearCuenta("Ana", "Ana@Correo.CL", null);
    }

    @Test
    void crearCuentaConCorreoExistenteDevuelve409ConDetailEnEspanol() throws Exception {
        when(servicioAltaCuentas.crearCuenta("Ana", "ana@correo.cl", null))
                .thenThrow(new ExcepcionConflicto("Ya existe una cuenta con ese correo"));

        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya existe una cuenta con ese correo"));
    }

    @Test
    void crearCuentaSinCredencialDevuelve503ConDetailEnEspanol() throws Exception {
        when(servicioAltaCuentas.crearCuenta("Ana", "ana@correo.cl", null))
                .thenThrow(new ExcepcionCuentasNoConfiguradas());

        mockMvc.perform(post("/api/usuarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"email\":\"ana@correo.cl\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("La creación de cuentas no está configurada"));
    }

    @Test
    void reenviarAccesoDevuelve200ConLaClaveNueva() throws Exception {
        Usuario usuario = new Usuario("uid-existente", "ana@correo.cl", "Ana", 1L, OffsetDateTime.now());
        when(servicioAltaCuentas.reenviarAcceso(7L)).thenReturn(new CuentaCreada(usuario, "Clave-Temporal-2", false));

        mockMvc.perform(post("/api/usuarios/7/reenviar-acceso").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claveTemporal").value("Clave-Temporal-2"))
                .andExpect(jsonPath("$.correoEnviado").value(false));
    }
}
