package cl.adndigital.mejorvejez.auditoria;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Evento de auditoría ({@code EVENTO_AUDITORIA}): registro append-only de
 * acciones relevantes (HU-12). El trigger {@code TRG_AUDITORIA_INMUTABLE}
 * (V3) impide modificarlo o eliminarlo, incluso para el dueño del esquema; la
 * aplicación solo inserta (ver {@link ServicioAuditoria}).
 */
@Entity
@Table(name = "EVENTO_AUDITORIA")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventoAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_EVENTO_AUDITORIA")
    private Long idEventoAuditoria;

    @Column(name = "NOMBRE_SERVICIO", nullable = false, length = 80)
    private String nombreServicio;

    @Column(name = "TIPO_ENTIDAD", nullable = false, length = 80)
    private String tipoEntidad;

    @Column(name = "ID_ENTIDAD", nullable = false, length = 100)
    private String idEntidad;

    @Column(name = "ID_GRUPO")
    private Long idGrupo;

    @Column(name = "ID_USUARIO_ACTOR")
    private Long idUsuarioActor;

    @Column(name = "CODIGO_ACCION", nullable = false, length = 60)
    private String codigoAccion;

    @Column(name = "FECHA_EVENTO", nullable = false)
    private OffsetDateTime fechaEvento;

    @Column(name = "ID_RESULTADO", nullable = false)
    private Long idResultado;

    @Column(name = "ID_CORRELACION", length = 100)
    private String idCorrelacion;

    @Lob
    @Column(name = "DETALLES_JSON")
    private String detallesJson;

    public EventoAuditoria(String nombreServicio, String tipoEntidad, String idEntidad, Long idGrupo,
            Long idUsuarioActor, String codigoAccion, OffsetDateTime fechaEvento, Long idResultado,
            String idCorrelacion, String detallesJson) {
        this.nombreServicio = nombreServicio;
        this.tipoEntidad = tipoEntidad;
        this.idEntidad = idEntidad;
        this.idGrupo = idGrupo;
        this.idUsuarioActor = idUsuarioActor;
        this.codigoAccion = codigoAccion;
        this.fechaEvento = fechaEvento;
        this.idResultado = idResultado;
        this.idCorrelacion = idCorrelacion;
        this.detallesJson = detallesJson;
    }
}
