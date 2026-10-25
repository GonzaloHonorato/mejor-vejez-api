package cl.adndigital.mejorvejez.adultos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Prueba {@link ServicioContactos} contra Oracle real (Testcontainers), con
 * el mismo patrón que {@code ServicioAdultosMayoresTest}: datos propios con
 * uids/ids únicos, filtrados siempre por sus propios ids, nunca contando
 * tablas completas.
 *
 * <p>{@link #designacionesConcurrentesDejanUnSoloPrincipal()} usa dos hilos
 * con conexión propia (mismo patrón que {@code
 * ServicioIntegrantesTest#degradacionesConcurrentesConservanUnCoordinador()}):
 * el adulto ya tiene un principal P, y dos coordinadores del mismo grupo
 * designan a la vez dos contactos NO principales distintos (B y C) como
 * principal. Ambos hilos se autentican y quedan esperando en un
 * {@code CountDownLatch} de arranque compartido antes de llamar al servicio,
 * para que las dos llamadas se solapen de verdad (si uno llamara al servicio
 * y terminara antes de que el otro arrancara, el {@code SELECT ... FOR
 * UPDATE} no tendría nada que serializar). El bloqueo sobre el adulto mayor
 * serializa ambas llamadas; no hay carrera que rechazar (a diferencia del
 * último-coordinador de F1: acá cualquier contacto ACTIVO puede ser
 * principal, no hay un mínimo que proteger), así que ambas deben terminar
 * sin error y P debe quedar fuera (no principal) al final.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioContactosTest {

    @Autowired
    private ServicioContactos servicio;

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
    void primerContactoQuedaPrincipal() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");

        RespuestaContacto creado = servicio.crear(idGrupo, idAdulto,
                new SolicitudCrearContacto("Primer contacto", null, "+56912345678", null));

        assertThat(creado.esPrincipal()).isTrue();
    }

    @Test
    void segundoContactoNoEsPrincipalPorDefecto() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");

        servicio.crear(idGrupo, idAdulto, new SolicitudCrearContacto("Primero", null, "+56912345678", null));
        RespuestaContacto segundo = servicio.crear(idGrupo, idAdulto,
                new SolicitudCrearContacto("Segundo", null, "+56987654321", null));

        assertThat(segundo.esPrincipal()).isFalse();
    }

    @Test
    void crearConPrincipalReemplazaAlAnterior() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");

        RespuestaContacto primero = servicio.crear(idGrupo, idAdulto,
                new SolicitudCrearContacto("Primero", null, "+56912345678", null));
        RespuestaContacto segundo = servicio.crear(idGrupo, idAdulto,
                new SolicitudCrearContacto("Segundo", null, "+56987654321", true));

        assertThat(segundo.esPrincipal()).isTrue();

        Long cantidadPrincipalesActivos = contarPrincipalesActivos(idAdulto);
        assertThat(cantidadPrincipalesActivos).isEqualTo(1);

        String esPrincipalPrimero = jdbcTemplate.queryForObject(
                "SELECT ES_PRINCIPAL FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", String.class,
                primero.idContacto());
        assertThat(esPrincipalPrimero).isEqualTo("N");
    }

    @Test
    void designarPrincipalReemplazaAlAnterior() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        long idOtro = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        RespuestaContacto nuevoPrincipal = servicio.designarPrincipal(idGrupo, idAdulto, idOtro);

        assertThat(nuevoPrincipal.esPrincipal()).isTrue();
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);

        String esPrincipalAnterior = jdbcTemplate.queryForObject(
                "SELECT ES_PRINCIPAL FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", String.class, idPrincipal);
        assertThat(esPrincipalAnterior).isEqualTo("N");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESIGNAR_CONTACTO_PRINCIPAL'",
                String.valueOf(idOtro));
        assertThat(eventos).hasSize(1);
        assertThat((String) eventos.get(0).get("DETALLES_JSON")).contains(String.valueOf(idPrincipal));
    }

    @Test
    void designarContactoInactivoDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idInactivo = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "INACTIVO");

        assertThatThrownBy(() -> servicio.designarPrincipal(idGrupo, idAdulto, idInactivo))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Solo un contacto activo puede ser principal");
    }

    @Test
    void designandoYaPrincipalNoCambiaNiAudita() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");

        RespuestaContacto resultado = servicio.designarPrincipal(idGrupo, idAdulto, idPrincipal);

        assertThat(resultado.esPrincipal()).isTrue();
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_ENTIDAD FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESIGNAR_CONTACTO_PRINCIPAL'",
                String.valueOf(idPrincipal));
        assertThat(eventos).isEmpty();
    }

    @Test
    void designarPrincipalSinAnteriorAuditaIdContactoAnteriorNulo() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        RespuestaContacto designado = servicio.designarPrincipal(idGrupo, idAdulto, idContacto);

        assertThat(designado.esPrincipal()).isTrue();
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);

        String detallesJson = jdbcTemplate.queryForObject(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESIGNAR_CONTACTO_PRINCIPAL'",
                String.class, String.valueOf(idContacto));
        Map<String, Object> detalles = JsonMapper.builder().build()
                .readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles).containsKey("idContactoAnterior");
        assertThat(detalles.get("idContactoAnterior")).isNull();
    }

    @Test
    void designacionesConcurrentesDejanUnSoloPrincipal() throws InterruptedException {
        String uidA = FixturaGrupos.uidUnico();
        String uidB = FixturaGrupos.uidUnico();
        long idUsuarioA = fixturaGrupos.crearUsuario(uidA, false);
        long idUsuarioB = fixturaGrupos.crearUsuario(uidB, false);
        long idGrupo = fixturaGrupos.crearGrupo(idUsuarioA);
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuarioA, "COORDINADOR", "ACTIVO");
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuarioB, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idUsuarioA, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idUsuarioA, true, "ACTIVO");
        long idContactoB = fixturaAdultos.crearContacto(idAdulto, idUsuarioA, false, "ACTIVO");
        long idContactoC = fixturaAdultos.crearContacto(idAdulto, idUsuarioA, false, "ACTIVO");

        AtomicInteger exitosos = new AtomicInteger();
        AtomicInteger fallidos = new AtomicInteger();
        CountDownLatch inicio = new CountDownLatch(1);
        CountDownLatch terminaron = new CountDownLatch(2);

        Thread hiloA = new Thread(() -> intentarDesignar(uidA, idGrupo, idAdulto, idContactoB, inicio, exitosos,
                fallidos, terminaron));
        Thread hiloB = new Thread(() -> intentarDesignar(uidB, idGrupo, idAdulto, idContactoC, inicio, exitosos,
                fallidos, terminaron));
        hiloA.start();
        hiloB.start();
        // Ambos hilos ya están autenticados y esperando en `inicio` cuando se
        // libera: sus llamadas a designarPrincipal se solapan de verdad, no
        // una después de que la otra ya terminó.
        inicio.countDown();
        boolean terminaronATiempo = terminaron.await(10, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(fallidos.get()).isZero();
        assertThat(exitosos.get()).isEqualTo(2);
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);

        String esPrincipalOriginal = jdbcTemplate.queryForObject(
                "SELECT ES_PRINCIPAL FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", String.class, idPrincipal);
        assertThat(esPrincipalOriginal).isEqualTo("N");
    }

    private void intentarDesignar(String uid, long idGrupo, long idAdulto, long idContacto, CountDownLatch inicio,
            AtomicInteger exitosos, AtomicInteger fallidos, CountDownLatch terminaron) {
        try {
            autenticar(uid);
            inicio.await();
            servicio.designarPrincipal(idGrupo, idAdulto, idContacto);
            exitosos.incrementAndGet();
        } catch (RuntimeException inesperada) {
            fallidos.incrementAndGet();
        } catch (InterruptedException interrumpida) {
            Thread.currentThread().interrupt();
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    @Test
    void desactivarPrincipalExigeReemplazo() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto, idPrincipal, null))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Debes designar otro contacto principal antes de desactivar este");
    }

    @Test
    void desactivarPrincipalConReemplazoLoTraspasa() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        long idOtro = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        RespuestaContacto desactivado = servicio.desactivar(idGrupo, idAdulto, idPrincipal,
                new SolicitudDesactivarContacto(idOtro));

        assertThat(desactivado.esPrincipal()).isFalse();
        String esPrincipalOtro = jdbcTemplate.queryForObject(
                "SELECT ES_PRINCIPAL FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", String.class, idOtro);
        assertThat(esPrincipalOtro).isEqualTo("S");
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESACTIVAR_CONTACTO'",
                String.valueOf(idPrincipal));
        assertThat(eventos).hasSize(1);
        assertThat((String) eventos.get(0).get("DETALLES_JSON")).contains(String.valueOf(idOtro));
    }

    @Test
    void desactivarPrincipalConReemplazoDeIdMayorA127LoTraspasa() {
        // Regresión: idPropuesto y getIdContacto() son ambos Long; si se
        // comparan con `==` en vez de por valor, la comparación usa
        // identidad de referencia. El cache de Long.valueOf solo cubre
        // [-128, 127], así que con ids por sobre ese rango la comparación
        // fallaba siempre y el reemplazo válido se rechazaba con 422.
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long base = 900_000_000L + System.nanoTime() % 1_000_000L;
        long idPrincipal = fixturaAdultos.crearContactoConId(base + 1, idAdulto, idCoordinador, true, "ACTIVO");
        long idOtro = fixturaAdultos.crearContactoConId(base + 2, idAdulto, idCoordinador, false, "ACTIVO");
        assertThat(idPrincipal).isGreaterThan(127L);
        assertThat(idOtro).isGreaterThan(127L);

        RespuestaContacto desactivado = servicio.desactivar(idGrupo, idAdulto, idPrincipal,
                new SolicitudDesactivarContacto(idOtro));

        assertThat(desactivado.esPrincipal()).isFalse();
        String esPrincipalOtro = jdbcTemplate.queryForObject(
                "SELECT ES_PRINCIPAL FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", String.class, idOtro);
        assertThat(esPrincipalOtro).isEqualTo("S");
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);
    }

    @Test
    void desactivarPrincipalConReemplazoInvalidoDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");
        long idInactivoDelMismoAdulto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "INACTIVO");
        long idOtroAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContactoDeOtroAdulto = fixturaAdultos.crearContacto(idOtroAdulto, idCoordinador, false, "ACTIVO");

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto, idPrincipal,
                new SolicitudDesactivarContacto(idPrincipal)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El nuevo contacto principal debe ser otro contacto activo del adulto mayor");

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto, idPrincipal,
                new SolicitudDesactivarContacto(idInactivoDelMismoAdulto)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El nuevo contacto principal debe ser otro contacto activo del adulto mayor");

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto, idPrincipal,
                new SolicitudDesactivarContacto(idContactoDeOtroAdulto)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El nuevo contacto principal debe ser otro contacto activo del adulto mayor");
    }

    @Test
    void desactivarUnicoContactoDejaSinPrincipal() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idUnico = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");

        RespuestaContacto desactivado = servicio.desactivar(idGrupo, idAdulto, idUnico, null);

        assertThat(desactivado.esPrincipal()).isFalse();
        assertThat(contarPrincipalesActivos(idAdulto)).isZero();
    }

    @Test
    void reactivarContactoSinPrincipalLoVuelvePrincipal() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idUnico = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        servicio.desactivar(idGrupo, idAdulto, idUnico, null);

        RespuestaContacto reactivado = servicio.reactivar(idGrupo, idAdulto, idUnico);

        assertThat(reactivado.esPrincipal()).isTrue();
        assertThat(contarPrincipalesActivos(idAdulto)).isEqualTo(1);
    }

    @Test
    void reactivarYaActivoDevuelve409() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        assertThatThrownBy(() -> servicio.reactivar(idGrupo, idAdulto, idContacto))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El contacto ya está activo");
    }

    @Test
    void colaboradorPuedeConsultarContactosPeroNoModificarlos() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uidCoordinador, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        List<RespuestaContacto> listado = servicio.listar(idGrupo, idAdulto);
        assertThat(listado).extracting(RespuestaContacto::idContacto).contains(idContacto);

        assertThatThrownBy(() -> servicio.crear(idGrupo, idAdulto,
                new SolicitudCrearContacto("Nuevo", null, "+56911111111", null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto, idContacto,
                new SolicitudActualizarContacto("Otro nombre", null, "+56911111111")))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idAdulto, idContacto, null))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        long idResultadoRechazada = idEstado("EVENTO_AUDITORIA", "RESULTADO", "RECHAZADA");
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'GRUPO_FAMILIAR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACCESO_GRUPO' AND ID_RESULTADO = ?",
                String.valueOf(idGrupo), idResultadoRechazada);
        assertThat(eventos).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void contactoDeOtroAdultoDevuelve404() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdultoA = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idAdultoB = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContactoDeB = fixturaAdultos.crearContacto(idAdultoB, idCoordinador, true, "ACTIVO");

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdultoA, idContactoDeB,
                new SolicitudActualizarContacto("Nuevo nombre", null, "+56911111111")))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El contacto no existe");
    }

    @Test
    void contactoEnAdultoInactivoDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto, idContacto,
                new SolicitudActualizarContacto("Nuevo nombre", null, "+56911111111")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El adulto mayor está inactivo; reactívalo para modificarlo");
    }

    @Test
    void actualizarContactoInactivoDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "INACTIVO");

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idAdulto, idContacto,
                new SolicitudActualizarContacto("Nuevo nombre", null, "+56911111111")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El contacto está inactivo; reactívalo para modificarlo");
    }

    @Test
    void eliminarContactoSinHistorialLoBorra() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        servicio.eliminar(idGrupo, idAdulto, idContacto);

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", Long.class, idContacto);
        assertThat(cantidadFilas).isZero();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ELIMINAR_CONTACTO'",
                String.valueOf(idContacto));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void eliminarContactoConHistorialDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        servicio.desactivar(idGrupo, idAdulto, idContacto, null);
        servicio.reactivar(idGrupo, idAdulto, idContacto);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idAdulto, idContacto))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El contacto tiene historial; desactívalo en su lugar");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", Long.class, idContacto);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    @Test
    void eliminarPrincipalConOtrosActivosDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPrincipal = fixturaAdultos.crearContacto(idAdulto, idCoordinador, true, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCoordinador, false, "ACTIVO");

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idAdulto, idPrincipal))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Debes designar otro contacto principal antes de eliminar este");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM CONTACTO_EMERGENCIA WHERE ID_CONTACTO = ?", Long.class, idPrincipal);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    private long crearCoordinadorYAutenticar() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        autenticar(uid);
        return idCoordinador;
    }

    private Long contarPrincipalesActivos(long idAdulto) {
        long idEstadoActivo = idEstado("CONTACTO_EMERGENCIA", "ESTADO", "ACTIVO");
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM CONTACTO_EMERGENCIA WHERE ID_ADULTO_MAYOR = ? AND ES_PRINCIPAL = 'S' "
                        + "AND ID_ESTADO = ?",
                Long.class, idAdulto, idEstadoActivo);
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
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
