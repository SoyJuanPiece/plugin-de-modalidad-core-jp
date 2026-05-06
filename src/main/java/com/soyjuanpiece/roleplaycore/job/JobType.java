package com.soyjuanpiece.roleplaycore.job;

/**
 * Enumeración de todos los trabajos disponibles en el sistema RoleplayCore.
 */
public enum JobType {

    // Sector público
    POLICIA("Policía", 1200.0, "roleplaycore.job.policia", false, 20),
    MEDICO("Médico", 1500.0, "roleplaycore.job.medico", false, 10),
    BOMBERO("Bombero", 1100.0, "roleplaycore.job.bombero", false, 10),
    JUEZ("Juez", 2500.0, "roleplaycore.job.juez", false, 3),
    FUNCIONARIO("Funcionario", 900.0, "roleplaycore.job.funcionario", false, 15),

    // Sector privado legal
    ABOGADO("Abogado", 2000.0, "roleplaycore.job.abogado", false, 8),
    BANQUERO("Banquero", 1800.0, "roleplaycore.job.banquero", false, 6),
    MECANICO("Mecánico", 900.0, "roleplaycore.job.mecanico", false, 12),
    TAXISTA("Taxista", 700.0, "roleplaycore.job.taxista", false, 15),
    PERIODISTA("Periodista", 700.0, "roleplaycore.job.periodista", false, 10),
    AGRICULTOR("Agricultor", 600.0, "roleplaycore.job.agricultor", false, 0),
    EMPRESARIO("Empresario", 2000.0, "roleplaycore.job.empresario", false, 0),
    GUARDIA_SEGURIDAD("Guardia de Seguridad", 800.0, "roleplaycore.job.guardia", false, 20),
    CHEF("Chef", 850.0, "roleplaycore.job.chef", false, 10),
    MINERO("Minero", 650.0, "roleplaycore.job.minero", false, 0),
    CONSTRUCTOR("Constructor", 950.0, "roleplaycore.job.constructor", false, 0),
    VETERINARIO("Veterinario", 1000.0, "roleplaycore.job.veterinario", false, 6),

    // Ilegales
    TRAFICANTE("Traficante", 3000.0, "roleplaycore.job.traficante", true, 0),
    SICARIO("Sicario", 4000.0, "roleplaycore.job.sicario", true, 0),
    HACKER("Hacker", 2500.0, "roleplaycore.job.hacker", true, 0),

    // Sin empleo
    DESEMPLEADO("Desempleado", 150.0, null, false, 0);

    private final String nombre;
    private final double salarioBase;
    private final String permiso;
    private final boolean esIlegal;
    private final int plazasMaximas;

    JobType(String nombre, double salarioBase, String permiso, boolean esIlegal, int plazasMaximas) {
        this.nombre        = nombre;
        this.salarioBase   = salarioBase;
        this.permiso       = permiso;
        this.esIlegal      = esIlegal;
        this.plazasMaximas = plazasMaximas;
    }

    public String getNombre()      { return nombre; }
    public double getSalarioBase() { return salarioBase; }
    public String getPermiso()     { return permiso; }
    public boolean esIlegal()      { return esIlegal; }
    public int getPlazasMaximas()  { return plazasMaximas; }

    public boolean esSectorPublico() {
        return this == POLICIA || this == MEDICO || this == BOMBERO
                || this == JUEZ || this == FUNCIONARIO;
    }

    public String getNombreColoreado() {
        return esIlegal ? "§c" + nombre : "§a" + nombre;
    }
}
