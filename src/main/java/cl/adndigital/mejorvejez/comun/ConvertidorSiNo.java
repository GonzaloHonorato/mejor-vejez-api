package cl.adndigital.mejorvejez.comun;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Convierte el patrón {@code CHAR(1)} {@code 'S'}/{@code 'N'} que usa el
 * esquema Oracle para columnas booleanas a {@link Boolean} y viceversa.
 * Úsalo con {@code @Convert(converter = ConvertidorSiNo.class)} en cualquier
 * columna que siga ese patrón (p. ej. {@code ACTIVO}).
 */
@Converter
public class ConvertidorSiNo implements AttributeConverter<Boolean, String> {

    private static final String SI = "S";
    private static final String NO = "N";

    @Override
    public String convertToDatabaseColumn(Boolean atributo) {
        if (atributo == null) {
            return null;
        }
        return atributo ? SI : NO;
    }

    @Override
    public Boolean convertToEntityAttribute(String columna) {
        if (columna == null) {
            return null;
        }
        return SI.equals(columna);
    }
}
