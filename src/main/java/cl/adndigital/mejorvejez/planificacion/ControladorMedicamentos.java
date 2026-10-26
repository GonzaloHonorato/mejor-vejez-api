package cl.adndigital.mejorvejez.planificacion;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo global de medicamentos (HU-04). La autorización la exige
 * {@link ServicioMedicamentos} en cada operación.
 *
 * <p>Contrato de respuesta: {@code POST .../medicamentos} devuelve 201 con
 * {@link RespuestaMedicamento} si el medicamento es nuevo, o 200 con el
 * existente si ya había uno con el mismo {@code (nombre, idPresentacion,
 * concentracion)} normalizado. Sin {@code Location}: no existe un
 * {@code GET} de un medicamento individual.
 */
@RestController
@RequestMapping("/api/grupos/{idGrupo}/medicamentos")
public class ControladorMedicamentos {

    private final ServicioMedicamentos servicio;

    public ControladorMedicamentos(ServicioMedicamentos servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<RespuestaMedicamento> buscar(@PathVariable long idGrupo,
            @RequestParam(required = false) String texto) {
        return servicio.buscar(idGrupo, texto);
    }

    @PostMapping
    public ResponseEntity<RespuestaMedicamento> crear(@PathVariable long idGrupo,
            @Valid @RequestBody SolicitudCrearMedicamento solicitud) {
        ServicioMedicamentos.ResultadoMedicamento resultado = servicio.crear(idGrupo, solicitud);
        if (!resultado.creado()) {
            return ResponseEntity.ok(resultado.medicamento());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(resultado.medicamento());
    }
}
