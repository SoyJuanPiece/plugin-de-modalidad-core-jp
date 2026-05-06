package com.soyjuanpiece.roleplaycore.town;

/**
 * Enumeración de los flags de configuración de una ciudad (CityFlags).
 * <p>
 * El Alcalde puede modificar estos flags mediante {@code TownManager#setCityFlag()}.
 * Cada flag controla un aspecto del comportamiento de la ciudad.
 * </p>
 */
public enum CityFlag {

    /** Permite combate JcJ (PvP) dentro de los límites de la ciudad */
    PVP_PERMITIDO("PvP Permitido", false),

    /** Permite el robo entre jugadores dentro de la ciudad */
    ROBO_PERMITIDO("Robo Permitido", false),

    /** Aplica un impuesto adicional sobre las transacciones comerciales */
    IMPUESTO_COMERCIO("Impuesto sobre Comercio", true),

    /** Permite el uso de armas de fuego (si el plugin las tiene) */
    ARMAS_PERMITIDAS("Armas de Fuego Permitidas", false),

    /** Activa el toque de queda nocturno (restricción de movimiento) */
    TOQUE_DE_QUEDA("Toque de Queda Nocturno", false),

    /** Permite que los NPCs guardianes patrullen activamente */
    GUARDIANES_ACTIVOS("Guardianes NPC Activos", true),

    /** Activa el mercado negro (Dealer NPC aparece de noche) */
    MERCADO_NEGRO("Mercado Negro Activo", false);

    /** Nombre legible del flag para mostrar en la UI */
    private final String nombre;

    /** Valor por defecto del flag al crear una nueva ciudad */
    private final boolean valorPorDefecto;

    CityFlag(String nombre, boolean valorPorDefecto) {
        this.nombre = nombre;
        this.valorPorDefecto = valorPorDefecto;
    }

    /**
     * Retorna el nombre legible del flag.
     *
     * @return nombre del flag en español
     */
    public String getNombre() {
        return nombre;
    }

    /**
     * Retorna el valor por defecto de este flag al crear una ciudad nueva.
     *
     * @return valor boolean por defecto
     */
    public boolean getValorPorDefecto() {
        return valorPorDefecto;
    }
}
