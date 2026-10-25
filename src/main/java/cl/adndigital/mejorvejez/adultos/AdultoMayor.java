package cl.adndigital.mejorvejez.adultos;

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
 * Adulto mayor atendido por un grupo ({@code ADULTO_MAYOR}), aislado por
 * {@code ID_GRUPO} (HU-03; {@code UQ_ADULTO_GRUPO} es el destino de las FK
 * compuestas que impiden datos cruzados entre familias). Contenido
 * operativo, sin diagnósticos. Estado en el catálogo {@code
 * ADULTO_MAYOR.ESTADO} (ACTIVO / INACTIVO).
 */
@Entity
@Table(name = "ADULTO_MAYOR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdultoMayor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ADULTO_MAYOR")
    private Long idAdultoMayor;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "CODIGO_INTERNO", length = 50)
    private String codigoInterno;

    @Column(name = "NOMBRE_COMPLETO", nullable = false, length = 180)
    private String nombreCompleto;

    @Column(name = "FECHA_NACIMIENTO")
    private LocalDate fechaNacimiento;

    @Column(name = "NOTAS_EMERGENCIA", length = 1000)
    private String notasEmergencia;

    @Column(name = "OBSERVACIONES_GENERALES", length = 2000)
    private String observacionesGenerales;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "ID_USUARIO_CREADOR", nullable = false)
    private Long idUsuarioCreador;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    @Column(name = "ID_USUARIO_ACTUALIZACION")
    private Long idUsuarioActualizacion;

    /** Adulto mayor nuevo del grupo indicado ({@code POST /api/grupos/{idGrupo}/adultos}). */
    public AdultoMayor(long idGrupo, String codigoInterno, String nombreCompleto, LocalDate fechaNacimiento,
            String notasEmergencia, String observacionesGenerales, long idUsuarioCreador, long idEstado,
            OffsetDateTime fechaCreacion) {
        this.idGrupo = idGrupo;
        this.codigoInterno = codigoInterno;
        this.nombreCompleto = nombreCompleto;
        this.fechaNacimiento = fechaNacimiento;
        this.notasEmergencia = notasEmergencia;
        this.observacionesGenerales = observacionesGenerales;
        this.idUsuarioCreador = idUsuarioCreador;
        this.idEstado = idEstado;
        this.fechaCreacion = fechaCreacion;
    }

    /** Actualiza los datos del perfil ({@code PATCH .../adultos/{idAdulto}}). */
    public void actualizar(String codigoInterno, String nombreCompleto, LocalDate fechaNacimiento,
            String notasEmergencia, String observacionesGenerales, long idUsuario, OffsetDateTime ahora) {
        this.codigoInterno = codigoInterno;
        this.nombreCompleto = nombreCompleto;
        this.fechaNacimiento = fechaNacimiento;
        this.notasEmergencia = notasEmergencia;
        this.observacionesGenerales = observacionesGenerales;
        this.idUsuarioActualizacion = idUsuario;
        this.fechaActualizacion = ahora;
    }

    /** Cambia el estado (desactivar / reactivar), dejando trazabilidad del actor y la fecha. */
    public void cambiarEstado(long idEstado, long idUsuario, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.idUsuarioActualizacion = idUsuario;
        this.fechaActualizacion = ahora;
    }
}
