package cl.adndigital.mejorvejez.usuarios;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioUsuario extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUidFirebase(String uidFirebase);

    Optional<Usuario> findByEmail(String email);

    /**
     * Usuarios cuyo nombre o email contienen {@code texto} (sin distinguir
     * mayúsculas), ordenados por nombre con desempate por {@code idUsuario}
     * (paginación estable entre páginas cuando hay nombres repetidos);
     * {@code texto} nulo no filtra (administración global de cuentas,
     * {@code GET /api/usuarios}). {@code %} y {@code _} de {@code texto} se
     * escapan antes de armar el patrón, para que se busquen como texto
     * literal y no como comodines del LIKE.
     */
    default Page<Usuario> buscarPorTexto(String texto, Pageable pageable) {
        return buscarPorTextoEscapado(escaparComodinesLike(texto), pageable);
    }

    @Query("SELECT u FROM Usuario u "
            + "WHERE :texto IS NULL OR LOWER(u.nombre) LIKE LOWER(CONCAT('%', :texto, '%')) ESCAPE '\\' "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :texto, '%')) ESCAPE '\\' "
            + "ORDER BY u.nombre, u.idUsuario")
    Page<Usuario> buscarPorTextoEscapado(@Param("texto") String texto, Pageable pageable);

    /** Escapa {@code \}, {@code %} y {@code _} para usar {@code texto} como literal en un LIKE con {@code ESCAPE '\'}. */
    private static String escaparComodinesLike(String texto) {
        return texto == null ? null : texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Administradores en {@code idEstado}, bloqueados con {@code SELECT
     * ... FOR UPDATE} (mismo patrón que {@code
     * RepositorioGrupoFamiliar#bloquearPorId}). Evita la carrera por la
     * que dos administradores que se quitan el rol (o se dan de baja) casi
     * al mismo tiempo podrían pasar ambos el chequeo de "al menos un
     * administrador activo" y dejar el sistema sin ninguno: la segunda
     * transacción que llega bloquea hasta que la primera confirma, y al
     * reanudar vuelve a evaluar la condición con los datos ya confirmados.
     * Debe llamarse dentro de una transacción; el bloqueo dura hasta que
     * esta termina.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM Usuario u WHERE u.esAdministrador = true AND u.idEstado = :idEstado")
    List<Usuario> bloquearAdministradoresEn(@Param("idEstado") long idEstado);
}
