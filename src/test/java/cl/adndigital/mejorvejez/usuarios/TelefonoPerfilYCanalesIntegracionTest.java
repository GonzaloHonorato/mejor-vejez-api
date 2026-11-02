package cl.adndigital.mejorvejez.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;

/**
 * Regla D2 del teléfono chileno en Mi perfil ({@code PATCH /api/yo}) y en el
 * número de WhatsApp de los canales ({@code PUT /api/yo/canales}), por HTTP
 * contra Oracle real (Testcontainers): lo inválido se rechaza con el mensaje
 * de la regla y lo válido se guarda normalizado como {@code +56XXXXXXXXX}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class TelefonoPerfilYCanalesIntegracionTest {

    private static final String MENSAJE = "Ingresa un teléfono chileno: +56 y 9 dígitos.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uid;
    private long idUsuario;

    @BeforeEach
    void crearUsuario() {
        uid = FixturaGrupos.uidUnico();
        idUsuario = new FixturaGrupos(jdbcTemplate).crearUsuario(uid, false);
    }

    @Test
    void perfilRechazaTelefonosNoChilenosSinGuardar() throws Exception {
        for (String invalido : List.of("12345678", "+54 9 11 2345 6789", "+56 9 1234 567", "abc")) {
            mockMvc.perform(autenticado(patch("/api/yo"))
                            .content("{\"nombre\":\"Ana\",\"telefono\":\"%s\"}".formatted(invalido)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                    .andExpect(jsonPath("$.errores[0].mensaje").value(MENSAJE));
        }

        assertThat(telefonoGuardado()).isNull();
    }

    @Test
    void perfilGuardaElTelefonoNormalizadoYAceptaBorrarlo() throws Exception {
        mockMvc.perform(autenticado(patch("/api/yo"))
                        .content("{\"nombre\":\"Ana\",\"telefono\":\"9 1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefono").value("+56912345678"));
        assertThat(telefonoGuardado()).isEqualTo("+56912345678");

        mockMvc.perform(autenticado(patch("/api/yo"))
                        .content("{\"nombre\":\"Ana\",\"telefono\":\"\"}"))
                .andExpect(status().isOk());
        assertThat(telefonoGuardado()).isNull();
    }

    @Test
    void canalesRechazanWhatsappNoChilenoYGuardanElValidoNormalizado() throws Exception {
        String correo = "canal-" + uid + "@ejemplo.cl";

        mockMvc.perform(autenticado(put("/api/yo/canales"))
                        .content(cuerpoCanales(correo, "+1 555 123 4567")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(MENSAJE));

        mockMvc.perform(autenticado(put("/api/yo/canales"))
                        .content(cuerpoCanales(correo, "+56 9 8765 4321")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canales[1].destino").value("+56987654321"));

        String destino = jdbcTemplate.queryForObject(
                "SELECT DESTINO FROM CANAL_NOTIFICACION_USUARIO WHERE ID_USUARIO = ? AND CANAL = 'WHATSAPP'",
                String.class, idUsuario);
        assertThat(destino).isEqualTo("+56987654321");
    }

    private String cuerpoCanales(String correo, String whatsapp) {
        return """
                {"canales":[{"canal":"CORREO","destino":"%s","habilitado":true},
                            {"canal":"WHATSAPP","destino":"%s","habilitado":false}]}
                """.formatted(correo, whatsapp);
    }

    private MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().jwt(constructor -> constructor.subject(uid).claim("email", uid + "@ejemplo.cl")))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String telefonoGuardado() {
        return jdbcTemplate.queryForObject("SELECT TELEFONO FROM USUARIO WHERE ID_USUARIO = ?", String.class,
                idUsuario);
    }
}
