package cl.adndigital.mejorvejez.tareas;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.catalogos.RespuestaOpcion;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.catalogos.TipoCatalogo;
import cl.adndigital.mejorvejez.comun.TextoAcotado;
import cl.adndigital.mejorvejez.grupos.RepositorioIntegranteGrupo;
import cl.adndigital.mejorvejez.planificacion.CatalogoDiasSemana;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;
import cl.adndigital.mejorvejez.planificacion.RepositorioDetallePlan;
import cl.adndigital.mejorvejez.planificacion.RepositorioPlanMedicamentos;

/**
 * Reserva ocurrencias y crea las tareas de MEDICAMENTOS a partir de ellas
 * (HU-05, reglas 3, 4, 6, 7 y 9 de la Fase 4). Lo usan el generador diario
 * ({@link GeneradorPorPlan} en la fase A, {@link ServicioGeneracionTareas} en
 * la fase B) y el recálculo tras un cambio de plan (Tarea 4).
 *
 * <p>{@link #reservarSiLibre} y {@link #crearTarea} participan en la
 * transacción del llamador, que debe tener el plan bloqueado ({@code
 * RepositorioPlanMedicamentos.bloquearPorId}, regla 6). {@link
 * #crearDesdeOcurrencia} abre su propia transacción ({@code REQUIRES_NEW},
 * fase B) y bloquea el plan ella misma.
 */
@Component
public class CreadorTareas {

    /** Motivo de la asignación automática al responsable predeterminado (regla 9). */
    static final String MOTIVO_RESPONSABLE_PREDETERMINADO = "Responsable predeterminado del plan";

    private static final int LARGO_MAXIMO_TITULO = 200;
    private static final int LARGO_MAXIMO_DESCRIPCION = 1000;
    private static final String ACTIVO = "ACTIVO";

    private final RepositorioOcurrenciaPlanificada repositorioOcurrencias;
    private final RepositorioTareaCuidado repositorioTareas;
    private final RepositorioAsignacionTarea repositorioAsignaciones;
    private final RepositorioPlanMedicamentos repositorioPlanes;
    private final RepositorioDetallePlan repositorioDetalles;
    private final VerificadorVigencia verificador;
    private final RepositorioIntegranteGrupo repositorioIntegrantes;
    private final RepositorioConsultaGeneracion consultaGeneracion;
    private final PlanificadorOcurrencias planificador;
    private final CatalogoDiasSemana catalogoDias;
    private final ServicioCatalogos servicioCatalogos;
    private final Clock reloj;

    /**
     * Descripción de cada unidad de dosis por id, cargada en el primer uso:
     * el catálogo lo siembra {@code V2__catalogos.sql} y no cambia en
     * caliente (misma premisa y carga idempotente que {@code
     * CatalogoDiasSemana}).
     */
    private volatile Map<Long, String> unidadesDosis;

    public CreadorTareas(RepositorioOcurrenciaPlanificada repositorioOcurrencias,
            RepositorioTareaCuidado repositorioTareas, RepositorioAsignacionTarea repositorioAsignaciones,
            RepositorioPlanMedicamentos repositorioPlanes, RepositorioDetallePlan repositorioDetalles,
            VerificadorVigencia verificador, RepositorioIntegranteGrupo repositorioIntegrantes,
            RepositorioConsultaGeneracion consultaGeneracion,
            PlanificadorOcurrencias planificador, CatalogoDiasSemana catalogoDias,
            ServicioCatalogos servicioCatalogos, Clock reloj) {
        this.repositorioOcurrencias = repositorioOcurrencias;
        this.repositorioTareas = repositorioTareas;
        this.repositorioAsignaciones = repositorioAsignaciones;
        this.repositorioPlanes = repositorioPlanes;
        this.repositorioDetalles = repositorioDetalles;
        this.verificador = verificador;
        this.repositorioIntegrantes = repositorioIntegrantes;
        this.consultaGeneracion = consultaGeneracion;
        this.planificador = planificador;
        this.catalogoDias = catalogoDias;
        this.servicioCatalogos = servicioCatalogos;
        this.reloj = reloj;
    }

    /**
     * Si el turno (detalle + instante) está libre, inserta una ocurrencia
     * {@code PLANIFICADA} con la revisión siguiente (reglas 3 y 4). Un turno
     * está ocupado si tiene una ocurrencia PLANIFICADA o ERROR, o una
     * TAREA_CREADA cuya tarea no está CANCELADA. Un turno ya pasado
     * ({@code horario < ahora}) nunca se reserva (ruling final de F4 sobre
     * la regla 4; HU-05 habla de ocurrencias futuras): nacería atrasada y,
     * si es de MEDICAMENTOS, no se podría cancelar. Las PLANIFICADA o ERROR
     * ya reservadas de un turno pasado las sigue procesando la fase B: son
     * reintentos legítimos. Participa en la transacción del llamador, que
     * debe tener el plan bloqueado.
     *
     * @return la ocurrencia nueva, o vacío si el turno ya pasó o estaba
     *         ocupado.
     */
    public Optional<OcurrenciaPlanificada> reservarSiLibre(DetallePlan d, PlanMedicamentos p,
            OffsetDateTime horario, OffsetDateTime ahora) {
        if (horario.isBefore(ahora)) {
            return Optional.empty();
        }
        long idDetalle = d.getIdDetallePlan();
        long existentes = repositorioOcurrencias.countByIdDetallePlanAndFechaHorario(idDetalle, horario);
        if (existentes > 0) {
            boolean ocupado = repositorioOcurrencias.turnoOcupado(idDetalle, horario,
                    List.of(estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA),
                            estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_ERROR)),
                    estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_TAREA_CREADA),
                    estadoTarea(CodigosTareas.ESTADO_TAREA_CANCELADA));
            if (ocupado) {
                return Optional.empty();
            }
        }
        int revision = Math.toIntExact(existentes + 1);
        OcurrenciaPlanificada ocurrencia = new OcurrenciaPlanificada(idDetalle, p.getIdAdultoMayor(),
                p.getIdGrupo(), horario, PlanificadorOcurrencias.clave(idDetalle, horario, revision),
                estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA), ahora);
        return Optional.of(repositorioOcurrencias.save(ocurrencia));
    }

    /**
     * Crea la tarea PENDIENTE de la ocurrencia (con su misma clave), la
     * asigna al responsable predeterminado del detalle si sigue siendo
     * integrante ACTIVO del grupo (regla 9; si no, queda sin asignar) y deja
     * la ocurrencia en TAREA_CREADA vinculada a ella. No escribe historial
     * (regla 9: no hay usuario de sistema). Participa en la transacción del
     * llamador, que debe tener el plan bloqueado.
     */
    public TareaCuidado crearTarea(DetallePlan d, PlanMedicamentos p, OcurrenciaPlanificada o,
            OffsetDateTime ahora) {
        TareaCuidado tarea = repositorioTareas.save(TareaCuidado.deMedicamento(o.getClaveOcurrencia(),
                p.getIdGrupo(), p.getIdAdultoMayor(), d.getIdDetallePlan(), titulo(d), descripcion(d),
                o.getFechaHorario(), estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE), ahora));
        asignarResponsablePredeterminado(tarea, d, p, ahora);
        o.vincularTarea(tarea.getIdTarea(), estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_TAREA_CREADA));
        repositorioOcurrencias.save(o);
        return tarea;
    }

    /**
     * Fase B del generador para una ocurrencia, en su propia transacción
     * ({@code REQUIRES_NEW}): bloquea el plan del detalle de la ocurrencia y
     * recién entonces la lee (regla 6), así que ve el último estado
     * confirmado aunque otra corrida la haya procesado mientras esperaba el
     * bloqueo.
     * <ul>
     * <li>ya no está PLANIFICADA ni ERROR → {@code false} (otra corrida la
     * procesó);</li>
     * <li>plan, detalle, adulto o grupo no están ACTIVO, o el turno ya no es
     * esperado según la programación vigente → CANCELADA, {@code
     * false};</li>
     * <li>ya existe una tarea con su clave → se vincula a ella, {@code
     * false};</li>
     * <li>si no → {@link #crearTarea}, {@code true}.</li>
     * </ul>
     *
     * @return {@code true} solo si creó una tarea nueva.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean crearDesdeOcurrencia(long idOcurrencia) {
        Optional<Long> idPlan = consultaGeneracion.planDeOcurrencia(idOcurrencia);
        if (idPlan.isEmpty()) {
            return false;
        }
        PlanMedicamentos plan = repositorioPlanes.bloquearPorId(idPlan.get()).orElse(null);
        OcurrenciaPlanificada ocurrencia = repositorioOcurrencias.findById(idOcurrencia).orElse(null);
        if (plan == null || ocurrencia == null || !estaAbierta(ocurrencia)) {
            return false;
        }
        DetallePlan detalle = repositorioDetalles.findById(ocurrencia.getIdDetallePlan()).orElseThrow();
        if (!corresponde(plan, detalle, ocurrencia.getFechaHorario())) {
            ocurrencia.cambiarEstado(estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_CANCELADA));
            repositorioOcurrencias.save(ocurrencia);
            return false;
        }
        Optional<TareaCuidado> existente = repositorioTareas.findByClaveOcurrencia(ocurrencia.getClaveOcurrencia());
        if (existente.isPresent()) {
            ocurrencia.vincularTarea(existente.get().getIdTarea(),
                    estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_TAREA_CREADA));
            repositorioOcurrencias.save(ocurrencia);
            return false;
        }
        crearTarea(detalle, plan, ocurrencia, OffsetDateTime.now(reloj));
        return true;
    }

    /** {@code nombreMedicamento} + {@code " " + concentracion} si la hay, truncado a 200 (bytes UTF-8, {@link TextoAcotado}). */
    public String titulo(DetallePlan d) {
        String titulo = d.getNombreMedicamento();
        if (tieneTexto(d.getConcentracion())) {
            titulo += " " + d.getConcentracion().strip();
        }
        return TextoAcotado.truncarABytes(titulo, LARGO_MAXIMO_TITULO);
    }

    /**
     * {@code "Dosis: " + dosis + " " + unidad} + {@code ". " +
     * instrucciones} si las hay, truncado a 1000 (bytes UTF-8, {@link
     * TextoAcotado}). Ejemplo: {@code "Dosis: 1.5 Comprimido. Con comida"}.
     * Si la unidad ya no está activa en el catálogo, se omite.
     */
    public String descripcion(DetallePlan d) {
        StringBuilder descripcion = new StringBuilder("Dosis: ")
                .append(d.getDosisCantidad().stripTrailingZeros().toPlainString());
        String unidad = unidadesDosis().get(d.getIdUnidadDosis());
        if (unidad != null) {
            descripcion.append(' ').append(unidad);
        }
        if (tieneTexto(d.getInstrucciones())) {
            descripcion.append(". ").append(d.getInstrucciones().strip());
        }
        return TextoAcotado.truncarABytes(descripcion.toString(), LARGO_MAXIMO_DESCRIPCION);
    }

    /**
     * Si la ocurrencia de {@code horario} sigue correspondiendo: plan,
     * detalle, adulto y grupo ACTIVO, y la programación vigente del detalle
     * la espera ({@link PlanificadorOcurrencias#esperado}).
     */
    private boolean corresponde(PlanMedicamentos plan, DetallePlan detalle, OffsetDateTime horario) {
        boolean todoActivo = verificador.planAdultoYGrupoActivos(plan) && verificador.detalleActivo(detalle);
        return todoActivo && ProgramacionDetalle.de(detalle, plan, catalogoDias)
                .map(programacion -> planificador.esperado(programacion, horario))
                .orElse(false);
    }

    private void asignarResponsablePredeterminado(TareaCuidado tarea, DetallePlan d, PlanMedicamentos p,
            OffsetDateTime ahora) {
        Long idResponsable = d.getIdResponsablePredeterminado();
        if (idResponsable == null) {
            return;
        }
        long idIntegranteActivo = idActivo("INTEGRANTE_GRUPO");
        boolean esIntegranteActivo = repositorioIntegrantes.findByIdGrupoAndIdUsuario(p.getIdGrupo(), idResponsable)
                .filter(integrante -> integrante.getIdEstado() == idIntegranteActivo)
                .isPresent();
        if (!esIntegranteActivo) {
            return;
        }
        long idAsignador = p.getIdUsuarioActualizacion() != null ? p.getIdUsuarioActualizacion()
                : p.getIdUsuarioCreador();
        repositorioAsignaciones.save(new AsignacionTarea(tarea.getIdTarea(), p.getIdGrupo(), idResponsable,
                idAsignador, MOTIVO_RESPONSABLE_PREDETERMINADO,
                servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CodigosTareas.CAMPO_ESTADO,
                        CodigosTareas.ESTADO_ASIGNACION_ACTIVO),
                ahora));
    }

    private boolean estaAbierta(OcurrenciaPlanificada o) {
        long idEstado = o.getIdEstado();
        return idEstado == estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_PLANIFICADA)
                || idEstado == estadoOcurrencia(CodigosTareas.ESTADO_OCURRENCIA_ERROR);
    }

    private Map<Long, String> unidadesDosis() {
        Map<Long, String> cargadas = unidadesDosis;
        if (cargadas == null) {
            cargadas = servicioCatalogos.listarOpciones(TipoCatalogo.UNIDAD_DOSIS).stream()
                    .collect(Collectors.toUnmodifiableMap(RespuestaOpcion::idOpcion, RespuestaOpcion::descripcion));
            unidadesDosis = cargadas;
        }
        return cargadas;
    }

    private long estadoOcurrencia(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_OCURRENCIA_PLANIFICADA, CodigosTareas.CAMPO_ESTADO,
                codigo);
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private long idActivo(String tabla) {
        return servicioCatalogos.idEstado(tabla, CodigosTareas.CAMPO_ESTADO, ACTIVO);
    }

    private static boolean tieneTexto(String texto) {
        return texto != null && !texto.isBlank();
    }
}
