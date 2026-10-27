package cl.adndigital.mejorvejez.relevos;

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
 * Elemento de la foto de tareas y alertas pendientes guardada al registrar
 * un relevo ({@code DETALLE_TRASPASO}, HU-11, nota interna de TSA-11).
 * {@code ID_REFERENCIA} es polimórfica ({@code TIPO_ELEMENTO} = {@link
 * CodigosRelevos#TIPO_ELEMENTO_TAREA} | {@link
 * CodigosRelevos#TIPO_ELEMENTO_ALERTA}); la valida el backend al armar la
 * foto filtrando siempre por grupo y adulto (regla 4 de la Fase 7), nunca
 * una FK de la base de datos.
 *
 * <p>Inmutable: una vez guardada, nunca se actualiza ni se borra, ni
 * siquiera al cancelar el relevo (regla 7 de la Fase 7).
 */
@Entity
@Table(name = "DETALLE_TRASPASO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetalleTraspaso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_DETALLE_TRASPASO")
    private Long idDetalleTraspaso;

    @Column(name = "ID_TRASPASO", nullable = false)
    private Long idTraspaso;

    @Column(name = "TIPO_ELEMENTO", nullable = false, length = 20)
    private String tipoElemento;

    @Column(name = "ID_REFERENCIA", nullable = false)
    private Long idReferencia;

    @Column(name = "RESUMEN", length = 1000)
    private String resumen;

    /** Elemento nuevo de la foto del relevo indicado. */
    public DetalleTraspaso(long idTraspaso, String tipoElemento, long idReferencia, String resumen) {
        this.idTraspaso = idTraspaso;
        this.tipoElemento = tipoElemento;
        this.idReferencia = idReferencia;
        this.resumen = resumen;
    }
}
