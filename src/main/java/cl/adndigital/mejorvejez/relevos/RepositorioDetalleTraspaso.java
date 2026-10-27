package cl.adndigital.mejorvejez.relevos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioDetalleTraspaso extends JpaRepository<DetalleTraspaso, Long> {

    /**
     * Detalles del relevo, en el orden en que se guardaron (tareas antes
     * que alertas, regla 4 de la Fase 7). El listado paginado ({@code
     * ServicioRelevos#listar}) no usa este método para contar: cuenta con
     * {@code RepositorioConsultaRelevos#conteosDeDetallePorTraspaso}
     * (ronda de corrección 1, minor 5), sin cargar las filas completas.
     */
    List<DetalleTraspaso> findByIdTraspasoOrderByIdDetalleTraspaso(long idTraspaso);
}
