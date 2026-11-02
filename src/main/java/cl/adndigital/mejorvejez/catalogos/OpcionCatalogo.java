package cl.adndigital.mejorvejez.catalogos;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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
 * Catálogo de opciones de negocio ({@code CATALOGO_NEGOCIO}): listas de
 * valores fijos por {@link TipoCatalogo} (presentación de medicamento,
 * unidad de dosis, día de semana, parentesco). Lo siembran {@code
 * V2__catalogos.sql} y {@code V6__parentesco_y_contactos.sql}; la
 * aplicación solo lo lee (ver {@code ServicioCatalogos}).
 */
@Entity
@Table(name = "CATALOGO_NEGOCIO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpcionCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_OPCION")
    private Long idOpcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "TIPO_CATALOGO", nullable = false, length = 50)
    private TipoCatalogo tipoCatalogo;

    @Column(name = "CODIGO", nullable = false, length = 50)
    private String codigo;

    @Column(name = "DESCRIPCION", nullable = false, length = 160)
    private String descripcion;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ACTIVO", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean activo;

    @Column(name = "ORDEN", nullable = false)
    private Integer orden;
}
