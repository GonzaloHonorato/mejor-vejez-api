package cl.adndigital.mejorvejez.grupos;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone el catálogo de roles de grupo (COORDINADOR / COLABORADOR). Exige
 * token, como todo {@code /api} salvo la salud (ver {@code
 * ConfiguracionSeguridad}).
 */
@RestController
@RequestMapping("/api/grupos")
public class ControladorRoles {

    private final RepositorioRolGrupo repositorioRolGrupo;

    public ControladorRoles(RepositorioRolGrupo repositorioRolGrupo) {
        this.repositorioRolGrupo = repositorioRolGrupo;
    }

    @GetMapping("/roles")
    public List<RespuestaRol> roles() {
        return repositorioRolGrupo.findAllByOrderByIdRolAsc().stream()
                .map(rol -> new RespuestaRol(rol.getIdRol(), rol.getNombreRol(), rol.getDescripcion()))
                .toList();
    }
}
