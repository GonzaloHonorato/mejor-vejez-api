package cl.adndigital.mejorvejez.tareas;

import java.nio.charset.StandardCharsets;

/**
 * Trunca texto para columnas {@code VARCHAR2(n)} del esquema, que usan
 * semántica BYTE (la de Oracle por defecto): {@code n} es un máximo de bytes
 * en la codificación de la base (AL32UTF8), no de caracteres. Un texto con
 * tildes puede tener menos de {@code n} caracteres y aun así no caber. El
 * corte nunca parte un carácter multibyte ni un par sustituto: se descarta
 * entero el último punto de código que no cabe. Como cada carácter ocupa al
 * menos un byte, el resultado tiene también a lo más {@code n} caracteres.
 */
final class TextoAcotado {

    private TextoAcotado() {
    }

    /** {@code texto} si cabe en {@code maximoBytes} bytes UTF-8; si no, su prefijo más largo que cabe. */
    static String truncarABytes(String texto, int maximoBytes) {
        if (texto == null || texto.length() * 3L <= maximoBytes
                || texto.getBytes(StandardCharsets.UTF_8).length <= maximoBytes) {
            return texto;
        }
        int bytes = 0;
        int fin = 0;
        while (fin < texto.length()) {
            int puntoCodigo = texto.codePointAt(fin);
            int largo = bytesUtf8(puntoCodigo);
            if (bytes + largo > maximoBytes) {
                break;
            }
            bytes += largo;
            fin += Character.charCount(puntoCodigo);
        }
        return texto.substring(0, fin);
    }

    private static int bytesUtf8(int puntoCodigo) {
        if (puntoCodigo < 0x80) {
            return 1;
        }
        if (puntoCodigo < 0x800) {
            return 2;
        }
        if (puntoCodigo < 0x10000) {
            return 3;
        }
        return 4;
    }
}
