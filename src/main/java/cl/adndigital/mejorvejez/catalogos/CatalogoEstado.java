package cl.adndigital.mejorvejez.catalogos;

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
 * Catálogo de estados por dominio ({@code NOMBRE_TABLA.NOMBRE_CAMPO}):
 * cada tabla/campo de negocio tiene su propio juego de códigos de estado
 * (p. ej. {@code TAREA_CUIDADO.ESTADO}). Lo siembra {@code V2__catalogos.sql};
 * la aplicación solo lo lee (ver {@code ServicioCatalogos}).
 */
@Entity
@Table(name = "CATALOGO_ESTADOS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogoEstado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ESTADO")
    private Long idEstado;

    @Column(name = "NOMBRE_TABLA", nullable = false, length = 60)
    private String nombreTabla;

    @Column(name = "NOMBRE_CAMPO", nullable = false, length = 60)
    private String nombreCampo;

    @Column(name = "CODIGO_ESTADO", nullable = false, length = 30)
    private String codigoEstado;

    @Column(name = "DESCRIPCION", nullable = false, length = 250)
    private String descripcion;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ACTIVO", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean activo;

    @Column(name = "ORDEN", nullable = false)
    private Integer orden;
}
