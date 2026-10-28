package cl.adndigital.mejorvejez.interno;

/**
 * Un adulto mayor del grupo con al menos una tarea en el período (Fase 9,
 * TSA-61): parte de {@link DatosSemana#adultos}. Los adultos sin ninguna
 * tarea en la semana no aparecen.
 */
public record AdultoSemana(long idAdulto, String nombre) {
}
