package cl.adndigital.mejorvejez.usuarios;

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
 * Usuario de la aplicación ({@code USUARIO}), identificado por el uid de
 * Firebase. No guarda contraseñas ni tokens: la autenticación la hace
 * Firebase. {@code esAdministrador} es el superadmin global (INC-02). Lo da
 * de alta {@code ServicioUsuarioActual} en el primer ingreso.
 */
@Entity
@Table(name = "USUARIO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_USUARIO")
    private Long idUsuario;

    @Column(name = "UID_FIREBASE", nullable = false, length = 128)
    private String uidFirebase;

    @Column(name = "EMAIL", nullable = false, length = 254)
    private String email;

    @Column(name = "NOMBRE", nullable = false, length = 160)
    private String nombre;

    @Column(name = "TELEFONO", length = 30)
    private String telefono;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ES_ADMINISTRADOR", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean esAdministrador;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    /** Usuario nuevo, no administrador. */
    public Usuario(String uidFirebase, String email, String nombre, long idEstado, OffsetDateTime fechaCreacion) {
        this.uidFirebase = uidFirebase;
        this.email = email;
        this.nombre = nombre;
        this.esAdministrador = false;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /**
     * Usuario nuevo, no administrador, con teléfono (alta de cuentas,
     * HU-01): {@code telefono} nulo o en blanco no se guarda.
     */
    public Usuario(String uidFirebase, String email, String nombre, String telefono, long idEstado,
            OffsetDateTime fechaCreacion) {
        this(uidFirebase, email, nombre, idEstado, fechaCreacion);
        this.telefono = (telefono == null || telefono.isBlank()) ? null : telefono.trim();
    }

    /**
     * Actualiza nombre y teléfono del perfil ({@code PATCH /api/yo}).
     * {@code nombre} se guarda sin espacios en los extremos; {@code
     * telefono} nulo o en blanco borra el teléfono guardado.
     */
    public void actualizarPerfil(String nombre, String telefono, OffsetDateTime fecha) {
        this.nombre = nombre.trim();
        this.telefono = (telefono == null || telefono.isBlank()) ? null : telefono.trim();
        this.fechaActualizacion = fecha;
    }

    /**
     * Actualiza nombre, teléfono y el rol de administrador desde la
     * administración global de cuentas ({@code PATCH
     * /api/usuarios/{idUsuario}}). Mismo tratamiento de nombre/teléfono que
     * {@link #actualizarPerfil}; el correo no se edita acá tampoco.
     */
    public void actualizarDesdeAdministracion(String nombre, String telefono, boolean esAdministrador,
            OffsetDateTime fecha) {
        this.nombre = nombre.trim();
        this.telefono = (telefono == null || telefono.isBlank()) ? null : telefono.trim();
        this.esAdministrador = esAdministrador;
        this.fechaActualizacion = fecha;
    }

    /**
     * Cambia el estado global del usuario ({@code PUT
     * /api/usuarios/{idUsuario}/estado}). No toca sus membresías de grupo
     * (HU-01): el acceso a cada grupo lo decide {@code ServicioAutorizacion}
     * por la membresía, no por este estado.
     */
    public void cambiarEstado(long idEstadoNuevo, OffsetDateTime fecha) {
        this.idEstado = idEstadoNuevo;
        this.fechaActualizacion = fecha;
    }
}
