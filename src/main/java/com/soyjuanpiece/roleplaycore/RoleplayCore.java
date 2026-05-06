package com.soyjuanpiece.roleplaycore;

import com.soyjuanpiece.roleplaycore.crime.CrimeManager;
import com.soyjuanpiece.roleplaycore.crime.CrimeSystem;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.economy.EconomyManager;
import com.soyjuanpiece.roleplaycore.player.PlayerProfile;
import com.soyjuanpiece.roleplaycore.town.TownManager;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

/**
 * Clase principal del plugin RoleplayCore.
 * <p>
 * Punto de entrada del motor de roleplay. Se encarga de:
 * <ul>
 *   <li>Configurar todos los servicios mediante inyección de dependencias ({@link #setupServices()}).</li>
 *   <li>Inicializar la base de datos y los cachés.</li>
 *   <li>Programar tareas recurrentes (Payday, limpieza de caché).</li>
 *   <li>Registrar listeners y comandos.</li>
 * </ul>
 * </p>
 */
public class RoleplayCore extends JavaPlugin {

    // -----------------------------------------------------------------------
    // Instancia singleton (acceso global controlado)
    // -----------------------------------------------------------------------

    /** Instancia singleton del plugin principal */
    private static RoleplayCore instancia;

    // -----------------------------------------------------------------------
    // Servicios inyectados por setupServices()
    // -----------------------------------------------------------------------

    /** Conector de base de datos con pool HikariCP */
    private DatabaseConnector conectorDB;

    /** Gestor de economía (saldos, transferencias, impuestos) */
    private EconomyManager gestorEconomia;

    /** Gestor de crímenes (niveles Wanted, multas, arrestos) */
    private CrimeManager gestorCriminal;

    /** Sistema forense (evidencias de ADN, scanner) */
    private CrimeSystem sistemaCriminal;

    /** Gestor de ciudades (elecciones, CityFlags, alcaldes) */
    private TownManager gestorCiudad;

    /** Perfil de jugadores (Payday, salarios, herencias) */
    private PlayerProfile perfilJugador;

    // -----------------------------------------------------------------------
    // Constantes de configuración de tareas
    // -----------------------------------------------------------------------

    /** Intervalo del ciclo Payday en ticks (20 ticks = 1 segundo) */
    private static final long INTERVALO_PAYDAY_TICKS = 20L * 60 * 30; // cada 30 minutos

    // -----------------------------------------------------------------------
    // Ciclo de vida del plugin
    // -----------------------------------------------------------------------

    /**
     * Llamado cuando el servidor carga el plugin.
     * Inicializa todos los servicios en el orden correcto.
     */
    @Override
    public void onEnable() {
        instancia = this;

        getLogger().info("============================================");
        getLogger().info("  RoleplayCore v" + getDescription().getVersion());
        getLogger().info("  Iniciando Motor de Roleplay...");
        getLogger().info("============================================");

        // Guardar la configuración por defecto si no existe
        saveDefaultConfig();

        // Inyección de dependencias: el orden importa
        setupServices();

        // Inicializar las tablas de la base de datos
        inicializarTablasBD();

        // Cargar evidencias forenses desde la DB (para persistencia entre reinicios)
        sistemaCriminal.cargarEvidenciasDesdeDB().thenAccept(cantidad ->
                getLogger().info("[RoleplayCore] " + cantidad + " evidencias forenses cargadas desde la DB.")
        );

        // Programar la tarea recurrente de Payday
        programarPayday();

        getLogger().info("[RoleplayCore] Todos los servicios iniciados correctamente. ¡Bienvenidos al servidor!");
    }

    /**
     * Llamado cuando el servidor apaga o recarga el plugin.
     * Cierra todos los recursos de forma ordenada.
     */
    @Override
    public void onDisable() {
        getLogger().info("[RoleplayCore] Apagando servicios...");

        // Cerrar el pool de conexiones de forma segura
        if (conectorDB != null) {
            conectorDB.cerrar();
        }

        getLogger().info("[RoleplayCore] Plugin desactivado correctamente.");
    }

    // -----------------------------------------------------------------------
    // setupServices: Inyección de dependencias
    // -----------------------------------------------------------------------

    /**
     * Configura e inyecta todas las dependencias entre servicios.
     * <p>
     * El orden de instanciación es crítico:
     * <ol>
     *   <li>{@link DatabaseConnector}: Base de toda la persistencia.</li>
     *   <li>{@link EconomyManager}: Depende de DatabaseConnector.</li>
     *   <li>{@link CrimeManager}: Depende de DatabaseConnector y EconomyManager.</li>
     *   <li>{@link CrimeSystem}: Depende de DatabaseConnector.</li>
     *   <li>{@link TownManager}: Depende de DatabaseConnector.</li>
     *   <li>{@link PlayerProfile}: Depende de DatabaseConnector y EconomyManager.</li>
     * </ol>
     * Si cualquier servicio falla al inicializarse, el plugin se desactiva
     * automáticamente para evitar comportamientos inconsistentes.
     * </p>
     */
    private void setupServices() {
        try {
            getLogger().info("[RoleplayCore] Inicializando DatabaseConnector...");
            this.conectorDB = new DatabaseConnector(this);

            getLogger().info("[RoleplayCore] Inicializando EconomyManager...");
            this.gestorEconomia = new EconomyManager(this, conectorDB);

            getLogger().info("[RoleplayCore] Inicializando CrimeManager...");
            this.gestorCriminal = new CrimeManager(this, conectorDB, gestorEconomia);

            getLogger().info("[RoleplayCore] Inicializando CrimeSystem (forense)...");
            this.sistemaCriminal = new CrimeSystem(this, conectorDB);

            getLogger().info("[RoleplayCore] Inicializando TownManager...");
            this.gestorCiudad = new TownManager(this, conectorDB);

            getLogger().info("[RoleplayCore] Inicializando PlayerProfile...");
            this.perfilJugador = new PlayerProfile(this, conectorDB, gestorEconomia);

            getLogger().info("[RoleplayCore] ✓ Todos los servicios inyectados correctamente.");

        } catch (Exception ex) {
            getLogger().log(Level.SEVERE,
                    "[RoleplayCore] ✗ Error crítico durante setupServices(). Desactivando plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // -----------------------------------------------------------------------
    // Inicialización de la base de datos
    // -----------------------------------------------------------------------

    /**
     * Crea las tablas de la base de datos si no existen.
     * <p>
     * Usa IF NOT EXISTS para ser idempotente: se puede ejecutar
     * múltiples veces sin efectos secundarios.
     * </p>
     */
    private void inicializarTablasBD() {
        conectorDB.executeTransaction(conexion -> {
            // Tabla de jugadores (perfil económico y social)
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_players (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  nombre VARCHAR(64) NOT NULL," +
                    "  bank_balance DOUBLE NOT NULL DEFAULT 0.0," +
                    "  cash_balance DOUBLE NOT NULL DEFAULT 0.0," +
                    "  dirty_money DOUBLE NOT NULL DEFAULT 0.0," +
                    "  trabajo_id INT NULL," +
                    "  fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  ultima_conexion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de trabajos disponibles
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_jobs (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  nombre_trabajo VARCHAR(64) NOT NULL," +
                    "  salario_base DOUBLE NOT NULL DEFAULT 500.0," +
                    "  descripcion VARCHAR(255)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de transacciones (auditoría ACID)
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_transactions (" +
                    "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  emisor_uuid VARCHAR(36) NOT NULL," +
                    "  receptor_uuid VARCHAR(36) NOT NULL," +
                    "  monto DOUBLE NOT NULL," +
                    "  tipo VARCHAR(32) NOT NULL," +
                    "  timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  INDEX idx_emisor (emisor_uuid)," +
                    "  INDEX idx_receptor (receptor_uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de registros criminales
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_criminal_records (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  wanted_level INT NOT NULL DEFAULT 0," +
                    "  ultimo_crimen DATETIME," +
                    "  multas_pendientes DOUBLE NOT NULL DEFAULT 0.0" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de evidencias forenses (ADN)
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_crime_evidence (" +
                    "  evidencia_id VARCHAR(36) PRIMARY KEY," +
                    "  criminal_uuid VARCHAR(36) NOT NULL," +
                    "  criminal_nombre VARCHAR(64) NOT NULL," +
                    "  tipo_crimen VARCHAR(32) NOT NULL," +
                    "  mundo VARCHAR(64) NOT NULL," +
                    "  coord_x DOUBLE NOT NULL," +
                    "  coord_y DOUBLE NOT NULL," +
                    "  coord_z DOUBLE NOT NULL," +
                    "  timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  expira DATETIME NOT NULL," +
                    "  INDEX idx_mundo (mundo)," +
                    "  INDEX idx_expira (expira)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de estado de la ciudad
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_town_state (" +
                    "  nombre_ciudad VARCHAR(64) PRIMARY KEY," +
                    "  alcalde_uuid VARCHAR(36)," +
                    "  alcalde_nombre VARCHAR(64)," +
                    "  estado_eleccion VARCHAR(32) NOT NULL DEFAULT 'SIN_ELECCION'" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de flags de ciudad
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_city_flags (" +
                    "  nombre_ciudad VARCHAR(64) NOT NULL," +
                    "  flag_nombre VARCHAR(64) NOT NULL," +
                    "  flag_valor BOOLEAN NOT NULL DEFAULT FALSE," +
                    "  PRIMARY KEY (nombre_ciudad, flag_nombre)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de elecciones
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_elections (" +
                    "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  nombre_ciudad VARCHAR(64) NOT NULL," +
                    "  estado VARCHAR(32) NOT NULL," +
                    "  iniciador_uuid VARCHAR(36) NOT NULL," +
                    "  ganador_uuid VARCHAR(36)," +
                    "  ganador_nombre VARCHAR(64)," +
                    "  total_votos INT NOT NULL DEFAULT 0," +
                    "  inicio DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  fin DATETIME" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla de votos por elección
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_election_votes (" +
                    "  votante_uuid VARCHAR(36) NOT NULL," +
                    "  nombre_ciudad VARCHAR(64) NOT NULL," +
                    "  candidato_uuid VARCHAR(36) NOT NULL," +
                    "  timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "  PRIMARY KEY (votante_uuid, nombre_ciudad)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Tabla del fondo estatal (para subsidios e impuestos)
            conexion.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS rp_state_fund (" +
                    "  id INT PRIMARY KEY DEFAULT 1," +
                    "  saldo DOUBLE NOT NULL DEFAULT 0.0," +
                    "  ultimo_ajuste DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            // Insertar registro del fondo estatal si no existe
            conexion.createStatement().executeUpdate(
                    "INSERT IGNORE INTO rp_state_fund (id, saldo) VALUES (1, 0.0)"
            );

            getLogger().info("[RoleplayCore] ✓ Tablas de la base de datos verificadas/creadas.");
            return null;
        }).exceptionally(error -> {
            getLogger().log(Level.SEVERE,
                    "[RoleplayCore] ✗ Error al inicializar tablas de la DB. " +
                    "¿Las credenciales en config.yml son correctas?", error);
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Tarea recurrente: Payday
    // -----------------------------------------------------------------------

    /**
     * Programa la tarea recurrente de Payday.
     * <p>
     * Se ejecuta cada {@value #INTERVALO_PAYDAY_TICKS} ticks de forma asíncrona
     * para procesar el salario de todos los jugadores en línea simultáneamente.
     * </p>
     */
    private void programarPayday() {
        new BukkitRunnable() {
            @Override
            public void run() {
                getLogger().info("[RoleplayCore] Ejecutando ciclo Payday...");
                perfilJugador.handlePayday().thenAccept(resultados -> {
                    getLogger().info("[RoleplayCore] Payday completado para " + resultados.size() + " jugadores.");
                    // Loguear estadísticas de caché al finalizar cada Payday
                    gestorEconomia.logEstadisticasCache();
                }).exceptionally(error -> {
                    getLogger().log(Level.SEVERE, "[RoleplayCore] Error en el ciclo Payday.", error);
                    return null;
                });
            }
        }.runTaskTimerAsynchronously(this, INTERVALO_PAYDAY_TICKS, INTERVALO_PAYDAY_TICKS);
    }

    // -----------------------------------------------------------------------
    // Getters de servicios (acceso público controlado)
    // -----------------------------------------------------------------------

    /**
     * Retorna la instancia singleton del plugin principal.
     *
     * @return Instancia activa de RoleplayCore
     */
    public static RoleplayCore getInstance() {
        return instancia;
    }

    /**
     * Retorna el conector de base de datos.
     *
     * @return DatabaseConnector activo
     */
    public DatabaseConnector getConectorDB() {
        return conectorDB;
    }

    /**
     * Retorna el gestor de economía.
     *
     * @return EconomyManager activo
     */
    public EconomyManager getGestorEconomia() {
        return gestorEconomia;
    }

    /**
     * Retorna el gestor del sistema criminal (niveles Wanted).
     *
     * @return CrimeManager activo
     */
    public CrimeManager getGestorCriminal() {
        return gestorCriminal;
    }

    /**
     * Retorna el sistema de evidencias forenses.
     *
     * @return CrimeSystem activo
     */
    public CrimeSystem getSistemaCriminal() {
        return sistemaCriminal;
    }

    /**
     * Retorna el gestor de ciudades y elecciones.
     *
     * @return TownManager activo
     */
    public TownManager getGestorCiudad() {
        return gestorCiudad;
    }

    /**
     * Retorna el gestor de perfiles de jugador.
     *
     * @return PlayerProfile activo
     */
    public PlayerProfile getPerfilJugador() {
        return perfilJugador;
    }
}
