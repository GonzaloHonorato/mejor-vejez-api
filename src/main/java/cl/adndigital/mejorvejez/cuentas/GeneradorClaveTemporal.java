package cl.adndigital.mejorvejez.cuentas;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Genera la contraseña temporal que recibe una cuenta nueva (o que vuelve a
 * recibir al reenviar el acceso): 12 caracteres de un alfabeto sin
 * ambigüedades visuales ({@code 0}/{@code O}, {@code 1}/{@code l}/{@code I}),
 * con al menos una mayúscula, una minúscula y un dígito.
 */
@Component
public class GeneradorClaveTemporal {

    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int LARGO = 12;

    private final SecureRandom aleatorio = new SecureRandom();

    /** Una contraseña temporal nueva de 12 caracteres que cumple los requisitos del alfabeto. */
    public String generar() {
        String candidata;
        do {
            candidata = generarCandidata();
        } while (!cumpleRequisitos(candidata));
        return candidata;
    }

    private String generarCandidata() {
        StringBuilder constructor = new StringBuilder(LARGO);
        for (int i = 0; i < LARGO; i++) {
            constructor.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
        }
        return constructor.toString();
    }

    private boolean cumpleRequisitos(String candidata) {
        boolean tieneMayuscula = candidata.chars().anyMatch(Character::isUpperCase);
        boolean tieneMinuscula = candidata.chars().anyMatch(Character::isLowerCase);
        boolean tieneDigito = candidata.chars().anyMatch(Character::isDigit);
        return tieneMayuscula && tieneMinuscula && tieneDigito;
    }
}
