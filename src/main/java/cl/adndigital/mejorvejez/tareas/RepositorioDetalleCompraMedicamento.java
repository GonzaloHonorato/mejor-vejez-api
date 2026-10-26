package cl.adndigital.mejorvejez.tareas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioDetalleCompraMedicamento extends JpaRepository<DetalleCompraMedicamento, Long> {

    /** Líneas de compra de cualquiera de las tareas dadas, en una sola consulta (sin N+1), por id de inserción. */
    List<DetalleCompraMedicamento> findByIdTareaInOrderByIdDetalleCompra(Collection<Long> idsTarea);
}
