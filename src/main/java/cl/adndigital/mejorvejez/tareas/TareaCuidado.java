package cl.adndigital.mejorvejez.tareas;

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
 * Tarea de cuidado ({@code TAREA_CUIDADO}, HU-05/06/07): medicamentos,
 * compra/retiro de medicamentos o citas/controles médicos. {@code
 * CLAVE_OCURRENCIA} e {@code ID_DETALLE_PLAN} solo existen en {@code
 * MEDICAMENTOS} ({@code CK_TAREA_MEDICAMENTO}); esa clave es la misma que la
 * de su {@link OcurrenciaPlanificada} ({@code DET-}idDetalle{@code -}
 * horarioUtc{@code -}revisión, regla 3 de la Fase 4) y, junto con el índice
 * {@code UQ_TAREA_CLAVE_OCURRENCIA}, es la última barrera contra duplicados.
 *
 * <p>Las tareas de {@code MEDICAMENTOS} solo se crean con {@link
 * #deMedicamento} (el generador) y solo se cancelan por el recálculo tras un
 * cambio de plan (regla 5); nunca a mano. Las manuales ({@code
 * COMPRA_RETIRO_MEDICAMENTOS} | {@code CITAS_CONTROLES_MEDICOS}) se crean con
 * {@link #manual} y sí las cancela el coordinador.
 *
 * <p>{@code GENERA_ALERTA} y {@code MINUTOS_TOLERANCIA} se fijan siempre a
 * {@code true} / 30 al crear la tarea (esta tarea no los deja configurables;
 * F5 los usará para las alertas por atraso, regla 12).
 */
@Entity
@Table(name = "TAREA_CUIDADO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TareaCuidado {

    private static final boolean GENERA_ALERTA_DEFECTO = true;
    private static final int MINUTOS_TOLERANCIA_DEFECTO = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_TAREA")
    private Long idTarea;

    @Column(name = "TIPO_TAREA", nullable = false, length = 40)
    private String tipoTarea;

    @Column(name = "CLAVE_OCURRENCIA", length = 200)
    private String claveOcurrencia;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "ID_DETALLE_PLAN")
    private Long idDetallePlan;

    @Column(name = "TITULO_TAREA", nullable = false, length = 200)
    private String tituloTarea;

    @Column(name = "DESCRIPCION_TAREA", length = 1000)
    private String descripcionTarea;

    @Column(name = "FECHA_HORARIO", nullable = false)
    private OffsetDateTime fechaHorario;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "GENERA_ALERTA", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean generaAlerta;

    @Column(name = "MINUTOS_TOLERANCIA", nullable = false)
    private Integer minutosTolerancia;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    @Column(name = "OBSERVACION_CIERRE", length = 1000)
    private String observacionCierre;

    @Column(name = "ID_USUARIO_CONFIRMADOR")
    private Long idUsuarioConfirmador;

    @Column(name = "FECHA_CONFIRMACION")
    private OffsetDateTime fechaConfirmacion;

    @Column(name = "MOTIVO_CANCELACION", length = 200)
    private String motivoCancelacion;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "FECHA_ACTUALIZACION")
    private OffsetDateTime fechaActualizacion;

    private TareaCuidado(String tipoTarea, String claveOcurrencia, long idGrupo, long idAdultoMayor,
            Long idDetallePlan, String tituloTarea, String descripcionTarea, OffsetDateTime fechaHorario,
            long idEstado, OffsetDateTime ahora) {
        this.tipoTarea = tipoTarea;
        this.claveOcurrencia = claveOcurrencia;
        this.idGrupo = idGrupo;
        this.idAdultoMayor = idAdultoMayor;
        this.idDetallePlan = idDetallePlan;
        this.tituloTarea = tituloTarea;
        this.descripcionTarea = descripcionTarea;
        this.fechaHorario = fechaHorario;
        this.generaAlerta = GENERA_ALERTA_DEFECTO;
        this.minutosTolerancia = MINUTOS_TOLERANCIA_DEFECTO;
        this.idEstado = idEstado;
        this.fechaCreacion = ahora;
    }

    /**
     * Tarea de {@code MEDICAMENTOS} generada para la ocurrencia de clave
     * {@code claveOcurrencia} (la misma que {@link
     * OcurrenciaPlanificada#getClaveOcurrencia()}). Solo la crea el
     * generador (regla 5 de la Fase 4): nunca a mano.
     */
    public static TareaCuidado deMedicamento(String claveOcurrencia, long idGrupo, long idAdulto,
            long idDetallePlan, String titulo, String descripcion, OffsetDateTime fechaHorario,
            long idEstadoPendiente, OffsetDateTime ahora) {
        return new TareaCuidado(CodigosTareas.TIPO_TAREA_MEDICAMENTOS, claveOcurrencia, idGrupo, idAdulto,
                idDetallePlan, titulo, descripcion, fechaHorario, idEstadoPendiente, ahora);
    }

    /**
     * Tarea manual ({@code tipoTarea} = {@code COMPRA_RETIRO_MEDICAMENTOS} |
     * {@code CITAS_CONTROLES_MEDICOS}), sin clave de ocurrencia ni detalle de
     * plan (ambos {@code null}, como exige {@code CK_TAREA_MEDICAMENTO} para
     * cualquier tipo distinto de {@code MEDICAMENTOS}).
     */
    public static TareaCuidado manual(String tipoTarea, long idGrupo, long idAdulto, String titulo,
            String descripcion, OffsetDateTime fechaHorario, long idEstadoPendiente, OffsetDateTime ahora) {
        return new TareaCuidado(tipoTarea, null, idGrupo, idAdulto, null, titulo, descripcion, fechaHorario,
                idEstadoPendiente, ahora);
    }

    /** Cambia el estado de la tarea (transición simple, sin cierre ni cancelación; HU-07, regla 10). */
    public void cambiarEstado(long idEstado, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.fechaActualizacion = ahora;
    }

    /**
     * Confirma el resultado de la tarea ({@code COMPLETADA} | {@code
     * NO_REALIZADA}), con el confirmador, la fecha y la observación de
     * cierre (reglas 10/11 de la Fase 4).
     */
    public void confirmar(long idEstado, long idUsuario, String observacion, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.idUsuarioConfirmador = idUsuario;
        this.observacionCierre = observacion;
        this.fechaConfirmacion = ahora;
        this.fechaActualizacion = ahora;
    }

    /** Cancela la tarea con el motivo indicado (hasta 200 caracteres, {@code MOTIVO_CANCELACION}). */
    public void cancelar(long idEstado, String motivo, OffsetDateTime ahora) {
        this.idEstado = idEstado;
        this.motivoCancelacion = motivo;
        this.fechaActualizacion = ahora;
    }

    /**
     * Ajusta título y descripción con los datos vigentes del detalle del
     * plan (recálculo, regla 8: "Ajustar"). El responsable asignado nunca
     * cambia por este método (HU-06).
     */
    public void ajustarTexto(String titulo, String descripcion, OffsetDateTime ahora) {
        this.tituloTarea = titulo;
        this.descripcionTarea = descripcion;
        this.fechaActualizacion = ahora;
    }
}
