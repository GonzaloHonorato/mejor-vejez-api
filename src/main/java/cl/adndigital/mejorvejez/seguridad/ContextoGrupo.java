package cl.adndigital.mejorvejez.seguridad;

import cl.adndigital.mejorvejez.grupos.GrupoFamiliar;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Resultado de una autorización por grupo ({@link ServicioAutorizacion}).
 *
 * @param usuario         usuario autenticado.
 * @param grupo           grupo al que se autorizó el acceso.
 * @param rol             rol de su membresía ACTIVA ({@code "COORDINADOR"} |
 *                        {@code "COLABORADOR"}), o {@code null} si es un
 *                        administrador sin membresía activa en el grupo.
 * @param esAdministrador si el usuario es administrador global.
 */
public record ContextoGrupo(Usuario usuario, GrupoFamiliar grupo, String rol, boolean esAdministrador) {
}
