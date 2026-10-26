package cl.adndigital.mejorvejez.tareas;

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
 * Registro idempotente de la generación de tareas de medicamentos ({@code
 * OCURRENCIA_PLANIFICADA}, HU-05). Una fila por turno (detalle + instante)
 * intentado: {@code CLAVE_OCURRENCIA} (regla 3 de la Fase 4,
 * {@code "DET-" + idDetalle + "-" + horarioUtc + "-" + revision}) es
 * {@code UNIQUE} y, junto con el mismo valor en {@link
 * TareaCuidado#getClaveOcurrencia()}, es la última barrera contra
 * duplicados. {@code ID_ESTADO=TAREA_CREADA} no se sincroniza con el estado
 * posterior de la tarea: una ocurrencia queda {@code TAREA_CREADA} para
 * siempre aunque la tarea después se cancele (regla 4).
 */
@Entity
@Table(name = "OCURRENCIA_PLANIFICADA")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OcurrenciaPlanificada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_OCURRENCIA")
    private Long idOcurrencia;

    @Column(name = "ID_DETALLE_PLAN", nullable = false)
    private Long idDetallePlan;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "FECHA_HORARIO", nullable = false)
    private OffsetDateTime fechaHorario;

    @Column(name = "CLAVE_OCURRENCIA", nullable = false, length = 200)
    private String claveOcurrencia;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "ID_TAREA")
    private Long idTarea;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    /** Ocurrencia nueva del turno (detalle + instante) indicado, sin tarea vinculada todavía. */
    public OcurrenciaPlanificada(long idDetalle, long idAdulto, long idGrupo, OffsetDateTime fechaHorario,
            String clave, long idEstado, OffsetDateTime ahora) {
        this.idDetallePlan = idDetalle;
        this.idAdultoMayor = idAdulto;
        this.idGrupo = idGrupo;
        this.fechaHorario = fechaHorario;
        this.claveOcurrencia = clave;
        this.idEstado = idEstado;
        this.fechaCreacion = ahora;
    }

    /** Vincula la ocurrencia a la tarea creada para ella y pasa a {@code TAREA_CREADA} (fase B del generador). */
    public void vincularTarea(long idTarea, long idEstadoTareaCreada) {
        this.idTarea = idTarea;
        this.idEstado = idEstadoTareaCreada;
    }

    /** Cambia el estado de la ocurrencia (p. ej. a {@code CANCELADA} o {@code ERROR}). */
    public void cambiarEstado(long idEstado) {
        this.idEstado = idEstado;
    }
}
