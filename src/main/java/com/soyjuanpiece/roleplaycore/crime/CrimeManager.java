package com.soyjuanpiece.roleplaycore.crime;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.economy.EconomyManager;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Gestor del sistema criminal de RoleplayCore.
 * <p>
 * Controla el escalado de niveles Wanted, multas, arrestos y la interacción
 * con el sistema policial. Usa caché Guava para datos temporales de sesión
 * (nivel Wanted en memoria, alertas activas).
 * </p>
 *
 * <h3>Niveles Wanted:</h3>
 * <ul>
 *   <li><b>Nivel 1:</b> Multa por chat.</li>
 *   <li><b>Nivel 2:</b> Los guardias NPC atacan al criminal.</li>
 *   <li><b>Nivel 3:</b> Alarma sonora en la zona del criminal.</li>
 *   <li><b>Nivel 4:</b> Bloqueo de todos los comandos de teletransporte.</li>
 *   <li><b>Nivel 5 (Most Wanted):</b> El criminal suelta todo su inventario al morir.</li>
 * </ul>
 */
public class CrimeManager {

    /** Referencia al plugin principal */
    private final RoleplayCore plugin;

    /** Conector de base de datos para persistencia de registros criminales */
    private final DatabaseConnector db;

    /** Referencia al gestor de economía para aplicar multas */
    private final EconomyManager economy;

    /**
     * Caché Guava para datos temporales de sesión.
     * Almacena el nivel Wanted actual de jugadores en línea.
     * Expiración: 30 minutos tras el último acceso (datos de sesión efímeros).
     */
    private final Cache<UUID, Integer> cacheNivelWanted;

    /**
     * Mapa concurrente con los jugadores que tienen bloqueado el teletransporte
     * por tener nivel Wanted 4 o superior.
     */
    private final Map<UUID, Boolean> jugadoresConTpBloqueado;

    // -----------------------------------------------------------------------
    // Constantes del sistema criminal
    // -----------------------------------------------------------------------

    /** Nivel máximo del sistema Wanted */
    private static final int NIVEL_WANTED_MAXIMO = 5;

    /** Tiempo en segundos para reducir el nivel Wanted (si el jugador se esconde) */
    private static final long TIEMPO_REDUCCION_WANTED_SEGUNDOS = 600L; // 10 minutos

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo CrimeManager e inicializa los cachés de Guava.
     *
     * @param plugin  Instancia del plugin principal
     * @param db      Conector de base de datos
     * @param economy Gestor de economía para aplicar multas
     */
    public CrimeManager(RoleplayCore plugin, DatabaseConnector db, EconomyManager economy) {
        this.plugin   = plugin;
        this.db       = db;
        this.economy  = economy;

        // Inicializar caché Guava para datos de sesión (nivel Wanted)
        this.cacheNivelWanted = CacheBuilder.newBuilder()
                .maximumSize(200)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

        this.jugadoresConTpBloqueado = new ConcurrentHashMap<>();
    }

    // -----------------------------------------------------------------------
    // Gestión del nivel Wanted
    // -----------------------------------------------------------------------

    /**
     * Obtiene el nivel Wanted actual de un jugador.
     * <p>
     * Primero consulta la caché Guava; si no existe, consulta la base de datos
     * y almacena el resultado en caché para futuras consultas rápidas.
     * </p>
     *
     * @param jugadorId UUID del jugador
     * @return CompletableFuture con el nivel Wanted (0 si no tiene antecedentes)
     */
    public CompletableFuture<Integer> obtenerNivelWanted(UUID jugadorId) {
        // Consultar caché Guava primero (datos de sesión)
        Integer nivelCacheado = cacheNivelWanted.getIfPresent(jugadorId);
        if (nivelCacheado != null) {
            return CompletableFuture.completedFuture(nivelCacheado);
        }

        // Consultar base de datos
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT wanted_level FROM rp_criminal_records WHERE uuid = ? LIMIT 1"
            );
            ps.setString(1, jugadorId.toString());
            ResultSet rs = ps.executeQuery();

            int nivel = rs.next() ? rs.getInt("wanted_level") : 0;

            // Almacenar en caché Guava para acceso rápido durante la sesión
            cacheNivelWanted.put(jugadorId, nivel);
            return nivel;
        });
    }

    /**
     * Incrementa el nivel Wanted de un jugador al cometer un crimen.
     * <p>
     * El nivel Wanted se incrementa según el tipo de crimen cometido y
     * activa los efectos correspondientes al nuevo nivel:
     * <ul>
     *   <li>Nivel 1: Notificación al jugador con la multa.</li>
     *   <li>Nivel 2: Los guardias NPC ahora atacan al criminal.</li>
     *   <li>Nivel 3: Alarma sonora emitida en la zona.</li>
     *   <li>Nivel 4: Bloqueo de comandos de teletransporte.</li>
     *   <li>Nivel 5: Estado "Most Wanted" activado.</li>
     * </ul>
     * </p>
     *
     * @param jugadorId  UUID del jugador criminal
     * @param tipoCrimen Tipo de crimen cometido
     * @return CompletableFuture con el nuevo nivel Wanted
     */
    public CompletableFuture<Integer> incrementarWanted(UUID jugadorId, CrimeType tipoCrimen) {
        int incremento = tipoCrimen.getNivelWantedInicial();

        return db.executeTransaction(conexion -> {
            // Obtener el nivel actual con bloqueo de fila para evitar condiciones de carrera
            PreparedStatement psObtener = conexion.prepareStatement(
                    "SELECT wanted_level FROM rp_criminal_records WHERE uuid = ? FOR UPDATE"
            );
            psObtener.setString(1, jugadorId.toString());
            ResultSet rs = psObtener.executeQuery();

            int nivelActual = rs.next() ? rs.getInt("wanted_level") : 0;
            int nuevoNivel  = Math.min(nivelActual + incremento, NIVEL_WANTED_MAXIMO);

            // Actualizar o insertar el registro criminal
            PreparedStatement psActualizar = conexion.prepareStatement(
                    "INSERT INTO rp_criminal_records (uuid, wanted_level, ultimo_crimen) " +
                    "VALUES (?, ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE wanted_level = ?, ultimo_crimen = NOW()"
            );
            psActualizar.setString(1, jugadorId.toString());
            psActualizar.setInt(2, nuevoNivel);
            psActualizar.setInt(3, nuevoNivel);
            psActualizar.executeUpdate();

            // Actualizar caché de sesión
            cacheNivelWanted.put(jugadorId, nuevoNivel);

            // Aplicar efectos del nivel Wanted en el hilo principal
            int nivelFinal = nuevoNivel;
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    aplicarEfectosWanted(jugadorId, nivelFinal, tipoCrimen)
            );

            return nuevoNivel;
        });
    }

    /**
     * Aplica los efectos del nivel Wanted al jugador.
     * <p>
     * Usa Pattern Matching de Java 21 (switch expressions) para manejar
     * cada nivel de forma clara y sin fall-through accidental.
     * </p>
     *
     * @param jugadorId  UUID del jugador
     * @param nivel      Nuevo nivel Wanted del jugador
     * @param tipoCrimen Tipo de crimen que provocó el cambio de nivel
     */
    private void aplicarEfectosWanted(UUID jugadorId, int nivel, CrimeType tipoCrimen) {
        Player jugador = Bukkit.getPlayer(jugadorId);

        // Pattern Matching en switch expressions (Java 21)
        switch (nivel) {
            case 1 -> {
                // Nivel 1: Solo una multa en el chat
                if (jugador != null) {
                    jugador.sendMessage("§c[POLICÍA] §fSe ha emitido una orden de multa en tu contra por: §e"
                            + tipoCrimen.getNombre() + "§f. Multa: §c$" + (int) tipoCrimen.getMultaBase());
                }
                plugin.getLogger().info("[CrimeManager] Jugador " + jugadorId + " → Wanted Nivel 1 (Multa).");
            }
            case 2 -> {
                // Nivel 2: Los guardias NPC comienzan a atacar al criminal
                if (jugador != null) {
                    jugador.sendMessage("§c[ALERTA] §fLos guardias han sido alertados de tu presencia.");
                }
                // TODO: Activar IA de guardias NPC (requiere plugin de IA como MythicMobs)
                plugin.getLogger().info("[CrimeManager] Jugador " + jugadorId + " → Wanted Nivel 2 (Guardias alertados).");
            }
            case 3 -> {
                // Nivel 3: Alarma sonora en la zona
                if (jugador != null) {
                    jugador.sendMessage("§c[ALARMA] §f¡Se ha activado una alarma en tu zona!");
                    jugador.getWorld().playSound(jugador.getLocation(),
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 3.0f, 0.5f);
                }
                plugin.getLogger().info("[CrimeManager] Jugador " + jugadorId + " → Wanted Nivel 3 (Alarma activa).");
            }
            case 4 -> {
                // Nivel 4: Bloqueo de teletransporte
                jugadoresConTpBloqueado.put(jugadorId, true);
                if (jugador != null) {
                    jugador.sendMessage("§c[SISTEMA] §fTus privilegios de teletransporte han sido §cBLOQUEADOS §fpor las autoridades.");
                }
                plugin.getLogger().info("[CrimeManager] Jugador " + jugadorId + " → Wanted Nivel 4 (TP bloqueado).");
            }
            case 5 -> {
                // Nivel 5: Most Wanted - pierde todo el inventario al morir
                jugadoresConTpBloqueado.put(jugadorId, true);
                if (jugador != null) {
                    jugador.sendMessage("§4§l[⚠ MOST WANTED ⚠] §cEres el criminal más buscado del servidor.");
                    jugador.sendMessage("§c¡Morirás soltando TODO tu inventario sin excepción!");
                }
                plugin.getLogger().warning("[CrimeManager] Jugador " + jugadorId + " → Wanted Nivel 5 (MOST WANTED).");
            }
        }
    }

    /**
     * Reduce el nivel Wanted de un jugador (al pagar la multa o cumplir la pena).
     *
     * @param jugadorId UUID del jugador
     * @param reduccion Número de niveles a reducir
     * @return CompletableFuture con el nuevo nivel Wanted
     */
    public CompletableFuture<Integer> reducirWanted(UUID jugadorId, int reduccion) {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE rp_criminal_records SET wanted_level = GREATEST(0, wanted_level - ?) " +
                    "WHERE uuid = ?"
            );
            ps.setInt(1, reduccion);
            ps.setString(2, jugadorId.toString());
            ps.executeUpdate();

            // Obtener el nivel actualizado
            PreparedStatement psObtener = conexion.prepareStatement(
                    "SELECT wanted_level FROM rp_criminal_records WHERE uuid = ?"
            );
            psObtener.setString(1, jugadorId.toString());
            ResultSet rs = psObtener.executeQuery();

            int nuevoNivel = rs.next() ? rs.getInt("wanted_level") : 0;

            // Actualizar caché y desbloquear TP si el nivel bajó
            cacheNivelWanted.put(jugadorId, nuevoNivel);
            if (nuevoNivel < 4) {
                jugadoresConTpBloqueado.remove(jugadorId);
            }

            return nuevoNivel;
        });
    }

    /**
     * Verifica si un jugador tiene el teletransporte bloqueado por su nivel Wanted.
     *
     * @param jugadorId UUID del jugador
     * @return true si el TP está bloqueado (Wanted nivel 4 o 5)
     */
    public boolean tieneTpBloqueado(UUID jugadorId) {
        return jugadoresConTpBloqueado.getOrDefault(jugadorId, false);
    }

    /**
     * Limpia los datos de sesión del jugador al desconectarse.
     * Los datos persisten en la base de datos para cuando vuelva a conectarse.
     *
     * @param jugadorId UUID del jugador que se desconecta
     */
    public void limpiarSesion(UUID jugadorId) {
        cacheNivelWanted.invalidate(jugadorId);
        // El bloqueo de TP se mantiene aunque se desconecte (se carga de DB al reconectar)
    }

    /**
     * Verifica si un jugador tiene el nivel "Most Wanted" (nivel 5).
     *
     * @param jugadorId UUID del jugador
     * @return CompletableFuture con true si es Most Wanted
     */
    public CompletableFuture<Boolean> esMostWanted(UUID jugadorId) {
        return obtenerNivelWanted(jugadorId)
                .thenApply(nivel -> nivel >= NIVEL_WANTED_MAXIMO);
    }
}
