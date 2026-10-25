package cl.adndigital.mejorvejez.adultos;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioContactoEmergencia extends JpaRepository<ContactoEmergencia, Long> {

    /** Contactos del adulto mayor indicado, en cualquier estado. */
    List<ContactoEmergencia> findByIdAdultoMayor(long idAdultoMayor);

    /**
     * Contactos principales ACTIVOS de los adultos indicados (a lo más uno
     * por adulto, por {@code UX_CONTACTO_PRINCIPAL_ACTIVO}).
     *
     * <p>La palabra clave derivada {@code True} no combina con {@code
     * esPrincipal} porque el atributo está convertido ({@code
     * ConvertidorSiNo} lo mapea a {@code CHAR(1)} 'S'/'N', no a un booleano
     * nativo): se implementa como un método por defecto que delega en
     * {@link #buscarPrincipalesActivos} con un parámetro {@code boolean}
     * ligado de verdad, para que Hibernate aplique el conversor también al
     * valor de comparación.
     */
    default List<ContactoEmergencia> findByIdAdultoMayorInAndEsPrincipalTrueAndIdEstado(Collection<Long> ids,
            long idEstadoActivo) {
        return buscarPrincipalesActivos(ids, true, idEstadoActivo);
    }

    @Query("SELECT c FROM ContactoEmergencia c "
            + "WHERE c.idAdultoMayor IN :ids AND c.esPrincipal = :esPrincipal AND c.idEstado = :idEstadoActivo")
    List<ContactoEmergencia> buscarPrincipalesActivos(@Param("ids") Collection<Long> ids,
            @Param("esPrincipal") boolean esPrincipal, @Param("idEstadoActivo") long idEstadoActivo);

    /** Cantidad de contactos (en cualquier estado) del adulto mayor indicado. */
    long countByIdAdultoMayor(long idAdultoMayor);
}
