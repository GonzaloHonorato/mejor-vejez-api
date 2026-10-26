package cl.adndigital.mejorvejez.planificacion;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import cl.adndigital.mejorvejez.comun.ConvertidorSiNo;

/**
 * Medicamento del catálogo GLOBAL ({@code MEDICAMENTO}), compartido por
 * todos los grupos (INC-19, HU-04). La unicidad normalizada por
 * {@code (UPPER(TRIM(NOMBRE)), ID_PRESENTACION, UPPER(TRIM(CONCENTRACION)))}
 * la impone {@code UX_MEDICAMENTO_NORMALIZADO} (V3); el backend la respeta
 * buscando primero el existente antes de insertar ({@code
 * ServicioMedicamentos}). Estado con bandera {@code ACTIVO} ('S'/'N'), sin
 * catálogo de estados (a diferencia de las demás entidades operativas): es
 * un catálogo compartido, no un recurso de un grupo.
 */
@Entity
@Table(name = "MEDICAMENTO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Medicamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_MEDICAMENTO")
    private Long idMedicamento;

    @Column(name = "NOMBRE", nullable = false, length = 180)
    private String nombre;

    @Column(name = "PRINCIPIO_ACTIVO", length = 180)
    private String principioActivo;

    @Column(name = "ID_PRESENTACION")
    private Long idPresentacion;

    @Column(name = "CONCENTRACION", length = 80)
    private String concentracion;

    @Column(name = "DESCRIPCION", length = 1000)
    private String descripcion;

    @Getter(AccessLevel.NONE)
    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ACTIVO", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean activo;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    /**
     * Medicamento nuevo del catálogo global ({@code POST
     * /api/grupos/{idGrupo}/medicamentos}), siempre {@code ACTIVO}.
     */
    public Medicamento(String nombre, String principioActivo, Long idPresentacion, String concentracion,
            OffsetDateTime fechaCreacion) {
        this.nombre = nombre;
        this.principioActivo = principioActivo;
        this.idPresentacion = idPresentacion;
        this.concentracion = concentracion;
        this.activo = true;
        this.fechaCreacion = fechaCreacion;
    }

    public boolean isActivo() {
        return Boolean.TRUE.equals(activo);
    }
}
