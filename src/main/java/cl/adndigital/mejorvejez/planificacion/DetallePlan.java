package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Medicamento dentro de un {@link PlanMedicamentos} ({@code DETALLE_PLAN},
 * HU-04), con dosis, vigencia, cantidades y horario. Sin {@code
 * HORARIO_JSON}: el horario vive en {@code DETALLE_PLAN_DIA} ({@link
 * #diasSemana}) y {@code DETALLE_PLAN_HORARIO} ({@link #horasAdministracion},
 * D3 — una fila por hora). Las horas se guardan como {@link LocalDateTime}
 * con la fecha fija 1970-01-01 (hora de reloj America/Santiago, sin
 * conversión de huso: {@code LocalDateTime} no lleva zona, así que Hibernate
 * la escribe literal en la columna {@code TIMESTAMP} sin zona, igual que la
 * lee); la BD exige además segundos en 0 ({@code CK_PLAN_HORARIO_SOLO_HORA}).
 * Estado en el catálogo {@code DETALLE_PLAN.ESTADO} (ACTIVO / INACTIVO); la
 * baja es siempre lógica y no toca el plan ni los demás detalles.
 */
@Entity
@Table(name = "DETALLE_PLAN")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetallePlan {

    /** Fecha fija de las filas de {@code DETALLE_PLAN_HORARIO} (D3). */
    private static final LocalDate FECHA_BASE_HORARIO = LocalDate.of(1970, 1, 1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_DETALLE_PLAN")
    private Long idDetallePlan;

    @Column(name = "ID_PLAN", nullable = false)
    private Long idPlan;

    @Column(name = "ID_MEDICAMENTO", nullable = false)
    private Long idMedicamento;

    @Column(name = "NOMBRE_MEDICAMENTO", nullable = false, length = 180)
    private String nombreMedicamento;

    @Column(name = "ID_PRESENTACION", nullable = false)
    private Long idPresentacion;

    @Column(name = "CONCENTRACION", length = 80)
    private String concentracion;

    @Column(name = "INSTRUCCIONES", length = 1000)
    private String instrucciones;

    @Column(name = "DOSIS_CANTIDAD", nullable = false, precision = 12, scale = 3)
    private BigDecimal dosisCantidad;

    @Column(name = "ID_UNIDAD_DOSIS", nullable = false)
    private Long idUnidadDosis;

    @Column(name = "CANTIDAD_INICIAL", precision = 12, scale = 3)
    private BigDecimal cantidadInicial;

    @Column(name = "CANTIDAD_DISPONIBLE", precision = 12, scale = 3)
    private BigDecimal cantidadDisponible;

    @Column(name = "FECHA_ESTIMADA_TERMINO")
    private LocalDate fechaEstimadaTermino;

    @Column(name = "MODO_CALCULO_TERMINO", nullable = false, length = 12)
    private String modoCalculoTermino;

    @Column(name = "FECHA_INICIO", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "FECHA_FIN")
    private LocalDate fechaFin;

    @Column(name = "ID_RESPONSABLE_PREDETERMINADO")
    private Long idResponsablePredeterminado;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /**
     * Días de la semana de administración ({@code
     * DETALLE_PLAN_DIA.ID_DIA_SEMANA}, catálogo {@code DIA_SEMANA}). {@code
     * @BatchSize} evita N+1 al listar varios detalles con {@code
     * spring.jpa.open-in-view=false}: en vez de una consulta por detalle para
     * traer sus días, agrupa hasta 50 ids de detalle por consulta.
     */
    @Getter(AccessLevel.NONE)
    @ElementCollection
    @CollectionTable(name = "DETALLE_PLAN_DIA", joinColumns = @JoinColumn(name = "ID_DETALLE_PLAN"))
    @Column(name = "ID_DIA_SEMANA", nullable = false)
    @BatchSize(size = 50)
    private Set<Long> diasSemana = new HashSet<>();

    /**
     * Horas de administración, expandidas como filas ({@code
     * DETALLE_PLAN_HORARIO.HORA_ADMINISTRACION}, D3). Mismo {@code
     * @BatchSize} que {@link #diasSemana}, por la misma razón.
     */
    @Getter(AccessLevel.NONE)
    @ElementCollection
    @CollectionTable(name = "DETALLE_PLAN_HORARIO", joinColumns = @JoinColumn(name = "ID_DETALLE_PLAN"))
    @Column(name = "HORA_ADMINISTRACION", nullable = false)
    @BatchSize(size = 50)
    private Set<LocalDateTime> horasAdministracion = new HashSet<>();

    /** Detalle nuevo del plan indicado ({@code POST .../planes/{idPlan}/detalles}), sin días ni horas todavía. */
    public DetallePlan(long idPlan, long idMedicamento, String nombreMedicamento, long idPresentacion,
            String concentracion, String instrucciones, BigDecimal dosisCantidad, long idUnidadDosis,
            BigDecimal cantidadInicial, BigDecimal cantidadDisponible, String modoCalculoTermino,
            LocalDate fechaEstimadaTermino, LocalDate fechaInicio, LocalDate fechaFin,
            Long idResponsablePredeterminado, long idEstado, OffsetDateTime fechaCreacion) {
        this.idPlan = idPlan;
        this.idMedicamento = idMedicamento;
        this.nombreMedicamento = nombreMedicamento;
        this.idPresentacion = idPresentacion;
        this.concentracion = concentracion;
        this.instrucciones = instrucciones;
        this.dosisCantidad = dosisCantidad;
        this.idUnidadDosis = idUnidadDosis;
        this.cantidadInicial = cantidadInicial;
        this.cantidadDisponible = cantidadDisponible;
        this.modoCalculoTermino = modoCalculoTermino;
        this.fechaEstimadaTermino = fechaEstimadaTermino;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.idResponsablePredeterminado = idResponsablePredeterminado;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /**
     * Días de la semana de administración (ids del catálogo {@code
     * DIA_SEMANA}), como copia materializada: a diferencia de un {@code
     * Collections.unmodifiableSet} sobre la colección gestionada por
     * Hibernate, esta copia sigue siendo legible fuera de la sesión/
     * transacción que la cargó.
     */
    public Set<Long> diasSemana() {
        return new HashSet<>(diasSemana);
    }

    /** Reemplaza por completo los días de administración ({@code DETALLE_PLAN_DIA}). */
    public void reemplazarDias(Set<Long> idsDia) {
        this.diasSemana.clear();
        this.diasSemana.addAll(idsDia);
    }

    /** Horas de administración, ordenadas, sin la fecha fija de almacenamiento. */
    public List<LocalTime> horas() {
        return horasAdministracion.stream()
                .map(LocalDateTime::toLocalTime)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Reemplaza por completo las horas de administración ({@code
     * DETALLE_PLAN_HORARIO}): borra las filas anteriores y guarda una fila
     * por cada hora indicada, combinada con la fecha fija 1970-01-01 y los
     * segundos en 0 ({@code CK_PLAN_HORARIO_SOLO_HORA}).
     */
    public void reemplazarHorario(List<LocalTime> horas) {
        this.horasAdministracion.clear();
        for (LocalTime hora : horas) {
            this.horasAdministracion.add(LocalDateTime.of(FECHA_BASE_HORARIO, hora.withSecond(0).withNano(0)));
        }
    }

    /**
     * Cambia el medicamento del catálogo al que apunta el detalle y copia su
     * nombre a {@code NOMBRE_MEDICAMENTO} (instantánea: el detalle conserva
     * el nombre con que se indicó aunque el catálogo cambie después).
     */
    public void cambiarMedicamento(long idMedicamento, String nombreMedicamento, OffsetDateTime ahora) {
        this.idMedicamento = idMedicamento;
        this.nombreMedicamento = nombreMedicamento;
        this.fechaActualizacion = ahora;
    }

    /** Actualiza presentación, dosis, instrucciones, responsable y vigencia del detalle. */
    public void actualizarDatos(long idPresentacion, String concentracion, String instrucciones,
            BigDecimal dosisCantidad, long idUnidadDosis, Long idResponsablePredeterminado, LocalDate fechaInicio,
            LocalDate fechaFin, OffsetDateTime ahora) {
        this.idPresentacion = idPresentacion;
        this.concentracion = concentracion;
        this.instrucciones = instrucciones;
        this.dosisCantidad = dosisCantidad;
        this.idUnidadDosis = idUnidadDosis;
        this.idResponsablePredeterminado = idResponsablePredeterminado;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.fechaActualizacion = ahora;
    }

    /** Actualiza la cantidad inicial y disponible del detalle (p. ej. al reponer o consumir dosis). */
    public void actualizarCantidad(BigDecimal cantidadInicial, BigDecimal cantidadDisponible, OffsetDateTime ahora) {
        this.cantidadInicial = cantidadInicial;
        this.cantidadDisponible = cantidadDisponible;
        this.fechaActualizacion = ahora;
    }

    /**
     * Actualiza el modo de cálculo del término ({@code AUTOMATICO} |
     * {@code MANUAL}) y la fecha estimada resultante: calculada por {@code
     * CalculadoraTermino} en modo AUTOMATICO, ingresada a mano en modo MANUAL.
     */
    public void actualizarTermino(String modoCalculoTermino, LocalDate fechaEstimadaTermino, OffsetDateTime ahora) {
        this.modoCalculoTermino = modoCalculoTermino;
        this.fechaEstimadaTermino = fechaEstimadaTermino;
        this.fechaActualizacion = ahora;
    }

    /** Cambia el estado (desactivar / reactivar). No toca el plan ni los demás detalles. */
    public void cambiarEstado(long idEstado, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.fechaActualizacion = ahora;
    }
}
