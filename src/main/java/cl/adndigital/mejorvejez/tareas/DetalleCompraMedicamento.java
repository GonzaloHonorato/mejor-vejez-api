package cl.adndigital.mejorvejez.tareas;

import java.math.BigDecimal;

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
 * Medicamento incluido en una tarea de {@code COMPRA_RETIRO_MEDICAMENTOS}
 * ({@code DETALLE_COMPRA_MEDICAMENTOS}): una tarea de ese tipo puede incluir
 * varios. Fila simple, sin métodos de dominio (no se edita: para corregirla
 * se elimina y se vuelve a crear, a cargo de la tarea siguiente que exponga
 * el servicio).
 */
@Entity
@Table(name = "DETALLE_COMPRA_MEDICAMENTOS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetalleCompraMedicamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_DETALLE_COMPRA")
    private Long idDetalleCompra;

    @Column(name = "ID_TAREA", nullable = false)
    private Long idTarea;

    @Column(name = "ID_MEDICAMENTO", nullable = false)
    private Long idMedicamento;

    @Column(name = "ID_PRESENTACION", nullable = false)
    private Long idPresentacion;

    @Column(name = "DOSIS_INDICADA", length = 200)
    private String dosisIndicada;

    @Column(name = "CANTIDAD_SOLICITADA", nullable = false, precision = 12, scale = 3)
    private BigDecimal cantidadSolicitada;

    @Column(name = "ID_UNIDAD_CANTIDAD", nullable = false)
    private Long idUnidadCantidad;

    @Column(name = "OBSERVACIONES", length = 500)
    private String observaciones;

    /** Línea de compra nueva de la tarea indicada. */
    public DetalleCompraMedicamento(long idTarea, long idMedicamento, long idPresentacion, String dosisIndicada,
            BigDecimal cantidadSolicitada, long idUnidadCantidad, String observaciones) {
        this.idTarea = idTarea;
        this.idMedicamento = idMedicamento;
        this.idPresentacion = idPresentacion;
        this.dosisIndicada = dosisIndicada;
        this.cantidadSolicitada = cantidadSolicitada;
        this.idUnidadCantidad = idUnidadCantidad;
        this.observaciones = observaciones;
    }
}
