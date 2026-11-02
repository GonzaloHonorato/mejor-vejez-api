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

    private static final LocalDate NACIMIENTO = LocalDate.of(1945, 3, 10);

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

        SolicitudCrearAdultoMayor solicitud = new SolicitudCrearAdultoMayor("COD-1", "Juana Pérez",
                LocalDate.of(1940, 1, 1), "Notas de emergencia", "Observaciones generales", contactoPrincipal());

        RespuestaAdultoMayor creado = servicio.crear(idGrupo, solicitud);

        assertThat(creado.idGrupo()).isEqualTo(idGrupo);
        assertThat(creado.nombreCompleto()).isEqualTo("Juana Pérez");
        assertThat(creado.codigoInterno()).isEqualTo("COD-1");
        assertThat(creado.idEstado()).isEqualTo(idEstado("ADULTO_MAYOR", "ACTIVO"));
        assertThat(creado.contactoPrincipal()).isNotNull();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'ADULTO_MAYOR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_ADULTO_MAYOR'",
                String.valueOf(creado.idAdultoMayor()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void altaCreaElContactoPrincipalYAuditaAmbos() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);
        long idNieto = fixturaAdultos.idParentesco("NIETO");

        RespuestaAdultoMayor creado = servicio.crear(idGrupo, new SolicitudCrearAdultoMayor(null, "Rosa Díaz",
                NACIMIENTO, null, null, new SolicitudContactoPrincipal(" Pedro Díaz ", idNieto, "9 8765-4321")));

        RespuestaContacto principal = creado.contactoPrincipal();
        assertThat(principal.nombreContacto()).isEqualTo("Pedro Díaz");
        assertThat(principal.idParentesco()).isEqualTo(idNieto);
        assertThat(principal.parentesco()).isEqualTo("Nieto");
        assertThat(principal.telefono()).isEqualTo("+56987654321");
        assertThat(principal.esPrincipal()).isTrue();
        assertThat(creado.fechaNacimiento()).isEqualTo(NACIMIENTO);

        Map<String, Object> fila = jdbcTemplate.queryForMap(
                "SELECT ES_PRINCIPAL, ID_ESTADO, ID_PARENTESCO, TELEFONO FROM CONTACTO_EMERGENCIA "
                        + "WHERE ID_ADULTO_MAYOR = ?",
                creado.idAdultoMayor());
        assertThat(fila.get("ES_PRINCIPAL")).isEqualTo("S");
        assertThat(((Number) fila.get("ID_ESTADO")).longValue()).isEqualTo(idEstado("CONTACTO_EMERGENCIA", "ACTIVO"));
        assertThat(((Number) fila.get("ID_PARENTESCO")).longValue()).isEqualTo(idNieto);
        assertThat(fila.get("TELEFONO")).isEqualTo("+56987654321");

        Long eventosContacto = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_CONTACTO' AND ID_GRUPO = ?",
                Long.class, String.valueOf(principal.idContacto()), idGrupo);
        assertThat(eventosContacto).isEqualTo(1);

        assertThat(servicio.obtener(idGrupo, creado.idAdultoMayor()).contactoPrincipal().idContacto())
                .isEqualTo(principal.idContacto());
    }

    @Test
    void altaSinFechaDeNacimientoDevuelve422SinCrear() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.crear(idGrupo, alta(null, "Sin fecha", null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La fecha de nacimiento es obligatoria.");
        assertThat(adultosDelGrupo(idGrupo)).isZero();
    }

    @Test
    void altaSinContactoPrincipalDevuelve422SinCrear() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudCrearAdultoMayor(null, "Sin contacto", NACIMIENTO, null, null, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El contacto principal es obligatorio.");
        assertThat(adultosDelGrupo(idGrupo)).isZero();
    }

    @Test
    void altaConParentescoInvalidoDevuelve422SinCrearElAdulto() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);
        long idUnidad = jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = 'UNIDAD_DOSIS' AND CODIGO = 'ML'",
                Long.class);

        assertThatThrownBy(() -> servicio.crear(idGrupo, new SolicitudCrearAdultoMayor(null, "Parentesco malo",
                NACIMIENTO, null, null, new SolicitudContactoPrincipal("Pedro", idUnidad, "+56987654321"))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El parentesco indicado no es válido.");
        assertThat(adultosDelGrupo(idGrupo)).isZero();
    }

    @Test
    void actualizarSinFechaDeNacimientoDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto,
                new SolicitudGuardarAdultoMayor(null, "Adulto fixtura", null, null, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La fecha de nacimiento es obligatoria.");
    }

    @Test
    void codigoEnBlancoSeGuardaNuloYNoChoca() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        RespuestaAdultoMayor primero = servicio.crear(idGrupo, alta("  ", "Primer adulto", NACIMIENTO));
        RespuestaAdultoMayor segundo = servicio.crear(idGrupo, alta("  ", "Segundo adulto", NACIMIENTO));

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

        servicio.crear(idGrupo, alta("COD-DUP", "Primer adulto", NACIMIENTO));

        assertThatThrownBy(() -> servicio.crear(idGrupo, alta("COD-DUP", "Segundo adulto", NACIMIENTO)))
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
                alta(null, "Adulto futuro", fechaFutura)))
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

        SolicitudCrearAdultoMayor alta = new SolicitudCrearAdultoMayor("COD-X", "Nombre igual",
                LocalDate.of(1950, 5, 5), "Notas", "Observaciones", contactoPrincipal());
        SolicitudGuardarAdultoMayor datos = alta.perfil();
        RespuestaAdultoMayor creado = servicio.crear(idGrupo, alta);

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

        RespuestaAdultoMayor creado = servicio.crear(idGrupo, alta("COD-Y", "Nombre original", NACIMIENTO));

        RespuestaAdultoMayor actualizado = servicio.actualizar(idGrupo, creado.idAdultoMayor(),
                new SolicitudGuardarAdultoMayor("COD-Y", "Nombre nuevo", NACIMIENTO, null, null));

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
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", NACIMIENTO, null, null)))
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

        assertThatThrownBy(() -> servicio.crear(idGrupo, alta(null, "Nuevo adulto", NACIMIENTO)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto,
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", NACIMIENTO, null, null)))
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
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", NACIMIENTO, null, null)))
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
                new SolicitudGuardarAdultoMayor(null, "Nuevo nombre", NACIMIENTO, null, null)))
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

    private long adultosDelGrupo(long idGrupo) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ADULTO_MAYOR WHERE ID_GRUPO = ?", Long.class,
                idGrupo);
    }

    private SolicitudCrearAdultoMayor alta(String codigo, String nombre, LocalDate fechaNacimiento) {
        return new SolicitudCrearAdultoMayor(codigo, nombre, fechaNacimiento, null, null, contactoPrincipal());
    }

    private SolicitudContactoPrincipal contactoPrincipal() {
        return new SolicitudContactoPrincipal("Contacto del alta", fixturaAdultos.idParentesco("HIJO"),
                "+56 9 8765 4321");
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
