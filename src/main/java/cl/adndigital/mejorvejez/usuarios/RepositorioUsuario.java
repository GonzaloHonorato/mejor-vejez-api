package cl.adndigital.mejorvejez.usuarios;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioUsuario extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUidFirebase(String uidFirebase);

    Optional<Usuario> findByEmail(String email);
}
