package cl.adndigital.mejorvejez.adultos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioAdultosMayores} contra Oracle real (Testcontainers).
 * Los datos los crea {@link FixturaGrupos} / {@link FixturaAdultos} con uids
 * e ids únicos; quedan confirmados en la base compartida, así que cada
 * prueba filtra siempre por sus propios ids y nunca cuenta tablas completas
 * (mismo patrón que {@code grupos.ServicioIntegrantesTest}).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAdultosMayoresTest {

    @Autowired
    private ServicioAdultosMayores servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void coordinadorCreaPerfilActivoYQuedaAuditado() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        SolicitudGuardarAdultoMayor solicitud = new SolicitudGuardarAdultoMayor("COD-1", "Juana Pérez",
                LocalDate.of(1940, 1, 1), "Notas de emergencia", "Observaciones generales");

        RespuestaAdultoMayor creado = servicio.crear(idGrupo, solicitud);

        assertThat(creado.idGrupo()).isEqualTo(idGrupo);
        assertThat(creado.nombreCompleto()).isEqualTo("Juana Pérez");
        assertThat(creado.codigoInterno()).isEqualTo("COD-1");
        assertThat(creado.idEstado()).isEqualTo(idEstado("ADULTO_MAYOR", "ACTIVO"));
        assertThat(creado.contactoPrincipal()).isNull();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_ADULTO_MAYOR'",
                String.valueOf(creado.idAdultoMayor()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void codigoEnBlancoSeGuardaNuloYNoChoca() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        RespuestaAdultoMayor primero = servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor("  ", "Primer adulto", null, null, null));
        RespuestaAdultoMayor segundo = servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor("  ", "Segundo adulto", null, null, null));

        assertThat(primero.codigoInterno()).isNull();
        assertThat(segundo.codigoInterno()).isNull();
    }

    @Test
    void codigoDuplicadoEnElGrupoDevuelve409() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        servicio.crear(idGrupo, new SolicitudGuardarAdultoMayor("COD-DUP", "Primer adulto", null, null, null));

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor("COD-DUP", "Segundo adulto", null, null, null)))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("Ya existe un adulto mayor con ese código en el grupo");
    }

    @Test
    void fechaNacimientoFuturaDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        LocalDate fechaFutura = LocalDate.now().plusYears(1);

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor(null, "Adulto futuro", fechaFutura, null, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La fecha de nacimiento no puede ser futura");
    }

    @Test
    void actualizarSinCambiosNoAudita() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        SolicitudGuardarAdultoMayor datos = new SolicitudGuardarAdultoMayor("COD-X", "Nombre igual",
                LocalDate.of(1950, 5, 5), "Notas", "Observaciones");
        RespuestaAdultoMayor creado = servicio.crear(idGrupo, datos);

        RespuestaAdultoMayor actualizado = servicio.actualizar(idGrupo, creado.idAdultoMayor(), datos);

        assertThat(actualizado.fechaActualizacion()).isNull();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT CODIGO_ACCION FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_ADULTO_MAYOR'",
                String.valueOf(creado.idAdultoMayor()));
        assertThat(eventos).isEmpty();
    }

    @Test
    void actualizarConCambiosAuditaLosCamposModificados() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        RespuestaAdultoMayor creado = servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor("COD-Y", "Nombre original", null, null, null));

        RespuestaAdultoMayor actualizado = servicio.actualizar(idGrupo, creado.idAdultoMayor(),
                new SolicitudGuardarAdultoMayor("COD-Y", "Nombre nuevo", null, null, null));

        assertThat(actualizado.nombreCompleto()).isEqualTo("Nombre nuevo");
        assertThat(actualizado.fechaActualizacion()).isNotNull();

        String detallesJson = jdbcTemplate.queryForObject(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_ADULTO_MAYOR'",
                String.class, String.valueOf(creado.idAdultoMayor()));
        Map<String, Object> detalles = JsonMapper.builder().build()
                .readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        List<String> camposModificados = (List<String>) detalles.get("camposModificados");
        assertThat(camposModificados).containsExactly("nombreCompleto");
    }

    @Test
    void desactivacionEsLogicaYConservaContactos() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        autenticar(uid);

        RespuestaAdultoMayor desactivado = servicio.desactivar(idGrupo, idAdulto);

        assertThat(desactivado.idEstado()).isEqualTo(idEstado("ADULTO_MAYOR", "INACTIVO"));

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ADULTO_MAYOR WHERE ID_ADULTO_MAYOR = ?", Long.class, idAdulto);
        assertThat(cantidadFilas).isEqualTo(1);

        Long idEstadoContacto = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", Long.class, idContacto);
        assertThat(idEstadoContacto).isEqualTo(idEstado("CONTACTO_EMERGENCIA", "ACTIVO"));

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESACTIVAR_ADULTO_MAYOR'",
                String.valueOf(idAdulto));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void desactivarYaInactivoDevuelve409() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El adulto mayor ya está inactivo");
    }

    @Test
    void modificarAdultoInactivoDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto,
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", null, null, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El adulto mayor está inactivo; reactívalo para modificarlo");
    }

    @Test
    void reactivarAdultoActivoDevuelve409() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.reactivar(idGrupo, idAdulto))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El adulto mayor ya está activo");
    }

    @Test
    void colaboradorPuedeConsultarPeroNoModificar() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uidCoordinador, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        List<RespuestaAdultoMayor> listado = servicio.listar(idGrupo, false);
        assertThat(listado).extracting(RespuestaAdultoMayor::idAdultoMayor).contains(idAdulto);

        RespuestaAdultoMayor obtenido = servicio.obtener(idGrupo, idAdulto);
        assertThat(obtenido.idAdultoMayor()).isEqualTo(idAdulto);

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudGuardarAdultoMayor(null, "Nuevo adulto", null, null, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto,
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", null, null, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto)).isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void miembroDeOtroGrupoRecibe403() {
        String uidCoordinadorA = FixturaGrupos.uidUnico();
        long idCoordinadorA = fixturaGrupos.crearUsuario(uidCoordinadorA, false);
        long idGrupoA = fixturaGrupos.crearGrupo(idCoordinadorA);
        fixturaGrupos.agregarIntegrante(idGrupoA, idCoordinadorA, "COORDINADOR", "ACTIVO");

        String uidCoordinadorB = FixturaGrupos.uidUnico();
        long idCoordinadorB = fixturaGrupos.crearUsuario(uidCoordinadorB, false);
        long idGrupoB = fixturaGrupos.crearGrupo(idCoordinadorB);
        fixturaGrupos.agregarIntegrante(idGrupoB, idCoordinadorB, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinadorB);

        assertThatThrownBy(() -> servicio.actualizar(idGrupoA, 999_999_999L,
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", null, null, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void adultoDeOtroGrupoDevuelve404() {
        String uidCoordinadorA = FixturaGrupos.uidUnico();
        long idCoordinadorA = fixturaGrupos.crearUsuario(uidCoordinadorA, false);
        long idGrupoA = fixturaGrupos.crearGrupo(idCoordinadorA);
        fixturaGrupos.agregarIntegrante(idGrupoA, idCoordinadorA, "COORDINADOR", "ACTIVO");

        long idUsuarioB = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixturaGrupos.crearGrupo(idUsuarioB);
        long idAdultoB = fixturaAdultos.crearAdulto(idGrupoB, idUsuarioB, "ACTIVO");
        autenticar(uidCoordinadorA);

        assertThatThrownBy(() -> servicio.actualizar(idGrupoA, idAdultoB,
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", null, null, null)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    @Test
    void administradorSinMembresiaModificaConTrazabilidad() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");
        autenticar(uidAdmin);

        servicio.desactivar(idGrupo, idAdulto);

        String detallesJson = jdbcTemplate.queryForObject(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESACTIVAR_ADULTO_MAYOR'",
                String.class, String.valueOf(idAdulto));
        Map<String, Object> detalles = JsonMapper.builder().build()
                .readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    @Test
    void listarPorDefectoSoloActivosConSuPrincipal() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idActivo = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContactoPrincipal = fixturaAdultos.crearContacto(idActivo, idCoordinador, true, "ACTIVO");
        fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        autenticar(uid);

        List<RespuestaAdultoMayor> listado = servicio.listar(idGrupo, false);

        assertThat(listado).extracting(RespuestaAdultoMayor::idAdultoMayor).containsExactly(idActivo);
        assertThat(listado.get(0).contactoPrincipal().idContacto()).isEqualTo(idContactoPrincipal);
    }

    @Test
    void listarConInactivosLosPoneAlFinal() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idInactivo = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        long idActivo = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        autenticar(uid);

        List<RespuestaAdultoMayor> listado = servicio.listar(idGrupo, true);

        assertThat(listado).extracting(RespuestaAdultoMayor::idAdultoMayor).containsExactly(idActivo, idInactivo);
    }

    @Test
    void eliminarSinReferenciasBorraLaFila() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        autenticar(uid);

        servicio.eliminar(idGrupo, idAdulto);

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ADULTO_MAYOR WHERE ID_ADULTO_MAYOR = ?", Long.class, idAdulto);
        assertThat(cantidadFilas).isZero();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ELIMINAR_ADULTO_MAYOR'",
                String.valueOf(idAdulto));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void eliminarConContactoDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idAdulto))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El adulto mayor tiene contactos, historial o referencias; desactívalo en su lugar");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ADULTO_MAYOR WHERE ID_ADULTO_MAYOR = ?", Long.class, idAdulto);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private void autenticar(String uid) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject(uid)
                .claim("email", uid + "@ejemplo.cl")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
