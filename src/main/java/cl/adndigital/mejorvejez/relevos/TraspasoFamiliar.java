package cl.adndigital.mejorvejez.relevos;

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
 * Relevo del turno de cuidado entre integrantes de un grupo ({@code
 * TRASPASO_FAMILIAR}, HU-11, TSA-51): quién entrega ({@code
 * ID_USUARIO_SALIENTE}) y quién recibe ({@code ID_USUARIO_ENTRANTE}) el
 * cuidado de un adulto mayor, con la foto de tareas y alertas pendientes al
 * momento del registro ({@link DetalleTraspaso}).
 *
 * <p>{@code BORRADOR} no se usa en el MVP (regla 3 de la Fase 7): todo
 * relevo se crea directamente {@code COMPLETADA} con {@code FECHA_TRASPASO
 * = FECHA_CREACION}, y solo puede pasar a {@code CANCELADA} ({@link
 * #cancelar}); el relevo nunca modifica el resto de sus propios datos (ni
 * los de la foto, que se conserva igual al cancelar, regla 7).
 */
@Entity
@Table(name = "TRASPASO_FAMILIAR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TraspasoFamiliar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_TRASPASO")
    private Long idTraspaso;

    @Column(name = "ID_GRUPO", nullable = false)
    private Long idGrupo;

    @Column(name = "ID_ADULTO_MAYOR", nullable = false)
    private Long idAdultoMayor;

    @Column(name = "ID_USUARIO_SALIENTE", nullable = false)
    private Long idUsuarioSaliente;

    @Column(name = "ID_USUARIO_ENTRANTE", nullable = false)
    private Long idUsuarioEntrante;

    @Column(name = "FECHA_TRASPASO", nullable = false)
    private OffsetDateTime fechaTraspaso;

    @Column(name = "NOTAS", length = 2000)
    private String notas;

    @Column(name = "ID_USUARIO_CREADOR", nullable = false)
    private Long idUsuarioCreador;

    @Column(name = "FECHA_CREACION", nullable = false)
    private OffsetDateTime fechaCreacion;

    @Column(name = "ID_ESTADO", nullable = false)
    private Long idEstado;

    private TraspasoFamiliar(long idGrupo, long idAdultoMayor, long idUsuarioSaliente, long idUsuarioEntrante,
            String notas, long idUsuarioCreador, long idEstado, OffsetDateTime ahora) {
        this.idGrupo = idGrupo;
        this.idAdultoMayor = idAdultoMayor;
        this.idUsuarioSaliente = idUsuarioSaliente;
        this.idUsuarioEntrante = idUsuarioEntrante;
        this.notas = notas;
        this.idUsuarioCreador = idUsuarioCreador;
        this.fechaTraspaso = ahora;
        this.fechaCreacion = ahora;
        this.idEstado = idEstado;
    }

    /**
     * Relevo nuevo, siempre {@code COMPLETADA} con {@code FECHA_TRASPASO =
     * FECHA_CREACION = ahora} (regla 3 de la Fase 7). {@code idUsuarioCreador}
     * es el actor que registra, no necesariamente el saliente ni el
     * entrante (un coordinador o un administrador puede registrar el relevo
     * de otros dos integrantes).
     */
    public static TraspasoFamiliar nuevo(long idGrupo, long idAdultoMayor, long idUsuarioSaliente,
            long idUsuarioEntrante, String notas, long idUsuarioCreador, long idEstadoCompletada,
            OffsetDateTime ahora) {
        return new TraspasoFamiliar(idGrupo, idAdultoMayor, idUsuarioSaliente, idUsuarioEntrante, notas,
                idUsuarioCreador, idEstadoCompletada, ahora);
    }

    /** Cancela el relevo ({@code COMPLETADA} → {@code CANCELADA}); la foto ({@code DetalleTraspaso}) no se toca. */
    public void cancelar(long idEstadoCancelada) {
        this.idEstado = idEstadoCancelada;
    }
}
