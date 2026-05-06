package com.soyjuanpiece.roleplaycore.crime;

/**
 * Enumeración de los tipos de crimen posibles en el sistema RoleplayCore.
 * <p>
 * Cada tipo de crimen lleva asociado un nombre legible, el nivel de búsqueda
 * inicial que genera y la multa base aplicada al ser capturado.
 * </p>
 */
public enum CrimeType {

    /** Robo a otro jugador (carterismo, hurto de ítems) */
    ROBO("Robo", 2, 500.0),

    /** Asesinato de un jugador civil */
    ASESINATO("Asesinato", 4, 2000.0),

    /** Agresión física sin causar la muerte */
    AGRESION("Agresión", 1, 200.0),

    /** Tráfico de sustancias ilegales */
    TRAFICO("Tráfico de sustancias", 3, 1500.0),

    /** Evasión de la justicia (huir de un oficial) */
    EVASION("Evasión policial", 2, 300.0),

    /** Sabotaje de infraestructura de la ciudad */
    SABOTAJE("Sabotaje", 3, 1000.0),

    /** Hackeo de sistemas electrónicos sin autorización */
    HACKEO("Hackeo ilegal", 2, 700.0),

    /** Lavado de activos descubierto por las autoridades */
    LAVADO("Lavado de activos", 3, 1200.0);

    /** Nombre legible del crimen (para mostrar en pantalla) */
    private final String nombre;

    /** Nivel de búsqueda (Wanted) inicial que genera este crimen */
    private final int nivelWantedInicial;

    /** Multa base en unidades monetarias del juego */
    private final double multaBase;

    CrimeType(String nombre, int nivelWantedInicial, double multaBase) {
        this.nombre = nombre;
        this.nivelWantedInicial = nivelWantedInicial;
        this.multaBase = multaBase;
    }

    /**
     * Retorna el nombre legible del crimen.
     *
     * @return nombre del crimen en español
     */
    public String getNombre() {
        return nombre;
    }

    /**
     * Retorna el nivel Wanted inicial que genera este tipo de crimen.
     *
     * @return nivel de búsqueda entre 1 y 5
     */
    public int getNivelWantedInicial() {
        return nivelWantedInicial;
    }

    /**
     * Retorna la multa base asociada a este crimen.
     *
     * @return monto de la multa en unidades monetarias
     */
    public double getMultaBase() {
        return multaBase;
    }
}
