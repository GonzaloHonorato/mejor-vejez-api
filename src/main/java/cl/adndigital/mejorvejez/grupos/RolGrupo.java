package cl.adndigital.mejorvejez.grupos;

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
 * Rol dentro de un grupo ({@code ROL_GRUPO}): solo {@code COORDINADOR} y
 * {@code COLABORADOR} (CHECK {@code CK_ROL_GRUPO_NOMBRE}); los siembra
 * {@code V2__catalogos.sql}. El administrador es global ({@code
 * USUARIO.ES_ADMINISTRADOR}), no un rol de grupo (INC-02).
 */
@Entity
@Table(name = "ROL_GRUPO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolGrupo {

    public static final String COORDINADOR = "COORDINADOR";
    public static final String COLABORADOR = "COLABORADOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID_ROL")
    private Long idRol;

    @Column(name = "NOMBRE_ROL", nullable = false, length = 30)
    private String nombreRol;

    @Column(name = "DESCRIPCION", length = 200)
    private String descripcion;
}
