package com.soyjuanpiece.roleplaycore.license;

/**
 * Tipos de licencias que los jugadores pueden obtener.
 */
public enum LicenseType {

    CONDUCIR("Licencia de Conducir", "roleplaycore.license.conducir", 500.0, "Permite conducir vehículos en la ciudad"),
    ARMA_CORTA("Licencia Arma Corta", "roleplaycore.license.arma_corta", 800.0, "Permite portar pistolas"),
    ARMA_LARGA("Licencia Arma Larga", "roleplaycore.license.arma_larga", 1500.0, "Permite portar rifles y escopetas"),
    MEDICA("Licencia Médica", "roleplaycore.license.medica", 2000.0, "Permite ejercer medicina en el servidor"),
    ABOGACIA("Licencia de Abogacía", "roleplaycore.license.abogacia", 2500.0, "Permite ejercer como abogado en el tribunal"),
    PILOTO("Licencia de Piloto", "roleplaycore.license.piloto", 3000.0, "Permite pilotar aeronaves"),
    PESCA("Licencia de Pesca", "roleplaycore.license.pesca", 200.0, "Permite pesca comercial en aguas del servidor"),
    COMERCIO("Licencia Comercial", "roleplaycore.license.comercio", 1000.0, "Permite abrir negocios legales"),
    EXPLOSIVOS("Licencia de Explosivos", "roleplaycore.license.explosivos", 5000.0, "Permite manipular explosivos (solo mineros certificados)");

    private final String nombre;
    private final String permiso;
    private final double costo;
    private final String descripcion;

    LicenseType(String nombre, String permiso, double costo, String descripcion) {
        this.nombre      = nombre;
        this.permiso     = permiso;
        this.costo       = costo;
        this.descripcion = descripcion;
    }

    public String getNombre()      { return nombre; }
    public String getPermiso()     { return permiso; }
    public double getCosto()       { return costo; }
    public String getDescripcion() { return descripcion; }
}
