package com.soyjuanpiece.roleplaycore.job;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.economy.EconomyManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Gestor del sistema de trabajos de RoleplayCore.
 * <p>
 * Gestiona asignación de empleo, turnos de trabajo (jornadas), promociones
 * y estadísticas de productividad por jugador.
 * </p>
 */
public class JobManager {

    private final RoleplayCore plugin;
    private final DatabaseConnector db;
    private final EconomyManager economy;

    /** Caché en memoria: UUID jugador → JobType actual */
    private final Map<UUID, JobType> cacheTrabajos = new ConcurrentHashMap<>();

    /** Jugadores actualmente en turno de trabajo (jornada activa) */
    private final Map<UUID, Long> jornadaInicio = new ConcurrentHashMap<>();

    /** Bonus acumulado por productividad durante la jornada (UUID → bonus $) */
    private final Map<UUID, Double> bonusProductividad = new ConcurrentHashMap<>();

    // Costo administrativo para cambiar de trabajo
    private static final double COSTO_CAMBIO_TRABAJO = 200.0;

    public JobManager(RoleplayCore plugin, DatabaseConnector db, EconomyManager economy) {
        this.plugin  = plugin;
        this.db      = db;
        this.economy = economy;
    }

    // -----------------------------------------------------------------------
    // Consulta de trabajo actual
    // -----------------------------------------------------------------------

    /**
     * Obtiene el trabajo actual de un jugador.
     */
    public CompletableFuture<JobType> obtenerTrabajo(UUID jugadorId) {
        JobType cached = cacheTrabajos.get(jugadorId);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT j.nombre_trabajo FROM rp_players p " +
                    "LEFT JOIN rp_jobs j ON p.trabajo_id = j.id " +
                    "WHERE p.uuid = ? LIMIT 1"
            );
            ps.setString(1, jugadorId.toString());
            ResultSet rs = ps.executeQuery();

            JobType tipo = JobType.DESEMPLEADO;
            if (rs.next() && rs.getString("nombre_trabajo") != null) {
                try {
                    tipo = JobType.valueOf(rs.getString("nombre_trabajo").toUpperCase()
                            .replace(" ", "_").replace("Á", "A").replace("É", "E")
                            .replace("Í", "I").replace("Ó", "O").replace("Ú", "U"));
                } catch (IllegalArgumentException ignored) {}
            }
            cacheTrabajos.put(jugadorId, tipo);
            return tipo;
        });
    }

    // -----------------------------------------------------------------------
    // Solicitar / cambiar trabajo
    // -----------------------------------------------------------------------

    /**
     * Asigna un trabajo a un jugador, cobrando el costo de cambio si aplica.
     *
     * @param jugador  Jugador que solicita el trabajo
     * @param trabajo  Trabajo solicitado
     * @return CompletableFuture con true si fue asignado exitosamente
     */
    public CompletableFuture<Boolean> solicitarTrabajo(Player jugador, JobType trabajo) {
        UUID id = jugador.getUniqueId();

        return db.executeTransaction(conexion -> {
            // 1. Obtener trabajo actual
            PreparedStatement psCurrent = conexion.prepareStatement(
                    "SELECT j.nombre_trabajo FROM rp_players p " +
                    "LEFT JOIN rp_jobs j ON p.trabajo_id = j.id WHERE p.uuid = ?"
            );
            psCurrent.setString(1, id.toString());
            ResultSet rsCurrent = psCurrent.executeQuery();
            boolean tieneTrabajoActual = rsCurrent.next() && rsCurrent.getString("nombre_trabajo") != null;

            // 2. Verificar plazas disponibles (si el trabajo tiene límite)
            if (trabajo.getPlazasMaximas() > 0) {
                PreparedStatement psPlazas = conexion.prepareStatement(
                        "SELECT COUNT(*) as total FROM rp_players p " +
                        "JOIN rp_jobs j ON p.trabajo_id = j.id " +
                        "WHERE j.nombre_trabajo = ?"
                );
                psPlazas.setString(1, trabajo.name());
                ResultSet rsPlazas = psPlazas.executeQuery();
                if (rsPlazas.next() && rsPlazas.getInt("total") >= trabajo.getPlazasMaximas()) {
                    return false; // Sin plazas disponibles
                }
            }

            // 3. Asegurar que el trabajo existe en rp_jobs
            PreparedStatement psEnsure = conexion.prepareStatement(
                    "INSERT IGNORE INTO rp_jobs (nombre_trabajo, salario_base, descripcion) VALUES (?, ?, ?)"
            );
            psEnsure.setString(1, trabajo.name());
            psEnsure.setDouble(2, trabajo.getSalarioBase());
            psEnsure.setString(3, trabajo.getNombre());
            psEnsure.executeUpdate();

            // 4. Obtener el ID del trabajo
            PreparedStatement psId = conexion.prepareStatement(
                    "SELECT id FROM rp_jobs WHERE nombre_trabajo = ? LIMIT 1"
            );
            psId.setString(1, trabajo.name());
            ResultSet rsId = psId.executeQuery();
            if (!rsId.next()) return false;
            int jobId = rsId.getInt("id");

            // 5. Asignar el trabajo al jugador
            PreparedStatement psAsignar = conexion.prepareStatement(
                    "UPDATE rp_players SET trabajo_id = ? WHERE uuid = ?"
            );
            psAsignar.setInt(1, jobId);
            psAsignar.setString(2, id.toString());
            psAsignar.executeUpdate();

            // 6. Cobrar costo de cambio si ya tenía trabajo
            if (tieneTrabajoActual) {
                PreparedStatement psCobrar = conexion.prepareStatement(
                        "UPDATE rp_players SET bank_balance = bank_balance - ? WHERE uuid = ? AND bank_balance >= ?"
                );
                psCobrar.setDouble(1, COSTO_CAMBIO_TRABAJO);
                psCobrar.setString(2, id.toString());
                psCobrar.setDouble(3, COSTO_CAMBIO_TRABAJO);
                psCobrar.executeUpdate();
            }

            return true;
        }).thenApply(exito -> {
            if (exito) {
                cacheTrabajos.put(id, trabajo);
                economy.limpiarCacheJugador(id);

                // Aplicar permiso del trabajo si está en línea
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (jugador.isOnline() && trabajo.getPermiso() != null) {
                        // Nota: los permisos se gestionan vía LuckPerms/grupo de trabajo
                        jugador.sendMessage("§a[EMPLEO] §fAhora eres §e" + trabajo.getNombre()
                                + "§f. Salario: §a$" + String.format("%.0f", trabajo.getSalarioBase())
                                + "§f por Payday.");
                    }
                });
            }
            return exito;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "[JobManager] Error al solicitar trabajo.", ex);
            return false;
        });
    }

    /**
     * Retira a un jugador de su trabajo actual.
     */
    public CompletableFuture<Boolean> renunciar(UUID jugadorId) {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE rp_players SET trabajo_id = NULL WHERE uuid = ?"
            );
            ps.setString(1, jugadorId.toString());
            int filas = ps.executeUpdate();
            return filas > 0;
        }).thenApply(exito -> {
            if (exito) {
                cacheTrabajos.put(jugadorId, JobType.DESEMPLEADO);
                economy.limpiarCacheJugador(jugadorId);
            }
            return exito;
        });
    }

    // -----------------------------------------------------------------------
    // Jornada laboral (turno de trabajo activo)
    // -----------------------------------------------------------------------

    /**
     * Inicia el turno de trabajo de un jugador.
     */
    public boolean iniciarJornada(UUID jugadorId) {
        if (jornadaInicio.containsKey(jugadorId)) return false; // Ya en turno
        jornadaInicio.put(jugadorId, System.currentTimeMillis());
        bonusProductividad.put(jugadorId, 0.0);
        return true;
    }

    /**
     * Finaliza el turno y otorga el bonus de productividad acumulado.
     *
     * @return CompletableFuture con el bonus ganado (0 si no tenía turno)
     */
    public CompletableFuture<Double> terminarJornada(UUID jugadorId) {
        Long inicio = jornadaInicio.remove(jugadorId);
        if (inicio == null) return CompletableFuture.completedFuture(0.0);

        double bonus = bonusProductividad.getOrDefault(jugadorId, 0.0);
        bonusProductividad.remove(jugadorId);

        if (bonus <= 0) return CompletableFuture.completedFuture(0.0);

        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE rp_players SET bank_balance = bank_balance + ? WHERE uuid = ?"
            );
            ps.setDouble(1, bonus);
            ps.setString(2, jugadorId.toString());
            ps.executeUpdate();

            PreparedStatement psLog = conexion.prepareStatement(
                    "INSERT INTO rp_transactions (emisor_uuid, receptor_uuid, monto, tipo) " +
                    "VALUES ('ESTADO', ?, ?, 'BONUS_TRABAJO')"
            );
            psLog.setString(1, jugadorId.toString());
            psLog.setDouble(2, bonus);
            psLog.executeUpdate();

            economy.limpiarCacheJugador(jugadorId);
            return bonus;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "[JobManager] Error al pagar bonus.", ex);
            return 0.0;
        });
    }

    /**
     * Agrega bonus de productividad al jugador en turno (por acción de trabajo).
     */
    public void agregarBonus(UUID jugadorId, double cantidad) {
        if (!jornadaInicio.containsKey(jugadorId)) return;
        bonusProductividad.merge(jugadorId, cantidad, Double::sum);
    }

    /**
     * Retorna true si el jugador está en turno activo.
     */
    public boolean estaEnJornada(UUID jugadorId) {
        return jornadaInicio.containsKey(jugadorId);
    }

    // -----------------------------------------------------------------------
    // Lista de trabajos disponibles
    // -----------------------------------------------------------------------

    /**
     * Retorna los trabajos legales disponibles (con plazas libres o ilimitados).
     */
    public CompletableFuture<List<JobType>> obtenerTrabajosDisponibles() {
        return db.executeTransaction(conexion -> {
            // Contar empleados por trabajo
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT j.nombre_trabajo, COUNT(p.uuid) as total " +
                    "FROM rp_jobs j LEFT JOIN rp_players p ON p.trabajo_id = j.id " +
                    "GROUP BY j.nombre_trabajo"
            );
            ResultSet rs = ps.executeQuery();

            Map<String, Integer> ocupados = new ConcurrentHashMap<>();
            while (rs.next()) {
                ocupados.put(rs.getString("nombre_trabajo"), rs.getInt("total"));
            }

            List<JobType> disponibles = new ArrayList<>();
            for (JobType tipo : JobType.values()) {
                if (tipo == JobType.DESEMPLEADO || tipo.esIlegal()) continue;
                int max  = tipo.getPlazasMaximas();
                int ocup = ocupados.getOrDefault(tipo.name(), 0);
                if (max == 0 || ocup < max) {
                    disponibles.add(tipo);
                }
            }
            return disponibles;
        });
    }

    /**
     * Limpia la caché al desconectarse el jugador.
     */
    public void limpiarSesion(UUID jugadorId) {
        cacheTrabajos.remove(jugadorId);
        jornadaInicio.remove(jugadorId);
        bonusProductividad.remove(jugadorId);
    }
}
