package cl.adndigital.mejorvejez.adultos;

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
 * Contacto de emergencia de un adulto mayor ({@code CONTACTO_EMERGENCIA}).
 * A lo más un contacto ACTIVO con {@code ES_PRINCIPAL = 'S'} por adulto
 * mayor ({@code UX_CONTACTO_PRINCIPAL_ACTIVO}, V3): red de seguridad de la
 * regla de negocio del contacto principal único (HU-03). {@code
 * TIPO_CONTACTO} es siempre {@code EMERGENCIA} en este MVP. Estado en el
 * catálogo {@code CONTACTO_EMERGENCIA.ESTADO} (ACTIVO / INACTIVO).
 */
@Entity
@Table(name = "CONTACTO_EMERGENCIA")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactoEmergencia {

    private static final String TIPO_CONTACTO_EMERGENCIA = "EMERGENCIA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_CONTACTO")
    private Long idContacto;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "NOMBRE_CONTACTO", nullable = false, length = 160)
    private String nombreContacto;

    @Column(name = "PARENTESCO", length = 80)
    private String parentesco;

    @Column(name = "TELEFONO", nullable = false, length = 30)
    private String telefono;

    @Column(name = "TIPO_CONTACTO", nullable = false, length = 30)
    private String tipoContacto;

    @Getter(AccessLevel.NONE)
    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ES_PRINCIPAL", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean esPrincipal;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "ID_USUARIO_CREADOR", nullable = false)
    private Long idUsuarioCreador;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /** Contacto nuevo del adulto mayor indicado ({@code POST .../adultos/{idAdulto}/contactos}). */
    public ContactoEmergencia(long idAdultoMayor, String nombreContacto, String parentesco, String telefono,
            boolean esPrincipal, long idEstado, long idUsuarioCreador, OffsetDateTime fechaCreacion) {
        this.idAdultoMayor = idAdultoMayor;
        this.nombreContacto = nombreContacto;
        this.parentesco = parentesco;
        this.telefono = telefono;
        this.tipoContacto = TIPO_CONTACTO_EMERGENCIA;
        this.esPrincipal = esPrincipal;
        this.idEstado = idEstado;
        this.idUsuarioCreador = idUsuarioCreador;
        this.fechaCreacion = fechaCreacion;
    }

    /** Actualiza nombre, parentesco y teléfono ({@code PATCH .../contactos/{idContacto}}). */
    public void actualizar(String nombreContacto, String parentesco, String telefono, OffsetDateTime ahora) {
        this.nombreContacto = nombreContacto;
        this.parentesco = parentesco;
        this.telefono = telefono;
        this.fechaActualizacion = ahora;
    }

    /** Marca o desmarca este contacto como principal. */
    public void marcarPrincipal(boolean principal, OffsetDateTime ahora) {
        this.esPrincipal = principal;
        this.fechaActualizacion = ahora;
    }

    /** Cambia el estado (desactivar / reactivar). */
    public void cambiarEstado(long idEstado, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.fechaActualizacion = ahora;
    }

    public boolean isEsPrincipal() {
        return Boolean.TRUE.equals(esPrincipal);
    }
}
