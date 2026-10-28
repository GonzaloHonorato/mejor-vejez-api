package cl.adndigital.mejorvejez.interno;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioEnvioReporteDestinatario extends JpaRepository<EnvioReporteDestinatario, Long> {

    /** La foto de destinatarios del envío indicado, por id de usuario ascendente. */
    List<EnvioReporteDestinatario> findByIdEnvioOrderByIdUsuario(long idEnvio);
}
