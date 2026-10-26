package cl.adndigital.mejorvejez.tareas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Médico y lugar de una tarea de {@code CITAS_CONTROLES_MEDICOS} ({@code
 * DETALLE_CITA_MEDICA}). {@code ID_TAREA} es su propia clave primaria (uno a
 * uno con {@link TareaCuidado}, sin secuencia propia): no lleva {@code
 * @GeneratedValue}, el valor lo fija el llamador con el id de la tarea ya
 * creada.
 */
@Entity
@Table(name = "DETALLE_CITA_MEDICA")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetalleCitaMedica {

    @Id
    @Column(name = "ID_TAREA")
    private Long idTarea;

    @Column(name = "NOMBRE_MEDICO", nullable = false, length = 180)
    private String nombreMedico;

    @Column(name = "LUGAR_ATENCION", nullable = false, length = 250)
    private String lugarAtencion;

    @Column(name = "OBSERVACIONES", length = 1000)
    private String observaciones;

    /** Detalle de cita nuevo, para la tarea ya creada {@code idTarea}. */
    public DetalleCitaMedica(long idTarea, String nombreMedico, String lugarAtencion, String observaciones) {
        this.idTarea = idTarea;
        this.nombreMedico = nombreMedico;
        this.lugarAtencion = lugarAtencion;
        this.observaciones = observaciones;
    }
}
