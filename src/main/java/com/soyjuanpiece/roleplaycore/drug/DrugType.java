package com.soyjuanpiece.roleplaycore.drug;

/**
 * Tipos de sustancias ilegales del sistema de narcotráfico.
 */
public enum DrugType {

    MARIHUANA("Marihuana", 50.0, 150.0, 300.0, 1),
    COCAINA("Cocaína", 200.0, 600.0, 1200.0, 3),
    HEROINA("Heroína", 500.0, 1500.0, 3000.0, 4),
    METANFETAMINA("Metanfetamina", 300.0, 900.0, 1800.0, 3),
    PASTILLAS("Pastillas Sintéticas", 100.0, 300.0, 600.0, 2),
    HONGOS("Hongos Psicoactivos", 80.0, 240.0, 480.0, 1);

    /** Nombre de la sustancia */
    private final String nombre;

    /** Costo de producción por unidad */
    private final double costoPorUnidad;

    /** Precio de venta callejero por unidad */
    private final double precioCallejero;

    /** Precio de venta máximo (dealer exclusivo) */
    private final double precioMaximo;

    /** Nivel de búsqueda Wanted que genera si te pillan */
    private final int wantedAlPillar;

    DrugType(String nombre, double costoPorUnidad, double precioCallejero,
             double precioMaximo, int wantedAlPillar) {
        this.nombre          = nombre;
        this.costoPorUnidad  = costoPorUnidad;
        this.precioCallejero = precioCallejero;
        this.precioMaximo    = precioMaximo;
        this.wantedAlPillar  = wantedAlPillar;
    }

    public String getNombre()          { return nombre; }
    public double getCostoPorUnidad()  { return costoPorUnidad; }
    public double getPrecioCallejero() { return precioCallejero; }
    public double getPrecioMaximo()    { return precioMaximo; }
    public int getWantedAlPillar()     { return wantedAlPillar; }
}
