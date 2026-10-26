package cl.adndigital.mejorvejez.planificacion;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Plan de medicamentos de un adulto mayor ({@code PLAN_MEDICAMENTOS}, HU-04),
 * aislado por {@code ID_GRUPO} igual que {@code adultos.AdultoMayor} (la FK
 * compuesta {@code FK_PLAN_ADULTO_GRUPO} impide que el plan quede asociado a
 * un adulto de otro grupo). Agrupa uno o más {@link DetallePlan}. Estado en
 * el catálogo {@code PLAN_MEDICAMENTOS.ESTADO} (ACTIVO / INACTIVO); la baja
 * es siempre lógica y no toca el estado de sus detalles.
 */
@Entity
@Table(name = "PLAN_MEDICAMENTOS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanMedicamentos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_PLAN")
    private Long idPlan;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "NOMBRE_PLAN", nullable = false, length = 160)
    private String nombrePlan;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "VIGENTE_DESDE", nullable = false)
    private LocalDate vigenteDesde;

    @Column(name = "VIGENTE_HASTA")
    private LocalDate vigenteHasta;

    @Column(name = "ID_USUARIO_CREADOR", nullable = false)
    private Long idUsuarioCreador;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "ID_USUARIO_ACTUALIZACION")
    private Long idUsuarioActualizacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /** Plan nuevo del adulto mayor indicado ({@code POST .../adultos/{idAdulto}/planes}). */
    public PlanMedicamentos(long idGrupo, long idAdultoMayor, String nombrePlan, LocalDate vigenteDesde,
            LocalDate vigenteHasta, long idUsuarioCreador, long idEstado, OffsetDateTime fechaCreacion) {
        this.idGrupo = idGrupo;
        this.idAdultoMayor = idAdultoMayor;
        this.nombrePlan = nombrePlan;
        this.vigenteDesde = vigenteDesde;
        this.vigenteHasta = vigenteHasta;
        this.idUsuarioCreador = idUsuarioCreador;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /** Actualiza nombre y vigencia del plan ({@code PATCH .../planes/{idPlan}}). */
    public void actualizar(String nombrePlan, LocalDate vigenteDesde, LocalDate vigenteHasta, long idUsuario,
            OffsetDateTime ahora) {
        this.nombrePlan = nombrePlan;
        this.vigenteDesde = vigenteDesde;
        this.vigenteHasta = vigenteHasta;
        this.idUsuarioActualizacion = idUsuario;
        this.fechaActualizacion = ahora;
    }

    /**
     * Marca el plan como modificado sin cambiar sus propios datos: lo usa el
     * servicio de detalles cuando agrega, actualiza o cambia el estado de un
     * {@link DetallePlan} del plan, para que {@code FECHA_ACTUALIZACION}
     * refleje también los cambios en sus detalles.
     */
    public void tocar(long idUsuario, OffsetDateTime ahora) {
        this.idUsuarioActualizacion = idUsuario;
        this.fechaActualizacion = ahora;
    }

    /** Cambia el estado (desactivar / reactivar), sin tocar el estado de sus detalles. */
    public void cambiarEstado(long idEstado, long idUsuario, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.idUsuarioActualizacion = idUsuario;
        this.fechaActualizacion = ahora;
    }
}
