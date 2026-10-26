package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.planificacion.ServicioMedicamentos.ResultadoMedicamento;

/**
 * Prueba {@link ServicioMedicamentos} contra Oracle real (Testcontainers),
 * con el mismo patrón que {@code adultos.ServicioContactosTest}: datos
 * propios con uids/nombres únicos, filtrados siempre por sus propios ids,
 * nunca contando tablas completas.
 *
 * <p>{@link #altasConcurrentesDelMismoMedicamentoDevuelvenElMismo()} usa dos
 * hilos con conexión propia (mismo patrón que {@code
 * ServicioContactosTest#designacionesConcurrentesDejanUnSoloPrincipal()}):
 * dos coordinadores del mismo grupo dan de alta, a la vez, el mismo
 * medicamento (mismo nombre/presentación/concentración). Ambos hilos se
 * autentican y quedan esperando en un {@code CountDownLatch} de arranque
 * compartido antes de llamar al servicio, para que las dos llamadas se
 * solapen de verdad.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioMedicamentosTest {

    @Autowired
    private ServicioMedicamentos servicio;

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
    void crearMedicamentoNuevoDevuelve201YAudita() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String nombre = "Medicamento " + UUID.randomUUID();

        ResultadoMedicamento resultado = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(nombre, "Paracetamol", idPresentacion, "500 mg"));

        assertThat(resultado.creado()).isTrue();
        assertThat(resultado.medicamento().nombre()).isEqualTo(nombre);
        assertThat(resultado.medicamento().principioActivo()).isEqualTo("Paracetamol");
        assertThat(resultado.medicamento().idPresentacion()).isEqualTo(idPresentacion);
        assertThat(resultado.medicamento().usadoEnGrupo()).isFalse();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'MEDICAMENTO' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_MEDICAMENTO'",
                String.valueOf(resultado.medicamento().idMedicamento()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void crearDuplicadoNormalizadoDevuelveElExistente() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String nombreBase = "Paracetamol-" + UUID.randomUUID();

        ResultadoMedicamento primero = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento("  " + nombreBase + " ", null, idPresentacion, "500 mg"));
        assertThat(primero.creado()).isTrue();

        ResultadoMedicamento segundo = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(nombreBase.toUpperCase(Locale.ROOT), null, idPresentacion, " 500 MG"));

        assertThat(segundo.creado()).isFalse();
        assertThat(segundo.medicamento().idMedicamento()).isEqualTo(primero.medicamento().idMedicamento());

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'MEDICAMENTO' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_MEDICAMENTO'",
                String.valueOf(primero.medicamento().idMedicamento()));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void mismoNombreConOtraConcentracionEsOtroMedicamento() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String nombre = "Ibuprofeno-" + UUID.randomUUID();

        ResultadoMedicamento primero = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(nombre, null, idPresentacion, "400 mg"));
        ResultadoMedicamento segundo = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(nombre, null, idPresentacion, "600 mg"));

        assertThat(primero.creado()).isTrue();
        assertThat(segundo.creado()).isTrue();
        assertThat(segundo.medicamento().idMedicamento()).isNotEqualTo(primero.medicamento().idMedicamento());
    }

    @Test
    void presentacionDeOtroCatalogoDevuelve422() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idUnidadDosis = idOpcion("UNIDAD_DOSIS", "MG");
        String nombre = "Medicamento-" + UUID.randomUUID();

        assertThatThrownBy(() -> servicio.crear(idGrupo, new SolicitudCrearMedicamento(nombre, null, idUnidadDosis, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class);
    }

    @Test
    void altasConcurrentesDelMismoMedicamentoDevuelvenElMismo() throws InterruptedException {
        String uidA = FixturaGrupos.uidUnico();
        String uidB = FixturaGrupos.uidUnico();
        long idUsuarioA = fixturaGrupos.crearUsuario(uidA, false);
        long idUsuarioB = fixturaGrupos.crearUsuario(uidB, false);
        long idGrupo = fixturaGrupos.crearGrupo(idUsuarioA);
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuarioA, "COORDINADOR", "ACTIVO");
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuarioB, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String nombre = "Medicamento concurrente " + UUID.randomUUID();
        SolicitudCrearMedicamento solicitud = new SolicitudCrearMedicamento(nombre, null, idPresentacion, "100 mg");

        List<Long> idsCreados = new CopyOnWriteArrayList<>();
        AtomicInteger fallidos = new AtomicInteger();
        CountDownLatch inicio = new CountDownLatch(1);
        CountDownLatch terminaron = new CountDownLatch(2);

        Thread hiloA = new Thread(
                () -> intentarCrear(uidA, idGrupo, solicitud, inicio, idsCreados, fallidos, terminaron));
        Thread hiloB = new Thread(
                () -> intentarCrear(uidB, idGrupo, solicitud, inicio, idsCreados, fallidos, terminaron));
        hiloA.start();
        hiloB.start();
        // Ambos hilos ya están autenticados y esperando en `inicio` cuando se
        // libera: sus llamadas a crear() se solapan de verdad.
        inicio.countDown();
        boolean terminaronATiempo = terminaron.await(10, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(fallidos.get()).isZero();
        assertThat(idsCreados).hasSize(2);
        assertThat(idsCreados.get(0)).isEqualTo(idsCreados.get(1));

        Long cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM MEDICAMENTO WHERE UPPER(TRIM(NOMBRE)) = ?", Long.class,
                nombre.toUpperCase(Locale.ROOT));
        assertThat(cantidad).isEqualTo(1);
    }

    private void intentarCrear(String uid, long idGrupo, SolicitudCrearMedicamento solicitud, CountDownLatch inicio,
            List<Long> idsCreados, AtomicInteger fallidos, CountDownLatch terminaron) {
        try {
            autenticar(uid);
            inicio.await();
            ResultadoMedicamento resultado = servicio.crear(idGrupo, solicitud);
            idsCreados.add(resultado.medicamento().idMedicamento());
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
    void buscarPriorizaLosUsadosEnElGrupo() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidadDosis = idOpcion("UNIDAD_DOSIS", "MG");
        String prefijo = "ZMed" + UUID.randomUUID().toString().substring(0, 8);

        // Alfabéticamente "-A" iría primero; el usado en el grupo debe
        // adelantarse igual porque usadoEnGrupo pesa más que el nombre.
        ResultadoMedicamento noUsado = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(prefijo + "-A", null, idPresentacion, null));
        ResultadoMedicamento usado = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(prefijo + "-Z", null, idPresentacion, null));
        marcarUsadoEnGrupo(idGrupo, idAdulto, idCoordinador, usado.medicamento().idMedicamento(), idPresentacion,
                idUnidadDosis);

        List<RespuestaMedicamento> resultados = servicio.buscar(idGrupo, prefijo);

        assertThat(resultados).extracting(RespuestaMedicamento::idMedicamento)
                .containsExactly(usado.medicamento().idMedicamento(), noUsado.medicamento().idMedicamento());
        assertThat(resultados.get(0).usadoEnGrupo()).isTrue();
        assertThat(resultados.get(1).usadoEnGrupo()).isFalse();
    }

    @Test
    void buscarEscapaComodines() {
        long idCoordinador = crearCoordinadorYAutenticar();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "SOLUCION_ORAL");
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String nombreConPorcentaje = "M" + sufijo + "50%";
        String nombreSinPorcentaje = "M" + sufijo + "50X";

        ResultadoMedicamento conPorcentaje = servicio.crear(idGrupo,
                new SolicitudCrearMedicamento(nombreConPorcentaje, null, idPresentacion, null));
        servicio.crear(idGrupo, new SolicitudCrearMedicamento(nombreSinPorcentaje, null, idPresentacion, null));

        List<RespuestaMedicamento> resultados = servicio.buscar(idGrupo, sufijo + "50%");

        assertThat(resultados).extracting(RespuestaMedicamento::idMedicamento)
                .containsExactly(conPorcentaje.medicamento().idMedicamento());
    }

    @Test
    void colaboradorPuedeBuscarPeroNoCrear() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uidCoordinador, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        List<RespuestaMedicamento> resultados = servicio.buscar(idGrupo, null);
        assertThat(resultados).isNotNull();

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudCrearMedicamento("Medicamento-" + UUID.randomUUID(), null, idPresentacion, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void obtenerActivoInexistenteOInactivoDevuelve422() {
        assertThatThrownBy(() -> servicio.obtenerActivo(Long.MAX_VALUE))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento indicado no existe o no está activo");
    }

    private long crearCoordinadorYAutenticar() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        autenticar(uid);
        return idCoordinador;
    }

    /** Marca el medicamento como usado en el grupo, insertando un plan y un detalle por JDBC. */
    private void marcarUsadoEnGrupo(long idGrupo, long idAdulto, long idCreador, long idMedicamento,
            long idPresentacion, long idUnidadDosis) {
        long idEstadoPlanActivo = idEstado("PLAN_MEDICAMENTOS", "ACTIVO");
        long idEstadoDetalleActivo = idEstado("DETALLE_PLAN", "ACTIVO");
        long idPlan = insertarYObtenerId(
                "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, VIGENTE_DESDE, "
                        + "ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, ?, ?)",
                "ID_PLAN", idGrupo, idAdulto, "Plan fixtura", idEstadoPlanActivo,
                java.sql.Date.valueOf(LocalDate.now()), idCreador);
        insertarYObtenerId(
                "INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, "
                        + "DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, FECHA_INICIO, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "ID_DETALLE_PLAN", idPlan, idMedicamento, "Medicamento fixtura", idPresentacion, 1,
                idUnidadDosis, java.sql.Date.valueOf(LocalDate.now()), idEstadoDetalleActivo);
    }

    private long idOpcion(String tipoCatalogo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipoCatalogo, codigo);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private long insertarYObtenerId(String sql, String columnaId, Object... parametros) {
        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(sql, new String[] { columnaId });
            for (int i = 0; i < parametros.length; i++) {
                sentencia.setObject(i + 1, parametros[i]);
            }
            return sentencia;
        }, generadorDeClave);
        return generadorDeClave.getKey().longValue();
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
