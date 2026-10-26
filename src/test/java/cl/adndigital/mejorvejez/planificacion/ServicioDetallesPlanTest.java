package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioDetallesPlan} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 (America/Santiago) vía
 * un {@code Clock} {@code @Primary} de prueba: solo afecta a este contexto
 * Spring (que la caché de contextos separa de los demás), no a otras
 * pruebas. Datos propios por prueba (mismo patrón que {@link
 * ServicioPlanesTest}); nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioDetallesPlanTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private ServicioDetallesPlan servicio;

    @Autowired
    private ServicioPlanes servicioPlanes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaPlanes fixturaPlanes;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Plan con varios medicamentos (prueba mínima HU-04)
    // ------------------------------------------------------------------

    @Test
    void crearYModificarPlanConVariosMedicamentos() {
        Escenario e = escenario();
        long idParacetamol = fixturaPlanes.crearMedicamento("Paracetamol");
        long idLosartan = fixturaPlanes.crearMedicamento("Losartan");

        Datos datosUno = new Datos(idParacetamol);
        RespuestaDetallePlan uno = servicio.crear(e.idGrupo, e.idPlan, datosUno.solicitud());
        Datos datosDos = new Datos(idLosartan);
        datosDos.dosis = new BigDecimal("2");
        datosDos.horario = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"20:00\"]}";
        RespuestaDetallePlan dos = servicio.crear(e.idGrupo, e.idPlan, datosDos.solicitud());

        assertThat(uno.nombreMedicamento()).startsWith("Paracetamol");
        assertThat(dos.nombreMedicamento()).startsWith("Losartan");
        assertThat(uno.idEstado()).isEqualTo(idEstado("DETALLE_PLAN", "ACTIVO"));

        datosUno.dosis = new BigDecimal("1.5");
        datosUno.horario = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"21:00\",\"09:00\"]}";
        RespuestaDetallePlan modificado = servicio.actualizar(e.idGrupo, e.idPlan, uno.idDetallePlan(),
                datosUno.solicitud());

        assertThat(modificado.dosisCantidad()).isEqualByComparingTo("1.5");
        assertThat(modificado.horas()).containsExactly("09:00", "21:00");

        RespuestaPlanDetallado plan = servicioPlanes.obtener(e.idGrupo, e.idPlan);
        assertThat(plan.detalles()).hasSize(2);
        RespuestaDetallePlan otro = plan.detalles().stream()
                .filter(d -> d.idDetallePlan() == dos.idDetallePlan()).findFirst().orElseThrow();
        assertThat(otro.dosisCantidad()).isEqualByComparingTo("2");
        assertThat(otro.horas()).containsExactly("20:00");
        assertThat(otro.fechaActualizacion()).isNull();

        assertThat(eventos(uno.idDetallePlan(), "AGREGAR_MEDICAMENTO_PLAN")).hasSize(1);
        assertThat(eventos(dos.idDetallePlan(), "AGREGAR_MEDICAMENTO_PLAN")).hasSize(1);
        List<Map<String, Object>> actualizaciones = eventos(uno.idDetallePlan(), "ACTUALIZAR_MEDICAMENTO_PLAN");
        assertThat(actualizaciones).hasSize(1);
        Map<String, Object> detalles = leerJson((String) actualizaciones.get(0).get("DETALLES_JSON"));
        assertThat(detalles.get("camposModificados")).isEqualTo(List.of("dosisCantidad", "horario"));
        assertThat(eventos(dos.idDetallePlan(), "ACTUALIZAR_MEDICAMENTO_PLAN")).isEmpty();

        OffsetDateTime fechaActualizacionPlan = jdbcTemplate.queryForObject(
                "SELECT FECHA_ACTUALIZACION FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?", OffsetDateTime.class,
                e.idPlan);
        assertThat(fechaActualizacionPlan).isNotNull();
    }

    @Test
    void actualizarSinCambiosNoGuardaNiAudita() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Omeprazol"));
        datos.concentracion = "  20 mg ";
        datos.instrucciones = "   ";
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        assertThat(creado.concentracion()).isEqualTo("20 mg");
        assertThat(creado.instrucciones()).isNull();

        RespuestaDetallePlan igual = servicio.actualizar(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                datos.solicitud());

        assertThat(igual.fechaActualizacion()).isNull();
        assertThat(eventos(creado.idDetallePlan(), "ACTUALIZAR_MEDICAMENTO_PLAN")).isEmpty();
    }

    @Test
    void cambiarDeMedicamentoCopiaElNombre() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Aspirina"));
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        datos.idMedicamento = fixturaPlanes.crearMedicamento("Clopidogrel");
        datos.dias = List.of(dia("LUNES"), dia("JUEVES"));
        RespuestaDetallePlan cambiado = servicio.actualizar(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                datos.solicitud());

        assertThat(cambiado.idMedicamento()).isEqualTo(datos.idMedicamento);
        assertThat(cambiado.nombreMedicamento()).startsWith("Clopidogrel");
        Map<String, Object> detalles = leerJson((String) eventos(creado.idDetallePlan(),
                "ACTUALIZAR_MEDICAMENTO_PLAN").get(0).get("DETALLES_JSON"));
        assertThat(detalles.get("camposModificados")).isEqualTo(List.of("idMedicamento", "dias"));
    }

    @Test
    void medicamentoInactivoDevuelve422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Descontinuado");
        jdbcTemplate.update("UPDATE MEDICAMENTO SET ACTIVO = 'N' WHERE ID_MEDICAMENTO = ?", idMedicamento);

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, new Datos(idMedicamento).solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento indicado no existe o no está activo");
    }

    // ------------------------------------------------------------------
    // Horario
    // ------------------------------------------------------------------

    @Test
    void horarioIntervaloSeGuardaExpandido() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Amoxicilina"));
        datos.horario = "{\"version\":1,\"tipo\":\"INTERVALO\",\"hora_inicio\":\"08:00\","
                + "\"hora_fin\":\"20:00\",\"frecuencia_horas\":6}";

        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        assertThat(creado.horas()).containsExactly("08:00", "14:00", "20:00");
        List<LocalTime> filas = jdbcTemplate.queryForList(
                "SELECT TO_CHAR(HORA_ADMINISTRACION, 'HH24:MI') FROM DETALLE_PLAN_HORARIO "
                        + "WHERE ID_DETALLE_PLAN = ? ORDER BY HORA_ADMINISTRACION",
                String.class, creado.idDetallePlan()).stream().map(LocalTime::parse).toList();
        assertThat(filas).containsExactly(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0));
    }

    @Test
    void horarioFueraDeContratoDevuelve422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Ranitidina"));
        datos.horario = "{\"version\":2,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\"]}";

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Versión de horario no soportada (se espera 1)");
        assertThat(detallesDelPlan(e.idPlan)).isZero();
    }

    @Test
    void horarioComoTextoDevuelve422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Diclofenaco"));
        datos.horario = "\"08:00\"";

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El horario es obligatorio");
    }

    // ------------------------------------------------------------------
    // Ids cruzados y días
    // ------------------------------------------------------------------

    @Test
    void unidadComoPresentacionDevuelve422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Calcio"));
        datos.idPresentacion = opcion("UNIDAD_DOSIS", "MG");

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La opción indicada no pertenece al catálogo PRESENTACION_MEDICAMENTO");

        Datos datosUnidad = new Datos(datos.idMedicamento);
        datosUnidad.idUnidadDosis = opcion("PRESENTACION_MEDICAMENTO", "JARABE");
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datosUnidad.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La opción indicada no pertenece al catálogo UNIDAD_DOSIS");
    }

    @Test
    void diaDeOtroCatalogoDevuelve422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Vitamina D"));
        datos.dias = List.of(dia("LUNES"), opcion("UNIDAD_DOSIS", "ML"));

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La opción indicada no pertenece al catálogo DIA_SEMANA");
    }

    @Test
    void diasVaciosODuplicadosDevuelven422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Hierro"));

        datos.dias = List.of();
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Selecciona al menos un día");

        long idMartes = dia("MARTES");
        datos.dias = List.of(idMartes, dia("JUEVES"), idMartes);
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Hay días repetidos");
    }

    @Test
    void diasSeDevuelvenEnElOrdenDelCatalogo() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Levotiroxina"));
        datos.dias = List.of(dia("VIERNES"), dia("LUNES"), dia("MIERCOLES"));

        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        assertThat(creado.diasSemana()).containsExactly(dia("LUNES"), dia("MIERCOLES"), dia("VIERNES"));
        RespuestaPlanDetallado plan = servicioPlanes.obtener(e.idGrupo, e.idPlan);
        assertThat(plan.detalles().get(0).diasSemana())
                .containsExactly(dia("LUNES"), dia("MIERCOLES"), dia("VIERNES"));
    }

    // ------------------------------------------------------------------
    // Vigencia (prueba mínima HU-04)
    // ------------------------------------------------------------------

    @Test
    void vigenciaFueraDelPlanDevuelve422() {
        Escenario e = escenario(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        long idMedicamento = fixturaPlanes.crearMedicamento("Prednisona");
        String fueraDelPlan = "La vigencia del medicamento debe estar dentro de la vigencia del plan";

        Datos antes = new Datos(idMedicamento);
        antes.fechaInicio = LocalDate.of(2026, 10, 31);
        antes.fechaFin = LocalDate.of(2026, 11, 10);
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, antes.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(fueraDelPlan);

        Datos despues = new Datos(idMedicamento);
        despues.fechaFin = LocalDate.of(2026, 12, 1);
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, despues.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(fueraDelPlan);

        Datos abierta = new Datos(idMedicamento);
        abierta.fechaFin = null;
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, abierta.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(fueraDelPlan);

        Datos invertida = new Datos(idMedicamento);
        invertida.fechaInicio = LocalDate.of(2026, 11, 10);
        invertida.fechaFin = LocalDate.of(2026, 11, 5);
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, invertida.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La vigencia del medicamento no es válida");

        Datos dentro = new Datos(idMedicamento);
        dentro.fechaInicio = LocalDate.of(2026, 11, 1);
        dentro.fechaFin = LocalDate.of(2026, 11, 30);
        assertThat(servicio.crear(e.idGrupo, e.idPlan, dentro.solicitud()).fechaFin())
                .isEqualTo(LocalDate.of(2026, 11, 30));
    }

    // ------------------------------------------------------------------
    // Responsable predeterminado
    // ------------------------------------------------------------------

    @Test
    void responsableNoIntegranteDevuelve422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Insulina");
        String mensaje = "El responsable debe ser integrante activo del grupo";

        Datos ajeno = new Datos(idMedicamento);
        ajeno.idResponsable = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, ajeno.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);

        Datos inactivo = new Datos(idMedicamento);
        inactivo.idResponsable = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, inactivo.idResponsable, "COLABORADOR", "INACTIVO");
        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, inactivo.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
    }

    @Test
    void responsableConIdMayorA127SeAcepta() {
        Escenario e = escenario();
        long idResponsable = crearUsuarioConId(900_000_000L + System.nanoTime() % 1_000_000L);
        assertThat(idResponsable).isGreaterThan(127L);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idResponsable, "COLABORADOR", "ACTIVO");
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Warfarina"));
        datos.idResponsable = idResponsable;

        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        assertThat(creado.idResponsablePredeterminado()).isEqualTo(idResponsable);
    }

    // ------------------------------------------------------------------
    // Fecha estimada de término (reloj fijo en el lunes 2026-11-02)
    // ------------------------------------------------------------------

    @Test
    void terminoAutomaticoSeCalcula() {
        Escenario e = escenario(LocalDate.of(2026, 10, 26), null);
        Datos datos = conTresDiasYDosTomas(fixturaPlanes.crearMedicamento("Metoprolol"));
        datos.cantidadDisponible = new BigDecimal("7");
        // Ignorada en modo AUTOMATICO.
        datos.fechaEstimada = LocalDate.of(2030, 1, 1);
        // Inicio (lunes 26) una semana antes de hoy: el cálculo arranca desde
        // hoy, max(hoy, fechaInicio). Si arrancara desde fechaInicio (o con
        // el reloj real), daría el lunes 2026-11-02.
        datos.fechaInicio = LocalDate.of(2026, 10, 26);

        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        // Lu 02 (7→5), Mi 04 (5→3), Vi 06 (3→1), Lu 09: 1 < 2 → se agota.
        assertThat(creado.modoCalculoTermino()).isEqualTo("AUTOMATICO");
        assertThat(creado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 9));

        // Disponible 0 y hoy (lunes) es día programado → hoy.
        datos.cantidadDisponible = BigDecimal.ZERO;
        assertThat(servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()).fechaEstimadaTermino()).isEqualTo(HOY);

        // Agotamiento (Lu 09) después de fechaFin (Do 08) → null.
        datos.cantidadDisponible = new BigDecimal("7");
        datos.fechaFin = LocalDate.of(2026, 11, 8);
        assertThat(servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()).fechaEstimadaTermino()).isNull();
    }

    @Test
    void terminoAutomaticoSinCantidadQuedaNulo() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Sertralina"));
        datos.cantidadDisponible = null;

        assertThat(servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()).fechaEstimadaTermino()).isNull();
    }

    @Test
    void terminoManualSinFechaDevuelve422() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Alprazolam"));
        datos.modo = "MANUAL";
        datos.fechaEstimada = null;

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Indica la fecha estimada de término o usa el cálculo automático");

        datos.fechaEstimada = LocalDate.of(2027, 3, 15);
        assertThat(servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()).fechaEstimadaTermino())
                .isEqualTo(LocalDate.of(2027, 3, 15));
    }

    @Test
    void pasarAAutomaticoRecalcula() {
        Escenario e = escenario();
        Datos datos = conTresDiasYDosTomas(fixturaPlanes.crearMedicamento("Furosemida"));
        datos.cantidadDisponible = new BigDecimal("7");
        datos.modo = "MANUAL";
        datos.fechaEstimada = LocalDate.of(2027, 1, 1);
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        assertThat(creado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2027, 1, 1));

        datos.modo = "AUTOMATICO";
        RespuestaDetallePlan recalculado = servicio.actualizar(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                datos.solicitud());

        assertThat(recalculado.modoCalculoTermino()).isEqualTo("AUTOMATICO");
        assertThat(recalculado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void actualizarCantidadRecalcula() {
        Escenario e = escenario();
        Datos datos = conTresDiasYDosTomas(fixturaPlanes.crearMedicamento("Digoxina"));
        datos.cantidadInicial = new BigDecimal("30");
        datos.cantidadDisponible = new BigDecimal("7");
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        assertThat(creado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 9));

        RespuestaDetallePlan actualizado = servicio.actualizarCantidad(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                new SolicitudActualizarCantidad(new BigDecimal("3")));

        // Lu 02 (3→1), Mi 04: 1 < 2 → se agota.
        assertThat(actualizado.cantidadDisponible()).isEqualByComparingTo("3");
        assertThat(actualizado.cantidadInicial()).isEqualByComparingTo("30");
        assertThat(actualizado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(eventos(creado.idDetallePlan(), "ACTUALIZAR_CANTIDAD_DISPONIBLE")).hasSize(1);
    }

    @Test
    void actualizarCantidadEnModoManualConservaLaFecha() {
        Escenario e = escenario();
        Datos datos = conTresDiasYDosTomas(fixturaPlanes.crearMedicamento("Tramadol"));
        datos.cantidadDisponible = new BigDecimal("7");
        datos.modo = "MANUAL";
        datos.fechaEstimada = LocalDate.of(2027, 2, 2);
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());

        RespuestaDetallePlan actualizado = servicio.actualizarCantidad(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                new SolicitudActualizarCantidad(BigDecimal.ZERO));

        assertThat(actualizado.cantidadDisponible()).isEqualByComparingTo("0");
        assertThat(actualizado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2027, 2, 2));
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    @Test
    void desactivarDetalleNoDesactivaPlanNiOtros() {
        Escenario e = escenario();
        RespuestaDetallePlan uno = servicio.crear(e.idGrupo, e.idPlan,
                new Datos(fixturaPlanes.crearMedicamento("Simvastatina")).solicitud());
        RespuestaDetallePlan dos = servicio.crear(e.idGrupo, e.idPlan,
                new Datos(fixturaPlanes.crearMedicamento("Glibenclamida")).solicitud());

        RespuestaDetallePlan desactivado = servicio.desactivar(e.idGrupo, e.idPlan, uno.idDetallePlan());

        assertThat(desactivado.idEstado()).isEqualTo(idEstado("DETALLE_PLAN", "INACTIVO"));
        assertThat(estadoDetalle(dos.idDetallePlan())).isEqualTo(idEstado("DETALLE_PLAN", "ACTIVO"));
        assertThat(jdbcTemplate.queryForObject("SELECT ID_ESTADO FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?",
                Long.class, e.idPlan)).isEqualTo(idEstado("PLAN_MEDICAMENTOS", "ACTIVO"));
        assertThat(eventos(uno.idDetallePlan(), "DESACTIVAR_MEDICAMENTO_PLAN")).hasSize(1);

        assertThatThrownBy(() -> servicio.desactivar(e.idGrupo, e.idPlan, uno.idDetallePlan()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El medicamento del plan ya está inactivo");

        RespuestaDetallePlan reactivado = servicio.reactivar(e.idGrupo, e.idPlan, uno.idDetallePlan());
        assertThat(reactivado.idEstado()).isEqualTo(idEstado("DETALLE_PLAN", "ACTIVO"));
        assertThat(eventos(uno.idDetallePlan(), "REACTIVAR_MEDICAMENTO_PLAN")).hasSize(1);

        assertThatThrownBy(() -> servicio.reactivar(e.idGrupo, e.idPlan, uno.idDetallePlan()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El medicamento del plan ya está activo");
    }

    @Test
    void reactivarFueraDeLaVigenciaActualDelPlanDevuelve422() {
        Escenario e = escenario(LocalDate.of(2026, 11, 1), null);
        RespuestaDetallePlan detalle = servicio.crear(e.idGrupo, e.idPlan,
                new Datos(fixturaPlanes.crearMedicamento("Clonazepam")).solicitud());
        servicio.desactivar(e.idGrupo, e.idPlan, detalle.idDetallePlan());
        // El detalle inactivo (FECHA_FIN nula) no impide acotar la vigencia del plan...
        servicioPlanes.actualizar(e.idGrupo, e.idPlan,
                new SolicitudActualizarPlan("Plan", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));

        // ...pero reactivarlo lo dejaría fuera de la vigencia actual.
        assertThatThrownBy(() -> servicio.reactivar(e.idGrupo, e.idPlan, detalle.idDetallePlan()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La vigencia del medicamento debe estar dentro de la vigencia del plan");
        assertThat(estadoDetalle(detalle.idDetallePlan())).isEqualTo(idEstado("DETALLE_PLAN", "INACTIVO"));
    }

    @Test
    void detalleInactivoNoAdmiteModificaciones() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Bromazepam"));
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        servicio.desactivar(e.idGrupo, e.idPlan, creado.idDetallePlan());
        String mensaje = "El medicamento del plan está inactivo; reactívalo para modificarlo";

        datos.dosis = new BigDecimal("2");
        assertThatThrownBy(() -> servicio.actualizar(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                datos.solicitud())).isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.actualizarCantidad(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                new SolicitudActualizarCantidad(BigDecimal.TEN)))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DOSIS_CANTIDAD FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = ?", BigDecimal.class,
                creado.idDetallePlan())).isEqualByComparingTo("1");
    }

    @Test
    void reactivarEnModoAutomaticoRecalculaElTermino() {
        Escenario e = escenario();
        Datos datos = conTresDiasYDosTomas(fixturaPlanes.crearMedicamento("Espironolactona"));
        datos.cantidadDisponible = new BigDecimal("7");
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        servicio.desactivar(e.idGrupo, e.idPlan, creado.idDetallePlan());
        // Simula una fecha calculada en otro "hoy" (el detalle estuvo inactivo un tiempo).
        jdbcTemplate.update("UPDATE DETALLE_PLAN SET FECHA_ESTIMADA_TERMINO = DATE '2026-10-01' "
                + "WHERE ID_DETALLE_PLAN = ?", creado.idDetallePlan());

        RespuestaDetallePlan reactivado = servicio.reactivar(e.idGrupo, e.idPlan, creado.idDetallePlan());

        assertThat(reactivado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT FECHA_ESTIMADA_TERMINO FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = ?", LocalDate.class,
                creado.idDetallePlan())).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void diaGuardadoFueraDelCatalogoActivoSeIgnoraAlEstimar() {
        Escenario e = escenario(LocalDate.of(2026, 10, 1), null);
        long idMedicamento = fixturaPlanes.crearMedicamento("Alopurinol");
        // Detalle fixtura: LUNES 08:00, dosis 1, AUTOMATICO. Se le agrega un
        // día que no es un DIA_SEMANA activo (simula un día dado de baja en
        // el catálogo después de guardarse).
        long idDetalle = fixturaPlanes.crearDetalle(e.idPlan, idMedicamento, "ACTIVO");
        jdbcTemplate.update("INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (?, ?)",
                idDetalle, opcion("UNIDAD_DOSIS", "ML"));

        RespuestaDetallePlan actualizado = servicio.actualizarCantidad(e.idGrupo, e.idPlan, idDetalle,
                new SolicitudActualizarCantidad(BigDecimal.ONE));

        // Solo cuenta el LUNES: Lu 02 (1→0), Lu 09: 0 < 1 → se agota.
        assertThat(actualizado.fechaEstimadaTermino()).isEqualTo(LocalDate.of(2026, 11, 9));

        // Si no queda ningún día conocido, la estimación es null (nunca un 500).
        jdbcTemplate.update("DELETE FROM DETALLE_PLAN_DIA WHERE ID_DETALLE_PLAN = ? AND ID_DIA_SEMANA = ?",
                idDetalle, dia("LUNES"));
        RespuestaDetallePlan sinDias = servicio.actualizarCantidad(e.idGrupo, e.idPlan, idDetalle,
                new SolicitudActualizarCantidad(new BigDecimal("2")));
        assertThat(sinDias.fechaEstimadaTermino()).isNull();
    }

    @Test
    void enPlanInactivoSePuedeDesactivarReactivarYEliminarUnDetalle() {
        Escenario e = escenario();
        RespuestaDetallePlan uno = servicio.crear(e.idGrupo, e.idPlan,
                fueraDeLaVentana(new Datos(fixturaPlanes.crearMedicamento("Nitrofurantoina"))).solicitud());
        RespuestaDetallePlan dos = servicio.crear(e.idGrupo, e.idPlan,
                fueraDeLaVentana(new Datos(fixturaPlanes.crearMedicamento("Cefadroxilo"))).solicitud());
        servicioPlanes.desactivar(e.idGrupo, e.idPlan);

        assertThat(servicio.desactivar(e.idGrupo, e.idPlan, uno.idDetallePlan()).idEstado())
                .isEqualTo(idEstado("DETALLE_PLAN", "INACTIVO"));
        assertThat(servicio.reactivar(e.idGrupo, e.idPlan, uno.idDetallePlan()).idEstado())
                .isEqualTo(idEstado("DETALLE_PLAN", "ACTIVO"));
        servicio.eliminar(e.idGrupo, e.idPlan, dos.idDetallePlan());

        assertThat(contar("DETALLE_PLAN", "ID_DETALLE_PLAN", dos.idDetallePlan())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT ID_ESTADO FROM PLAN_MEDICAMENTOS WHERE ID_PLAN = ?",
                Long.class, e.idPlan)).isEqualTo(idEstado("PLAN_MEDICAMENTOS", "INACTIVO"));
    }

    // ------------------------------------------------------------------
    // Cambio de horario (prueba mínima HU-04) y eliminación
    // ------------------------------------------------------------------

    @Test
    void cambiarHorarioNoModificaTareasExistentes() {
        // Desde F4 el recálculo (regla 8) sí cancela o ajusta las tareas
        // futuras PENDIENTE, pero solo las que llegó a crear el generador (las
        // que tienen una ocurrencia TAREA_CREADA del detalle; ver
        // tareas.ServicioRecalculoTareasTest). Estas tareas de fixtura no
        // tienen ocurrencia, así que siguen intactas.
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Metamizol"));
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        long idTareaPasada = fixturaPlanes.crearTareaDeMedicamento(e.idGrupo, e.idAdulto, creado.idDetallePlan(),
                HOY.minusDays(3).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime());
        long idTareaFutura = fixturaPlanes.crearTareaDeMedicamento(e.idGrupo, e.idAdulto, creado.idDetallePlan(),
                HOY.plusDays(7).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime());
        List<Map<String, Object>> antes = tareas(idTareaPasada, idTareaFutura);

        datos.horario = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"10:00\",\"22:00\"]}";
        datos.dias = List.of(dia("MARTES"));
        RespuestaDetallePlan cambiado = servicio.actualizar(e.idGrupo, e.idPlan, creado.idDetallePlan(),
                datos.solicitud());

        assertThat(cambiado.horas()).containsExactly("10:00", "22:00");
        assertThat(tareas(idTareaPasada, idTareaFutura)).isEqualTo(antes);
    }

    @Test
    void eliminarDetalleConTareaDevuelve422() {
        Escenario e = escenario();
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan,
                fueraDeLaVentana(new Datos(fixturaPlanes.crearMedicamento("Morfina"))).solicitud());
        fixturaPlanes.crearTareaDeMedicamento(e.idGrupo, e.idAdulto, creado.idDetallePlan(),
                HOY.atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime());

        assertThatThrownBy(() -> servicio.eliminar(e.idGrupo, e.idPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento del plan tiene historial o referencias; desactívalo en su lugar");
        assertThat(contar("DETALLE_PLAN", "ID_DETALLE_PLAN", creado.idDetallePlan())).isEqualTo(1);
    }

    @Test
    void eliminarDetalleSinReferenciasLoBorraConSusDiasYHoras() {
        Escenario e = escenario();
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan,
                fueraDeLaVentana(new Datos(fixturaPlanes.crearMedicamento("Captopril"))).solicitud());

        servicio.eliminar(e.idGrupo, e.idPlan, creado.idDetallePlan());

        assertThat(contar("DETALLE_PLAN", "ID_DETALLE_PLAN", creado.idDetallePlan())).isZero();
        assertThat(contar("DETALLE_PLAN_DIA", "ID_DETALLE_PLAN", creado.idDetallePlan())).isZero();
        assertThat(contar("DETALLE_PLAN_HORARIO", "ID_DETALLE_PLAN", creado.idDetallePlan())).isZero();
        assertThat(eventos(creado.idDetallePlan(), "ELIMINAR_MEDICAMENTO_PLAN")).hasSize(1);
    }

    @Test
    void eliminarDetalleDesactivadoAlgunaVezDevuelve422() {
        Escenario e = escenario();
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan,
                fueraDeLaVentana(new Datos(fixturaPlanes.crearMedicamento("Codeina"))).solicitud());
        servicio.desactivar(e.idGrupo, e.idPlan, creado.idDetallePlan());
        servicio.reactivar(e.idGrupo, e.idPlan, creado.idDetallePlan());

        assertThatThrownBy(() -> servicio.eliminar(e.idGrupo, e.idPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento del plan tiene historial o referencias; desactívalo en su lugar");
    }

    // ------------------------------------------------------------------
    // Otros: recursos ajenos, plan inactivo, permisos
    // ------------------------------------------------------------------

    @Test
    void detalleDeOtroPlanDevuelve404() {
        Escenario e = escenario();
        long idOtroPlan = fixturaPlanes.crearPlanConVigencia(e.idGrupo, e.idAdulto, e.idCoordinador,
                LocalDate.of(2026, 11, 1), null);
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Tamsulosina"));
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        String mensaje = "El medicamento del plan no existe";

        assertThatThrownBy(() -> servicio.actualizar(e.idGrupo, idOtroPlan, creado.idDetallePlan(),
                datos.solicitud())).isInstanceOf(ExcepcionNoEncontrado.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.desactivar(e.idGrupo, idOtroPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionNoEncontrado.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.eliminar(e.idGrupo, idOtroPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionNoEncontrado.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.actualizarCantidad(e.idGrupo, idOtroPlan, creado.idDetallePlan(),
                new SolicitudActualizarCantidad(BigDecimal.ONE)))
                .isInstanceOf(ExcepcionNoEncontrado.class).hasMessage(mensaje);
    }

    @Test
    void planDeOtroGrupoDevuelve404() {
        Escenario ajeno = escenario();
        Escenario propio = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Bisoprolol"));

        assertThatThrownBy(() -> servicio.crear(propio.idGrupo, ajeno.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El plan no existe");
    }

    @Test
    void planInactivoNoAdmiteNuevosMedicamentos() {
        Escenario e = escenario();
        Datos datos = new Datos(fixturaPlanes.crearMedicamento("Pregabalina"));
        RespuestaDetallePlan existente = servicio.crear(e.idGrupo, e.idPlan, datos.solicitud());
        servicioPlanes.desactivar(e.idGrupo, e.idPlan);
        String mensaje = "El plan está inactivo; reactívalo para modificarlo";

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, e.idPlan, datos.solicitud()))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.actualizar(e.idGrupo, e.idPlan, existente.idDetallePlan(),
                datos.solicitud())).isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
        assertThatThrownBy(() -> servicio.actualizarCantidad(e.idGrupo, e.idPlan, existente.idDetallePlan(),
                new SolicitudActualizarCantidad(BigDecimal.ONE)))
                .isInstanceOf(ExcepcionReglaNegocio.class).hasMessage(mensaje);
        assertThat(detallesDelPlan(e.idPlan)).isEqualTo(1);
    }

    @Test
    void colaboradorNoPuedeModificarMedicamentos() {
        Escenario e = escenario();
        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan,
                new Datos(fixturaPlanes.crearMedicamento("Gabapentina")).solicitud());
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        assertThatThrownBy(() -> servicio.desactivar(e.idGrupo, e.idPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.eliminar(e.idGrupo, e.idPlan, creado.idDetallePlan()))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void administradorSinMembresiaQuedaTrazado() {
        Escenario e = escenario();
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        RespuestaDetallePlan creado = servicio.crear(e.idGrupo, e.idPlan,
                new Datos(fixturaPlanes.crearMedicamento("Ondansetron")).solicitud());

        Map<String, Object> detalles = leerJson((String) eventos(creado.idDetallePlan(),
                "AGREGAR_MEDICAMENTO_PLAN").get(0).get("DETALLES_JSON"));
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
        assertThat(((Number) detalles.get("idPlan")).longValue()).isEqualTo(e.idPlan);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    /** Grupo con coordinador autenticado, adulto y plan ACTIVO vigente desde 2026-11-01 (sin término). */
    private Escenario escenario() {
        return escenario(LocalDate.of(2026, 11, 1), null);
    }

    private Escenario escenario(LocalDate vigenteDesde, LocalDate vigenteHasta) {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlanConVigencia(idGrupo, idAdulto, idCoordinador, vigenteDesde,
                vigenteHasta);
        autenticar(uid);
        return new Escenario(idCoordinador, idGrupo, idAdulto, idPlan);
    }

    private record Escenario(long idCoordinador, long idGrupo, long idAdulto, long idPlan) {
    }

    /**
     * Regla 13 de la Fase 4: desde F4, crear (o reactivar) un detalle genera
     * al instante las tareas de la ventana, y esas tareas son referencias que
     * impiden eliminarlo. Las pruebas que eliminan un detalle creado por el
     * servicio, o que afirman qué referencia produce el 422, lo crean con
     * {@code fechaInicio} = hoy + 30 días, fuera de la ventana de 7 días, para
     * no depender de qué días y horas caen dentro de ella.
     */
    private Datos fueraDeLaVentana(Datos datos) {
        datos.fechaInicio = HOY.plusDays(30);
        return datos;
    }

    /** LUNES, MIERCOLES y VIERNES a las 08:00 y 20:00, dosis 1. */
    private Datos conTresDiasYDosTomas(long idMedicamento) {
        Datos datos = new Datos(idMedicamento);
        datos.dias = List.of(dia("LUNES"), dia("MIERCOLES"), dia("VIERNES"));
        datos.horario = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\",\"20:00\"]}";
        return datos;
    }

    /** Datos mutables de una solicitud, con valores por defecto válidos. */
    private final class Datos {
        long idMedicamento;
        long idPresentacion = opcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String concentracion = "500 mg";
        String instrucciones = "Con comida";
        BigDecimal dosis = BigDecimal.ONE;
        long idUnidadDosis = opcion("UNIDAD_DOSIS", "COMPRIMIDO");
        List<Long> dias = List.of(dia("LUNES"));
        String horario = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\"]}";
        LocalDate fechaInicio = HOY;
        LocalDate fechaFin = null;
        BigDecimal cantidadInicial = null;
        BigDecimal cantidadDisponible = null;
        String modo = "AUTOMATICO";
        LocalDate fechaEstimada = null;
        Long idResponsable = null;

        Datos(long idMedicamento) {
            this.idMedicamento = idMedicamento;
        }

        SolicitudGuardarDetalle solicitud() {
            JsonNode nodoHorario = JSON.readTree(horario);
            return new SolicitudGuardarDetalle(idMedicamento, idPresentacion, concentracion, instrucciones, dosis,
                    idUnidadDosis, dias, nodoHorario, fechaInicio, fechaFin, cantidadInicial, cantidadDisponible,
                    modo, fechaEstimada, idResponsable);
        }
    }

    private long crearUsuarioConId(long idUsuario) {
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', ?)",
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + idUsuario + "@planes.mejorvejez.test",
                "Usuario id alto", idEstado("USUARIO", "ACTIVO"));
        return idUsuario;
    }

    private List<Map<String, Object>> tareas(long idTareaUno, long idTareaDos) {
        return jdbcTemplate.queryForList(
                "SELECT ID_TAREA, TIPO_TAREA, CLAVE_OCURRENCIA, ID_DETALLE_PLAN, TITULO_TAREA, "
                        + "TO_CHAR(FECHA_HORARIO, 'YYYY-MM-DD HH24:MI:SS TZH:TZM') AS FECHA_HORARIO, ID_ESTADO, "
                        + "TO_CHAR(FECHA_ACTUALIZACION, 'YYYY-MM-DD HH24:MI:SS') AS FECHA_ACTUALIZACION "
                        + "FROM TAREA_CUIDADO WHERE ID_TAREA IN (?, ?) ORDER BY ID_TAREA",
                idTareaUno, idTareaDos);
    }

    private List<Map<String, Object>> eventos(long idDetalle, String accion) {
        return jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'DETALLE_PLAN' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = ?",
                String.valueOf(idDetalle), accion);
    }

    private Map<String, Object> leerJson(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private long detallesDelPlan(long idPlan) {
        return contar("DETALLE_PLAN", "ID_PLAN", idPlan);
    }

    private long estadoDetalle(long idDetalle) {
        return jdbcTemplate.queryForObject("SELECT ID_ESTADO FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = ?",
                Long.class, idDetalle);
    }

    private long contar(String tabla, String columnaId, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabla + " WHERE " + columnaId + " = ?", Long.class, id);
    }

    private long dia(String codigo) {
        return opcion("DIA_SEMANA", codigo);
    }

    private long opcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
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
