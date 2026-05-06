package com.soyjuanpiece.roleplaycore.housing;

/**
 * Tipos de propiedades disponibles en el mercado inmobiliario.
 */
public enum PropertyType {

    APARTAMENTO("Apartamento", 15_000.0, 500.0, 2),
    CASA("Casa", 50_000.0, 1_200.0, 4),
    MANSION("Mansión", 250_000.0, 5_000.0, 10),
    LOCAL_COMERCIAL("Local Comercial", 80_000.0, 2_000.0, 3),
    ALMACEN("Almacén", 120_000.0, 3_000.0, 1),
    TERRENO("Terreno", 30_000.0, 800.0, 0),
    PENTHOUSE("Penthouse", 400_000.0, 8_000.0, 6);

    /** Nombre legible */
    private final String nombre;

    /** Precio base de compra */
    private final double precioCompra;

    /** Alquiler mensual base (por ciclo Payday) */
    private final double alquilerBase;

    /** Número máximo de cofres de almacenamiento */
    private final int maxCofres;

    PropertyType(String nombre, double precioCompra, double alquilerBase, int maxCofres) {
        this.nombre       = nombre;
        this.precioCompra = precioCompra;
        this.alquilerBase = alquilerBase;
        this.maxCofres    = maxCofres;
    }

    public String getNombre()      { return nombre; }
    public double getPrecioCompra(){ return precioCompra; }
    public double getAlquilerBase(){ return alquilerBase; }
    public int getMaxCofres()      { return maxCofres; }
}
