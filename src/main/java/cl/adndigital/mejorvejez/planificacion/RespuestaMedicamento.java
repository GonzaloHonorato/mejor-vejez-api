package cl.adndigital.mejorvejez.planificacion;

/**
 * Datos de un medicamento del catálogo global para quien lo consulta o lo
 * da de alta ({@code GET}/{@code POST .../medicamentos}).
 *
 * @param usadoEnGrupo si existe un {@code DETALLE_PLAN} de un {@code
 *                     PLAN_MEDICAMENTOS} del grupo de la URL que use este
 *                     medicamento.
 */
public record RespuestaMedicamento(long idMedicamento, String nombre, String principioActivo, Long idPresentacion,
        String concentracion, boolean usadoEnGrupo) {

    public static RespuestaMedicamento desde(Medicamento medicamento, boolean usadoEnGrupo) {
        return new RespuestaMedicamento(medicamento.getIdMedicamento(), medicamento.getNombre(),
                medicamento.getPrincipioActivo(), medicamento.getIdPresentacion(), medicamento.getConcentracion(),
                usadoEnGrupo);
    }
}
