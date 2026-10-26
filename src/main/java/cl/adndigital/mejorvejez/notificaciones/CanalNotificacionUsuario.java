package cl.adndigital.mejorvejez.notificaciones;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import cl.adndigital.mejorvejez.comun.ConvertidorSiNo;

/**
 * Canal de notificación de un usuario ({@code CANAL_NOTIFICACION_USUARIO},
 * TSA-45/TSA-46): una fila por (usuario, canal), {@code CORREO} o {@code
 * WHATSAPP}. {@code HABILITADO} usa el patrón {@code CHAR(1)} {@code S}/{@code
 * N} ({@link ConvertidorSiNo}), igual que otras columnas booleanas del
 * esquema.
 *
 * <p>Si un usuario no tiene fila {@code CORREO}, su canal operativo CORREO es
 * el {@code USUARIO.EMAIL} de la cuenta (regla 8 de la Fase 5): esa fila
 * predeterminada no se persiste aquí, la resuelve el servicio que calcula los
 * canales operativos.
 */
@Entity
@Table(name = "CANAL_NOTIFICACION_USUARIO")
@IdClass(CanalNotificacionUsuarioId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanalNotificacionUsuario {

    @Id
    @Column(name = "ID_USUARIO")
    private Long idUsuario;

    @Id
    @Column(name = "CANAL", length = 20)
    private String canal;

    @Column(name = "DESTINO", nullable = false, length = 254)
    private String destino;

    @Convert(converter = ConvertidorSiNo.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "HABILITADO", nullable = false, columnDefinition = "CHAR(1)")
    private Boolean habilitado;

    @Column(name = "FECHA_ACTUALIZACION", nullable = false)
    private OffsetDateTime fechaActualizacion;

    /** Canal nuevo del usuario indicado, con su destino y si está habilitado. */
    public CanalNotificacionUsuario(long idUsuario, String canal, String destino, boolean habilitado,
            OffsetDateTime ahora) {
        this.idUsuario = idUsuario;
        this.canal = canal;
        this.destino = destino;
        this.habilitado = habilitado;
        this.fechaActualizacion = ahora;
    }

    /** Actualiza destino y habilitación (regla 17 de la Fase 5: {@code PUT /api/yo/canales}). */
    public void actualizar(String destino, boolean habilitado, OffsetDateTime ahora) {
        this.destino = destino;
        this.habilitado = habilitado;
        this.fechaActualizacion = ahora;
    }
}
