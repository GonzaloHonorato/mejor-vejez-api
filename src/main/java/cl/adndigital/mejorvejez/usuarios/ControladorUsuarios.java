package cl.adndigital.mejorvejez.usuarios;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.cuentas.CuentaCreada;
import cl.adndigital.mejorvejez.cuentas.ServicioAltaCuentas;

/**
 * Administración global de cuentas: crear, listar, ver, editar, cambiar el
 * estado y reenviar el acceso de cualquier usuario. La autorización (solo
 * administradores) la exige {@link ServicioAdministracionUsuarios} o
 * {@link ServicioAltaCuentas}, según la operación, en cada caso.
 *
 * <p>{@code @Validated} habilita la validación de {@code pagina}/{@code
 * tamano} como parámetros de método ({@code @Min}, con {@code message} en
 * español); una violación lanza {@code ConstraintViolationException}, que
 * {@code ManejadorErrores} traduce a 400 "Datos inválidos".
 */
@RestController
@RequestMapping("/api/usuarios")
@Validated
public class ControladorUsuarios {

    private static final int TAMANO_MAXIMO_PAGINA = 100;

    private final ServicioAdministracionUsuarios servicio;
    private final ServicioAltaCuentas servicioAltaCuentas;

    public ControladorUsuarios(ServicioAdministracionUsuarios servicio, ServicioAltaCuentas servicioAltaCuentas) {
        this.servicio = servicio;
        this.servicioAltaCuentas = servicioAltaCuentas;
    }

    /**
     * Crea una cuenta nueva (Firebase + {@code USUARIO}): la autorización
     * (solo administradores) y el resto del alta los exige {@link
     * ServicioAltaCuentas}.
     */
    @PostMapping
    public ResponseEntity<RespuestaCuentaCreada> crear(@Valid @RequestBody SolicitudCrearUsuario solicitud) {
        CuentaCreada cuentaCreada = servicioAltaCuentas.crearCuenta(solicitud.nombre(), solicitud.email(),
                solicitud.telefono());
        RespuestaCuentaCreada respuesta = RespuestaCuentaCreada.desde(cuentaCreada);
        return ResponseEntity.created(URI.create("/api/usuarios/" + respuesta.usuario().idUsuario())).body(respuesta);
    }

    /**
     * Fija una contraseña temporal nueva para {@code idUsuario} y pide a
     * Alertas que la reenvíe por correo.
     */
    @PostMapping("/{idUsuario}/reenviar-acceso")
    public RespuestaAccesoReenviado reenviarAcceso(@PathVariable long idUsuario) {
        return RespuestaAccesoReenviado.desde(servicioAltaCuentas.reenviarAcceso(idUsuario));
    }

    @GetMapping
    public RespuestaPagina<RespuestaUsuario> listar(
            @RequestParam(required = false) String texto,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa.") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "El tamaño debe ser al menos 1.") int tamano) {
        int tamanoLimitado = Math.min(tamano, TAMANO_MAXIMO_PAGINA);
        Page<Usuario> paginaResultado = servicio.listar(texto, pagina, tamanoLimitado);
        List<RespuestaUsuario> elementos = paginaResultado.getContent().stream().map(RespuestaUsuario::desde).toList();
        return new RespuestaPagina<>(elementos, pagina, tamanoLimitado, paginaResultado.getTotalElements());
    }

    @GetMapping("/{idUsuario}")
    public RespuestaUsuario obtener(@PathVariable long idUsuario) {
        return RespuestaUsuario.desde(servicio.obtener(idUsuario));
    }

    @PatchMapping("/{idUsuario}")
    public RespuestaUsuario actualizar(@PathVariable long idUsuario,
            @Valid @RequestBody SolicitudActualizarUsuario solicitud) {
        Usuario actualizado = servicio.actualizar(idUsuario, solicitud.nombre(), solicitud.telefono(),
                solicitud.esAdministrador());
        return RespuestaUsuario.desde(actualizado);
    }

    @PutMapping("/{idUsuario}/estado")
    public RespuestaUsuario cambiarEstado(@PathVariable long idUsuario,
            @Valid @RequestBody SolicitudCambiarEstado solicitud) {
        Usuario actualizado = servicio.cambiarEstado(idUsuario, solicitud.idEstado());
        return RespuestaUsuario.desde(actualizado);
    }
}
