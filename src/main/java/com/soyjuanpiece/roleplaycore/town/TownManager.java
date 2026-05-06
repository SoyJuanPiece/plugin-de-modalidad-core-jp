package com.soyjuanpiece.roleplaycore.town;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.model.ElectionResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Gestor de ciudades (Towns) del sistema RoleplayCore.
 * <p>
 * Responsable de:
 * <ul>
 *   <li>Gestionar los flags de configuración de la ciudad (PvP, robo, impuestos).</li>
 *   <li>Administrar el sistema de elecciones municipales.</li>
 *   <li>Gestionar subsidios de estado para nuevos jugadores.</li>
 *   <li>Controlar el sistema político de la ciudad.</li>
 * </ul>
 * </p>
 */
public class TownManager {

    /** Referencia al plugin principal */
    private final RoleplayCore plugin;

    /** Conector de base de datos para persistencia */
    private final DatabaseConnector db;

    /**
     * Estado actual de los CityFlags.
     * Mapa concurrente: CityFlag → valor booleano actual.
     * Cargado desde la DB al iniciar y sincronizado al cambiar.
     */
    private final Map<CityFlag, Boolean> cityFlags;

    /**
     * Mapa de votos de la elección actual en curso.
     * UUID del candidato → número de votos recibidos.
     * Se almacena en memoria durante la elección y se resetea al finalizar.
     */
    private final Map<UUID, Integer> votosActuales;

    /**
     * Set de jugadores que ya votaron en la elección actual
     * para evitar votos duplicados.
     */
    private final Map<UUID, UUID> votantesActuales; // votanteId → candidatoId

    /** Estado de la elección actual */
    private EstadoEleccion estadoEleccion;

    /** UUID del alcalde actual */
    private UUID alcaldeActualId;

    /** Nombre del alcalde actual */
    private String alcaldeActualNombre;

    /** Nombre de la ciudad gestionada */
    private final String nombreCiudad;

    // -----------------------------------------------------------------------
    // Estado de la elección
    // -----------------------------------------------------------------------

    /**
     * Posibles estados del proceso electoral.
     */
    public enum EstadoEleccion {
        /** No hay elección activa; el alcalde actual gobierna */
        SIN_ELECCION,
        /** Se está recibiendo candidaturas */
        CANDIDATURAS_ABIERTAS,
        /** La votación está en curso */
        VOTACION_ACTIVA,
        /** La elección terminó y se está procesando el resultado */
        CONTANDO_VOTOS
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo TownManager e inicializa los flags de ciudad con sus valores por defecto.
     *
     * @param plugin Instancia del plugin principal
     * @param db     Conector de base de datos
     */
    public TownManager(RoleplayCore plugin, DatabaseConnector db) {
        this.plugin           = plugin;
        this.db               = db;
        this.nombreCiudad     = plugin.getConfig().getString("town.nombre", "La Ciudad");
        this.cityFlags        = new ConcurrentHashMap<>();
        this.votosActuales    = new ConcurrentHashMap<>();
        this.votantesActuales = new ConcurrentHashMap<>();
        this.estadoEleccion   = EstadoEleccion.SIN_ELECCION;

        // Cargar valores por defecto de todos los CityFlags
        for (CityFlag flag : CityFlag.values()) {
            cityFlags.put(flag, flag.getValorPorDefecto());
        }

        // Cargar estado persistido desde la DB
        cargarEstadoDesdeDB();
    }

    // -----------------------------------------------------------------------
    // Función principal: triggerElection (Sistema de Elecciones)
    // -----------------------------------------------------------------------

    /**
     * Inicia una nueva elección municipal y resetea todos los votos actuales.
     * <p>
     * Esta función:
     * <ol>
     *   <li>Verifica que no haya una elección activa.</li>
     *   <li>Resetea todos los votos del período anterior.</li>
     *   <li>Cambia el estado de la ciudad a VOTACION_ACTIVA.</li>
     *   <li>Persiste el nuevo estado en la base de datos.</li>
     *   <li>Notifica a todos los jugadores en línea.</li>
     * </ol>
     * </p>
     *
     * @param iniciadorId UUID del jugador o sistema que inicia la elección
     * @return CompletableFuture con true si la elección comenzó correctamente
     */
    public CompletableFuture<Boolean> triggerElection(UUID iniciadorId) {
        // Validación: no se puede iniciar una elección si ya hay una activa
        if (estadoEleccion == EstadoEleccion.VOTACION_ACTIVA
                || estadoEleccion == EstadoEleccion.CANDIDATURAS_ABIERTAS) {
            plugin.getLogger().warning("[TownManager] Se intentó iniciar una elección mientras otra estaba activa.");
            return CompletableFuture.completedFuture(false);
        }

        return db.executeTransaction(conexion -> {
            // 1. Registrar la nueva elección en la base de datos
            PreparedStatement psEleccion = conexion.prepareStatement(
                    "INSERT INTO rp_elections (nombre_ciudad, estado, iniciador_uuid, inicio) " +
                    "VALUES (?, 'CANDIDATURAS_ABIERTAS', ?, NOW())"
            );
            psEleccion.setString(1, nombreCiudad);
            psEleccion.setString(2, iniciadorId.toString());
            psEleccion.executeUpdate();

            // 2. Resetear todos los votos de la elección anterior en la DB
            PreparedStatement psResetVotos = conexion.prepareStatement(
                    "DELETE FROM rp_election_votes WHERE nombre_ciudad = ?"
            );
            psResetVotos.setString(1, nombreCiudad);
            psResetVotos.executeUpdate();

            // 3. Actualizar el estado de la ciudad en la DB
            PreparedStatement psEstado = conexion.prepareStatement(
                    "INSERT INTO rp_town_state (nombre_ciudad, estado_eleccion) " +
                    "VALUES (?, 'CANDIDATURAS_ABIERTAS') " +
                    "ON DUPLICATE KEY UPDATE estado_eleccion = 'CANDIDATURAS_ABIERTAS'"
            );
            psEstado.setString(1, nombreCiudad);
            psEstado.executeUpdate();

            return true;
        }).thenApply(exito -> {
            if (exito) {
                // 4. Actualizar el estado en memoria y resetear votos en memoria
                estadoEleccion = EstadoEleccion.CANDIDATURAS_ABIERTAS;
                votosActuales.clear();
                votantesActuales.clear();

                // 5. Notificar a todos los jugadores en línea (en el hilo principal)
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    String mensajeEleccion = "§6§l[ELECCIÓN MUNICIPAL] §e¡Se han abierto las candidaturas para " +
                            "el nuevo Alcalde de §6" + nombreCiudad + "§e!";
                    Bukkit.broadcastMessage(mensajeEleccion);
                    Bukkit.broadcastMessage("§7Usa §f/candidatarme §7para postularte como candidato.");
                });

                plugin.getLogger().info(String.format(
                        "[TownManager] Elección iniciada en '%s' por %s.", nombreCiudad, iniciadorId
                ));
            }
            return exito;
        });
    }

    /**
     * Finaliza la elección activa, cuenta los votos y proclama al ganador.
     * <p>
     * Este método:
     * <ol>
     *   <li>Determina el candidato con más votos.</li>
     *   <li>Actualiza el rol de Alcalde en la base de datos.</li>
     *   <li>Resetea el estado electoral.</li>
     *   <li>Notifica el resultado a toda la ciudad.</li>
     * </ol>
     * </p>
     *
     * @return CompletableFuture con el resultado de la elección
     */
    public CompletableFuture<ElectionResult> finalizarEleccion() {
        if (estadoEleccion == EstadoEleccion.SIN_ELECCION) {
            return CompletableFuture.completedFuture(
                    ElectionResult.sinParticipantes(nombreCiudad)
            );
        }

        // Determinar el ganador en memoria
        UUID ganadorId = null;
        int maxVotos   = 0;

        for (Map.Entry<UUID, Integer> entrada : votosActuales.entrySet()) {
            if (entrada.getValue() > maxVotos) {
                maxVotos  = entrada.getValue();
                ganadorId = entrada.getKey();
            }
        }

        if (ganadorId == null) {
            // Nadie votó: elección nula
            estadoEleccion = EstadoEleccion.SIN_ELECCION;
            votosActuales.clear();
            votantesActuales.clear();
            return CompletableFuture.completedFuture(
                    ElectionResult.sinParticipantes(nombreCiudad)
            );
        }

        // Capturar valores finales para uso en la lambda
        final UUID ganadorFinal   = ganadorId;
        final int  votosFinal     = maxVotos;
        final int  totalVotos     = votantesActuales.size();
        final Map<UUID, Integer> historial = new HashMap<>(votosActuales);

        // Obtener el nombre del ganador
        Player jugadorGanador = Bukkit.getPlayer(ganadorFinal);
        final String nombreGanador = jugadorGanador != null
                ? jugadorGanador.getName()
                : "Jugador " + ganadorFinal.toString().substring(0, 8);

        return db.executeTransaction(conexion -> {
            // Actualizar el alcalde en la base de datos
            PreparedStatement psAlcalde = conexion.prepareStatement(
                    "INSERT INTO rp_town_state (nombre_ciudad, alcalde_uuid, alcalde_nombre, estado_eleccion) " +
                    "VALUES (?, ?, ?, 'SIN_ELECCION') " +
                    "ON DUPLICATE KEY UPDATE alcalde_uuid = ?, alcalde_nombre = ?, estado_eleccion = 'SIN_ELECCION'"
            );
            psAlcalde.setString(1, nombreCiudad);
            psAlcalde.setString(2, ganadorFinal.toString());
            psAlcalde.setString(3, nombreGanador);
            psAlcalde.setString(4, ganadorFinal.toString());
            psAlcalde.setString(5, nombreGanador);
            psAlcalde.executeUpdate();

            // Registrar el resultado en el historial electoral
            PreparedStatement psResultado = conexion.prepareStatement(
                    "UPDATE rp_elections SET estado = 'FINALIZADA', ganador_uuid = ?, ganador_nombre = ?, " +
                    "total_votos = ?, fin = NOW() " +
                    "WHERE nombre_ciudad = ? AND estado != 'FINALIZADA' ORDER BY inicio DESC LIMIT 1"
            );
            psResultado.setString(1, ganadorFinal.toString());
            psResultado.setString(2, nombreGanador);
            psResultado.setInt(3, totalVotos);
            psResultado.setString(4, nombreCiudad);
            psResultado.executeUpdate();

            // Construir el objeto resultado
            return new ElectionResult(
                    UUID.randomUUID(),
                    ganadorFinal,
                    nombreGanador,
                    votosFinal,
                    totalVotos,
                    historial,
                    java.time.Instant.now(),
                    nombreCiudad
            );

        }).thenApply(resultado -> {
            // Actualizar estado en memoria
            estadoEleccion       = EstadoEleccion.SIN_ELECCION;
            alcaldeActualId      = resultado.ganadorId();
            alcaldeActualNombre  = resultado.nombreGanador();
            votosActuales.clear();
            votantesActuales.clear();

            // Proclamar el ganador a todos los jugadores (hilo principal)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage("§6§l[ELECCIÓN MUNICIPAL] §e¡Resultados oficiales de " + nombreCiudad + "!");
                Bukkit.broadcastMessage(String.format(
                        "§6Nuevo Alcalde: §e%s §7(%.1f%% de los votos)",
                        resultado.nombreGanador(), resultado.porcentajeVictoria()
                ));
                Bukkit.broadcastMessage("§7¡Felicitaciones al nuevo líder de la ciudad!");
            });

            plugin.getLogger().info(String.format(
                    "[TownManager] Elección finalizada en '%s'. Ganador: %s con %d/%d votos.",
                    nombreCiudad, nombreGanador, votosFinal, totalVotos
            ));

            return resultado;
        });
    }

    // -----------------------------------------------------------------------
    // Gestión de CityFlags (Constitución)
    // -----------------------------------------------------------------------

    /**
     * Modifica un CityFlag de la ciudad.
     * <p>
     * Solo puede ser ejecutado por el Alcalde actual o un administrador.
     * El cambio se persiste en la base de datos y se notifica a todos los jugadores.
     * </p>
     *
     * @param flag       El flag de ciudad a modificar
     * @param nuevoValor El nuevo valor del flag
     * @param alcaldeId  UUID del alcalde que realiza el cambio
     * @return CompletableFuture con true si el cambio fue exitoso
     */
    public CompletableFuture<Boolean> setCityFlag(CityFlag flag, boolean nuevoValor, UUID alcaldeId) {
        // Verificar que quien ejecuta sea el alcalde actual
        if (!alcaldeId.equals(alcaldeActualId)) {
            plugin.getLogger().warning(String.format(
                    "[TownManager] %s intentó cambiar CityFlag sin ser alcalde.", alcaldeId
            ));
            return CompletableFuture.completedFuture(false);
        }

        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "INSERT INTO rp_city_flags (nombre_ciudad, flag_nombre, flag_valor) " +
                    "VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE flag_valor = ?"
            );
            ps.setString(1, nombreCiudad);
            ps.setString(2, flag.name());
            ps.setBoolean(3, nuevoValor);
            ps.setBoolean(4, nuevoValor);
            ps.executeUpdate();

            return true;
        }).thenApply(exito -> {
            if (exito) {
                // Actualizar el estado en memoria
                cityFlags.put(flag, nuevoValor);

                // Notificar el cambio a todos los jugadores
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    String estado = nuevoValor ? "§aACTIVADO" : "§cDESACTIVADO";
                    Bukkit.broadcastMessage(String.format(
                            "§6[ALCALDÍA] §eLey actualizada: §f%s → %s",
                            flag.getNombre(), estado
                    ));
                });
            }
            return exito;
        });
    }

    /**
     * Obtiene el valor actual de un CityFlag.
     *
     * @param flag El flag a consultar
     * @return Valor actual del flag (usa el valor por defecto si no está cargado)
     */
    public boolean getCityFlag(CityFlag flag) {
        return cityFlags.getOrDefault(flag, flag.getValorPorDefecto());
    }

    // -----------------------------------------------------------------------
    // Sistema de votos
    // -----------------------------------------------------------------------

    /**
     * Registra el voto de un jugador por un candidato.
     *
     * @param votanteId    UUID del jugador que vota
     * @param candidatoId  UUID del candidato elegido
     * @return true si el voto fue registrado, false si ya había votado
     */
    public boolean registrarVoto(UUID votanteId, UUID candidatoId) {
        if (estadoEleccion != EstadoEleccion.VOTACION_ACTIVA) {
            return false;
        }

        // Evitar votos duplicados
        if (votantesActuales.containsKey(votanteId)) {
            return false;
        }

        // Registrar voto en memoria
        votantesActuales.put(votanteId, candidatoId);
        votosActuales.merge(candidatoId, 1, Integer::sum);

        return true;
    }

    // -----------------------------------------------------------------------
    // Carga de estado desde la base de datos
    // -----------------------------------------------------------------------

    /**
     * Carga el estado de la ciudad (flags, alcalde, elección) desde la base de datos.
     * Llamado durante la inicialización del TownManager.
     */
    private void cargarEstadoDesdeDB() {
        db.executeTransaction(conexion -> {
            // Cargar estado de la ciudad
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT * FROM rp_town_state WHERE nombre_ciudad = ? LIMIT 1"
            );
            ps.setString(1, nombreCiudad);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String alcaldeUUID = rs.getString("alcalde_uuid");
                if (alcaldeUUID != null) {
                    alcaldeActualId     = UUID.fromString(alcaldeUUID);
                    alcaldeActualNombre = rs.getString("alcalde_nombre");
                }

                try {
                    estadoEleccion = EstadoEleccion.valueOf(rs.getString("estado_eleccion"));
                } catch (IllegalArgumentException e) {
                    estadoEleccion = EstadoEleccion.SIN_ELECCION;
                }
            }

            // Cargar CityFlags desde la DB
            PreparedStatement psFlags = conexion.prepareStatement(
                    "SELECT flag_nombre, flag_valor FROM rp_city_flags WHERE nombre_ciudad = ?"
            );
            psFlags.setString(1, nombreCiudad);
            ResultSet rsFlags = psFlags.executeQuery();

            while (rsFlags.next()) {
                try {
                    CityFlag flag = CityFlag.valueOf(rsFlags.getString("flag_nombre"));
                    cityFlags.put(flag, rsFlags.getBoolean("flag_valor"));
                } catch (IllegalArgumentException e) {
                    // Flag desconocido (puede ocurrir en migraciones); ignorar
                }
            }

            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "[TownManager] No se pudo cargar el estado de la ciudad desde la DB (¿es la primera vez?): "
                    + error.getMessage());
            return null;
        });
    }

    /**
     * Retorna el nombre del alcalde actual.
     *
     * @return Nombre del alcalde o "Sin alcalde" si no hay ninguno elegido
     */
    public String getNombreAlcalde() {
        return alcaldeActualNombre != null ? alcaldeActualNombre : "Sin alcalde";
    }

    /**
     * Retorna el estado actual del proceso electoral.
     *
     * @return Estado de la elección
     */
    public EstadoEleccion getEstadoEleccion() {
        return estadoEleccion;
    }
}
