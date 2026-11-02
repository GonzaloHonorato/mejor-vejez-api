package cl.adndigital.mejorvejez.usuarios;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.comun.TelefonoChileno;

/**
 * Actualiza el perfil (nombre y teléfono) del usuario autenticado
 * ({@code PATCH /api/yo}). El correo no se edita acá: viene de Firebase.
 */
@Service
public class ServicioPerfil {

    private static final String MODULO = "usuarios";
    private static final String TIPO_ENTIDAD = "USUARIO";
    private static final String ACCION_ACTUALIZAR_PERFIL = "ACTUALIZAR_PERFIL";

    private final RepositorioUsuario repositorio;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioPerfil(RepositorioUsuario repositorio, ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Actualiza el perfil de {@code usuario} y audita los campos cambiados.
     * Si, tras normalizar, ni el nombre ni el teléfono cambian, no guarda ni
     * audita nada (evita ruido de auditoría en envíos sin cambios reales).
     * El teléfono sigue {@link TelefonoChileno#aGuardar}: si es el mismo ya
     * guardado no se valida.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionCampoInvalido el teléfono cambió y no es chileno.
     */
    @Transactional
    public Usuario actualizar(Usuario usuario, String nombre, String telefono) {
        String nombreAnterior = usuario.getNombre();
        String telefonoAnterior = usuario.getTelefono();

        String nombreNormalizado = nombre.trim();
        String telefonoNormalizado = TelefonoChileno.aGuardar(telefono, telefonoAnterior);
        if (Objects.equals(nombreAnterior, nombreNormalizado) && Objects.equals(telefonoAnterior, telefonoNormalizado)) {
            return usuario;
        }

        usuario.actualizarPerfil(nombre, telefonoNormalizado, OffsetDateTime.now(reloj));
        Usuario guardado = repositorio.save(usuario);

        List<String> camposCambiados = new ArrayList<>();
        if (!Objects.equals(nombreAnterior, guardado.getNombre())) {
            camposCambiados.add("nombre");
        }
        if (!Objects.equals(telefonoAnterior, guardado.getTelefono())) {
            camposCambiados.add("telefono");
        }

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdUsuario()), null, guardado.getIdUsuario(),
                ACCION_ACTUALIZAR_PERFIL, ResultadoAuditoria.EXITO, Map.of("campos", camposCambiados)));

        return guardado;
    }
}
