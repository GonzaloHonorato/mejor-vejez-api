package cl.adndigital.mejorvejez.comun;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador que SOLO existe para {@code ManejadorErroresTest}: expone un
 * endpoint por cada camino que debe cubrir {@link ManejadorErrores}, sin
 * depender de ningún controlador de negocio real.
 */
@RestController
@RequestMapping("/api/prueba-errores")
public class ControladorPruebaErrores {

    @GetMapping("/no-encontrado")
    public void noEncontrado() {
        throw new ExcepcionNoEncontrado("El recurso solicitado no existe.");
    }

    @GetMapping("/regla-negocio")
    public void reglaNegocio() {
        throw new ExcepcionReglaNegocio("La operación viola una regla de negocio.");
    }

    @GetMapping("/conflicto")
    public void conflicto() {
        throw new ExcepcionConflicto("El recurso ya existe.");
    }

    @GetMapping("/acceso-denegado")
    public void accesoDenegado() {
        throw new ExcepcionAccesoDenegado("No tiene permisos para esta operación.");
    }

    @PostMapping("/validacion")
    public void validacion(@Valid @RequestBody SolicitudPrueba solicitud) {
        // No hace nada: @Valid dispara MethodArgumentNotValidException antes
        // de que el cuerpo del método se ejecute.
    }

    @GetMapping("/parametro")
    public String parametro(@RequestParam String valor) {
        return valor;
    }

    @GetMapping("/integridad")
    public void integridad() {
        throw new DataIntegrityViolationException(
                "ORA-00001: unique CONSTRAINT (ESQUEMA.PK_TABLA_X) violated");
    }

    @GetMapping("/error-no-controlado")
    public void errorNoControlado() {
        throw new RuntimeException("ORA-00001 en TABLA_X");
    }

    /** Cuerpo de prueba con una sola validación, suficiente para probar el mapeo de errores por campo. */
    public record SolicitudPrueba(@NotBlank(message = "El nombre es obligatorio.") String nombre) {
    }
}
