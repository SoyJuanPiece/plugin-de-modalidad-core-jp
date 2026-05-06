package com.soyjuanpiece.roleplaycore.crime;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.model.EvidenceRecord;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema forense del motor de crimen para RoleplayCore.
 * <p>
 * Responsable de:
 * <ul>
 *   <li>Crear y persistir rastros de ADN en la escena de crímenes.</li>
 *   <li>Mostrar partículas invisibles para civiles (solo visibles para policías).</li>
 *   <li>Gestionar la expiración de evidencias.</li>
 *   <li>Permitir que policías con el ítem "Scanner" detecten evidencias.</li>
 * </ul>
 * </p>
 */
public class CrimeSystem {

    /** Referencia al plugin principal */
    private final RoleplayCore plugin;

    /** Conector de base de datos para persistencia de evidencias */
    private final DatabaseConnector db;

    /**
     * Mapa en memoria de evidencias activas: UUID de evidencia → EvidenceRecord.
     * ConcurrentHashMap garantiza seguridad en accesos concurrentes desde múltiples hilos.
     */
    private final Map<UUID, EvidenceRecord> evidenciasActivas;

    // -----------------------------------------------------------------------
    // Constantes del sistema forense
    // -----------------------------------------------------------------------

    /** Duración en segundos de una evidencia forense antes de destruirse */
    private static final long DURACION_EVIDENCIA_SEGUNDOS = 3_600L; // 1 hora

    /** Intervalo en ticks para la tarea de limpieza de evidencias expiradas (20 ticks = 1 segundo) */
    private static final long INTERVALO_LIMPIEZA_TICKS = 600L; // 30 segundos

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo CrimeSystem e inicia la tarea periódica de limpieza.
     *
     * @param plugin Instancia del plugin principal
     * @param db     Conector de base de datos
     */
    public CrimeSystem(RoleplayCore plugin, DatabaseConnector db) {
        this.plugin           = plugin;
        this.db               = db;
        this.evidenciasActivas = new ConcurrentHashMap<>();

        // Iniciar tarea periódica de limpieza de evidencias expiradas
        iniciarTareaLimpieza();
    }

    // -----------------------------------------------------------------------
    // Función principal: spawnEvidence (Sistema Forense)
    // -----------------------------------------------------------------------

    /**
     * Crea un rastro de ADN en la escena de un crimen.
     * <p>
     * Esta función realiza tres acciones:
     * <ol>
     *   <li>Genera un {@link EvidenceRecord} con los datos del crimen.</li>
     *   <li>Persiste el registro en la tabla {@code rp_crime_evidence} de la DB.</li>
     *   <li>Spawnea partículas (invisibles para civiles) en la ubicación del crimen.</li>
     * </ol>
     * La operación de persistencia es asíncrona para no bloquear el hilo principal.
     * El spawn de partículas se ejecuta en el hilo principal (requerimiento de Bukkit).
     * </p>
     *
     * @param ubicacion      Ubicación exacta de la escena del crimen
     * @param criminalId     UUID del jugador que cometió el crimen
     * @param nombreCriminal Nombre del jugador criminal
     * @param tipoCrimen     Tipo de crimen cometido
     * @return CompletableFuture con el EvidenceRecord creado
     */
    public CompletableFuture<EvidenceRecord> spawnEvidence(
            Location ubicacion,
            UUID criminalId,
            String nombreCriminal,
            CrimeType tipoCrimen
    ) {
        // Crear el registro de evidencia inmutable
        EvidenceRecord evidencia = EvidenceRecord.crear(
                criminalId,
                nombreCriminal,
                tipoCrimen,
                ubicacion,
                DURACION_EVIDENCIA_SEGUNDOS
        );

        // Almacenar en memoria inmediatamente para acceso rápido
        evidenciasActivas.put(evidencia.evidenciaId(), evidencia);

        // Persistir en la base de datos de forma asíncrona
        CompletableFuture<EvidenceRecord> futuro = db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "INSERT INTO rp_crime_evidence " +
                    "(evidencia_id, criminal_uuid, criminal_nombre, tipo_crimen, mundo, coord_x, coord_y, coord_z, timestamp, expira) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL ? SECOND))"
            );
            ps.setString(1, evidencia.evidenciaId().toString());
            ps.setString(2, evidencia.criminalId().toString());
            ps.setString(3, evidencia.nombreCriminal());
            ps.setString(4, evidencia.tipoCrimen().name());
            ps.setString(5, evidencia.mundo());
            ps.setDouble(6, evidencia.coordX());
            ps.setDouble(7, evidencia.coordY());
            ps.setDouble(8, evidencia.coordZ());
            ps.setLong(9, DURACION_EVIDENCIA_SEGUNDOS);
            ps.executeUpdate();

            return evidencia;
        });

        // Spawnear partículas en el hilo principal del servidor (requerimiento de Bukkit)
        futuro.thenRunAsync(
                () -> spawnParticulasForenses(ubicacion),
                runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable)
        );

        plugin.getLogger().info(String.format(
                "[CrimeSystem] Evidencia forense creada: %s cometió %s en (%s, %.1f, %.1f, %.1f)",
                nombreCriminal, tipoCrimen.getNombre(),
                ubicacion.getWorld().getName(),
                ubicacion.getX(), ubicacion.getY(), ubicacion.getZ()
        ));

        return futuro;
    }

    // -----------------------------------------------------------------------
    // Partículas forenses
    // -----------------------------------------------------------------------

    /**
     * Spawnea partículas de ADN en la ubicación del crimen.
     * <p>
     * Las partículas de tipo {@link Particle#DUST} de color rojo oscuro simulan
     * el rastro de ADN. En una implementación completa con ProtocolLib, estas
     * partículas solo serían visibles para jugadores con el permiso
     * {@code roleplaycore.police}.
     * </p>
     * <p>
     * Este método <b>debe ejecutarse en el hilo principal</b> del servidor.
     * </p>
     *
     * @param ubicacion Ubicación donde spawnear las partículas
     */
    private void spawnParticulasForenses(Location ubicacion) {
        World mundo = ubicacion.getWorld();
        if (mundo == null) return;

        // Configuración de la partícula de ADN (polvo de color rojo oscuro)
        Particle.DustOptions opcionesPolvo = new Particle.DustOptions(
                org.bukkit.Color.fromRGB(139, 0, 0), // Rojo sangre oscuro
                1.2f // Tamaño de la partícula
        );

        // Spawnear múltiples partículas en un área pequeña alrededor de la escena
        for (int i = 0; i < 10; i++) {
            double offsetX = (Math.random() - 0.5) * 0.8;
            double offsetZ = (Math.random() - 0.5) * 0.8;
            Location posParticula = ubicacion.clone().add(offsetX, 0.05, offsetZ);
            mundo.spawnParticle(Particle.DUST, posParticula, 1, 0, 0, 0, 0, opcionesPolvo);
        }
    }

    // -----------------------------------------------------------------------
    // Consulta de evidencias (Scanner policial)
    // -----------------------------------------------------------------------

    /**
     * Obtiene todas las evidencias activas cercanas a una ubicación.
     * <p>
     * Usado por el ítem "Scanner" policial para detectar rastros de ADN.
     * Solo devuelve evidencias que no han expirado.
     * </p>
     *
     * @param ubicacion  Ubicación del jugador con el scanner
     * @param radioBlocks Radio de detección en bloques
     * @return Lista de evidencias activas dentro del radio
     */
    public List<EvidenceRecord> obtenerEvidenciasCercanas(Location ubicacion, double radioBlocks) {
        List<EvidenceRecord> cercanas = new ArrayList<>();
        double radioAlCuadrado = radioBlocks * radioBlocks;

        for (EvidenceRecord evidencia : evidenciasActivas.values()) {
            // Solo considerar evidencias en el mismo mundo
            if (!evidencia.mundo().equals(ubicacion.getWorld().getName())) continue;

            // Solo evidencias válidas (no expiradas)
            if (!evidencia.esValida()) continue;

            // Calcular distancia al cuadrado (evita Math.sqrt para rendimiento)
            double dx = evidencia.coordX() - ubicacion.getX();
            double dy = evidencia.coordY() - ubicacion.getY();
            double dz = evidencia.coordZ() - ubicacion.getZ();
            double distanciaAlCuadrado = dx * dx + dy * dy + dz * dz;

            if (distanciaAlCuadrado <= radioAlCuadrado) {
                cercanas.add(evidencia);
            }
        }

        return cercanas;
    }

    // -----------------------------------------------------------------------
    // Tarea de limpieza de evidencias expiradas
    // -----------------------------------------------------------------------

    /**
     * Inicia una tarea periódica asíncrona que elimina evidencias expiradas.
     * <p>
     * La tarea se ejecuta cada {@value #INTERVALO_LIMPIEZA_TICKS} ticks
     * de forma asíncrona para no afectar el rendimiento del servidor.
     * </p>
     */
    private void iniciarTareaLimpieza() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int eliminadas = 0;
                List<UUID> aEliminar = new ArrayList<>();

                // Identificar evidencias expiradas
                for (Map.Entry<UUID, EvidenceRecord> entrada : evidenciasActivas.entrySet()) {
                    if (!entrada.getValue().esValida()) {
                        aEliminar.add(entrada.getKey());
                    }
                }

                // Eliminar de memoria
                for (UUID id : aEliminar) {
                    evidenciasActivas.remove(id);
                    eliminadas++;
                }

                if (eliminadas > 0) {
                    plugin.getLogger().info(String.format(
                            "[CrimeSystem] Limpieza completada: %d evidencias expiradas eliminadas.", eliminadas
                    ));
                }
            }
        }.runTaskTimerAsynchronously(plugin, INTERVALO_LIMPIEZA_TICKS, INTERVALO_LIMPIEZA_TICKS);
    }

    /**
     * Carga las evidencias activas desde la base de datos al iniciar el servidor.
     * <p>
     * Evita perder evidencias forenses si el servidor se reinicia.
     * </p>
     *
     * @return CompletableFuture con el número de evidencias cargadas
     */
    public CompletableFuture<Integer> cargarEvidenciasDesdeDB() {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT * FROM rp_crime_evidence WHERE expira > NOW()"
            );
            ResultSet rs = ps.executeQuery();

            int cargadas = 0;
            while (rs.next()) {
                UUID evidenciaId = UUID.fromString(rs.getString("evidencia_id"));
                UUID criminalId  = UUID.fromString(rs.getString("criminal_uuid"));
                CrimeType tipo   = CrimeType.valueOf(rs.getString("tipo_crimen"));

                // Reconstruir la evidencia desde la base de datos
                // (No podemos reconstruir un Location sin el objeto World aquí,
                //  así que guardamos los datos crudos y los usamos cuando se necesite)
                EvidenceRecord evidencia = new EvidenceRecord(
                        evidenciaId,
                        criminalId,
                        rs.getString("criminal_nombre"),
                        tipo,
                        rs.getString("mundo"),
                        rs.getDouble("coord_x"),
                        rs.getDouble("coord_y"),
                        rs.getDouble("coord_z"),
                        rs.getTimestamp("timestamp").toInstant(),
                        rs.getTimestamp("expira").toInstant()
                );

                evidenciasActivas.put(evidenciaId, evidencia);
                cargadas++;
            }

            return cargadas;
        });
    }

    /**
     * Retorna el número total de evidencias activas en memoria.
     *
     * @return número de evidencias actualmente monitoreadas
     */
    public int getEvidenciasActivas() {
        return evidenciasActivas.size();
    }
}
