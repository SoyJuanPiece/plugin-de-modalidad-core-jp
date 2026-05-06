package com.soyjuanpiece.roleplaycore.database;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Conector de base de datos con pool de conexiones HikariCP.
 * <p>
 * Responsable de gestionar todas las operaciones SQL de manera:
 * <ul>
 *   <li><b>Asíncrona:</b> mediante {@link CompletableFuture} y un executor dedicado.</li>
 *   <li><b>Atómica:</b> con transacciones ACID (commit/rollback automáticos).</li>
 *   <li><b>Resiliente:</b> sistema de reintentos con Exponential Backoff.</li>
 * </ul>
 * </p>
 */
public class DatabaseConnector {

    /** Referencia al plugin principal para acceder a la configuración y logger */
    private final RoleplayCore plugin;

    /** Pool de conexiones HikariCP de alto rendimiento */
    private HikariDataSource dataSource;

    /**
     * Executor de hilos virtuales (Java 21) dedicado a operaciones de I/O de base de datos.
     * Los hilos virtuales son más eficientes que los hilos de plataforma para I/O bloqueante.
     */
    private final Executor executorBD;

    // -----------------------------------------------------------------------
    // Constantes de política de reintentos (Exponential Backoff)
    // -----------------------------------------------------------------------

    /** Número máximo de intentos antes de reportar fallo definitivo */
    private static final int MAX_INTENTOS = 5;

    /** Tiempo base de espera entre reintentos (en milisegundos) */
    private static final long ESPERA_BASE_MS = 200L;

    /** Factor multiplicador para el backoff exponencial */
    private static final double FACTOR_BACKOFF = 2.0;

    /** Tiempo máximo de espera entre reintentos para evitar esperas infinitas (ms) */
    private static final long ESPERA_MAXIMA_MS = 10_000L;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo DatabaseConnector e inicializa el pool de conexiones.
     *
     * @param plugin Instancia del plugin principal
     */
    public DatabaseConnector(RoleplayCore plugin) {
        this.plugin = plugin;
        // Executor con hilos virtuales para operaciones I/O eficientes
        this.executorBD = Executors.newVirtualThreadPerTaskExecutor();
        inicializarPool();
    }

    // -----------------------------------------------------------------------
    // Inicialización del pool
    // -----------------------------------------------------------------------

    /**
     * Inicializa el pool de conexiones HikariCP leyendo la configuración del plugin.
     * <p>
     * Parámetros clave configurados:
     * <ul>
     *   <li>{@code maximumPoolSize}: Máximo de conexiones simultáneas.</li>
     *   <li>{@code connectionTimeout}: Tiempo máximo de espera para obtener una conexión.</li>
     *   <li>{@code idleTimeout}: Tiempo antes de cerrar conexiones inactivas.</li>
     *   <li>{@code maxLifetime}: Tiempo de vida máximo de una conexión.</li>
     * </ul>
     * </p>
     */
    private void inicializarPool() {
        HikariConfig config = new HikariConfig();

        // Lectura de parámetros de conexión desde config.yml
        String host     = plugin.getConfig().getString("database.host", "localhost");
        int    puerto   = plugin.getConfig().getInt("database.port", 3306);
        String baseDatos= plugin.getConfig().getString("database.name", "roleplay_db");
        String usuario  = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");

        // URL de conexión con parámetros de rendimiento y seguridad
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true",
                host, puerto, baseDatos
        ));
        config.setUsername(usuario);
        config.setPassword(password);

        // Configuración del pool
        config.setPoolName("RoleplayCore-DB-Pool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000L);   // 10 segundos máximo para obtener conexión
        config.setIdleTimeout(300_000L);         // 5 minutos de inactividad antes de cerrar
        config.setMaxLifetime(1_800_000L);        // 30 minutos de vida máxima por conexión
        config.setKeepaliveTime(60_000L);         // Keepalive cada 60 segundos

        // Propiedades de rendimiento para MySQL
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        try {
            this.dataSource = new HikariDataSource(config);
            plugin.getLogger().info("[RoleplayCore] Pool de conexiones DB inicializado correctamente.");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "[RoleplayCore] Error al inicializar el pool de conexiones.", ex);
        }
    }

    // -----------------------------------------------------------------------
    // Método principal: executeTransaction (ACID + Exponential Backoff)
    // -----------------------------------------------------------------------

    /**
     * Ejecuta una operación SQL de forma <b>segura, atómica y asíncrona</b>.
     * <p>
     * La transacción sigue el protocolo ACID:
     * <ul>
     *   <li><b>Atomicidad:</b> Todo o nada. Si falla, se hace rollback completo.</li>
     *   <li><b>Consistencia:</b> La BD permanece en estado válido tras la operación.</li>
     *   <li><b>Aislamiento:</b> Se usa el nivel de aislamiento por defecto de MySQL
     *       (REPEATABLE READ) para evitar lecturas sucias.</li>
     *   <li><b>Durabilidad:</b> Una vez confirmado el commit, el cambio es permanente.</li>
     * </ul>
     * <br>
     * En caso de fallo de red o timeout, aplica <b>Exponential Backoff</b>:
     * el tiempo de espera entre reintentos se duplica cada vez, con un máximo de
     * {@value #ESPERA_MAXIMA_MS} ms, hasta un máximo de {@value #MAX_INTENTOS} intentos.
     * </p>
     *
     * @param <T>      Tipo del resultado que devuelve la operación
     * @param callback Lambda o método que contiene la lógica SQL a ejecutar
     * @return {@link CompletableFuture} con el resultado de la operación
     */
    public <T> CompletableFuture<T> executeTransaction(TransactionCallback<T> callback) {
        return CompletableFuture.supplyAsync(() -> {
            int intento = 0;
            long espera = ESPERA_BASE_MS;
            Exception ultimoError = null;

            // Bucle de reintentos con Exponential Backoff
            while (intento < MAX_INTENTOS) {
                intento++;
                try (Connection conexion = dataSource.getConnection()) {

                    // Desactivar auto-commit para gestionar la transacción manualmente
                    conexion.setAutoCommit(false);

                    try {
                        // Ejecutar la lógica SQL proporcionada por el llamador
                        T resultado = callback.ejecutar(conexion);

                        // Confirmar la transacción si todo fue exitoso
                        conexion.commit();

                        if (intento > 1) {
                            plugin.getLogger().info(String.format(
                                    "[RoleplayCore] Transacción exitosa en el intento %d.", intento
                            ));
                        }

                        return resultado;

                    } catch (Exception errorSQL) {
                        // Deshacer todos los cambios si ocurre cualquier error
                        try {
                            conexion.rollback();
                        } catch (SQLException errorRollback) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "[RoleplayCore] Error crítico al hacer rollback.", errorRollback);
                        }
                        throw errorSQL; // Re-lanzar para que el bucle lo capture
                    }

                } catch (Exception error) {
                    ultimoError = error;

                    plugin.getLogger().warning(String.format(
                            "[RoleplayCore] Fallo en transacción (intento %d/%d): %s. Reintentando en %dms...",
                            intento, MAX_INTENTOS, error.getMessage(), espera
                    ));

                    // Si no hemos agotado los intentos, esperar antes del siguiente
                    if (intento < MAX_INTENTOS) {
                        try {
                            Thread.sleep(espera);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Calcular la próxima espera con backoff exponencial
                        espera = (long) Math.min(espera * FACTOR_BACKOFF, ESPERA_MAXIMA_MS);
                    }
                }
            }

            // Todos los intentos fallaron: registrar y lanzar excepción
            plugin.getLogger().log(Level.SEVERE, String.format(
                    "[RoleplayCore] Transacción fallida definitivamente tras %d intentos.", MAX_INTENTOS
            ), ultimoError);

            throw new RuntimeException(
                    "La transacción falló tras " + MAX_INTENTOS + " intentos.", ultimoError
            );

        }, executorBD);
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /**
     * Obtiene una conexión directa del pool para operaciones de solo lectura
     * que no requieren gestión transaccional completa.
     *
     * @return Conexión activa del pool HikariCP
     * @throws SQLException si no hay conexiones disponibles en el pool
     */
    public Connection obtenerConexion() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Cierra el pool de conexiones de forma ordenada al apagar el plugin.
     * Debe llamarse desde {@code RoleplayCore#onDisable()}.
     */
    public void cerrar() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("[RoleplayCore] Pool de conexiones DB cerrado correctamente.");
        }
    }

    /**
     * Verifica si el pool de conexiones está activo y operativo.
     *
     * @return true si el DataSource está disponible y no cerrado
     */
    public boolean estaActivo() {
        return dataSource != null && !dataSource.isClosed();
    }
}
