package com.soyjuanpiece.roleplaycore.player;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;
import com.soyjuanpiece.roleplaycore.economy.EconomyManager;
import com.soyjuanpiece.roleplaycore.model.PaydayResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Perfil de jugador del sistema RoleplayCore.
 * <p>
 * Gestiona los datos persistentes de cada jugador:
 * <ul>
 *   <li>Salario y trabajo actual.</li>
 *   <li>Procesamiento del Payday (salario menos impuestos).</li>
 *   <li>Historial de transacciones.</li>
 *   <li>Registro de herencias y testamentos.</li>
 * </ul>
 * </p>
 */
public class PlayerProfile {

    /** Referencia al plugin principal */
    private final RoleplayCore plugin;

    /** Conector de base de datos */
    private final DatabaseConnector db;

    /** Gestor de economía para aplicar cobros y descuentos */
    private final EconomyManager economy;

    // -----------------------------------------------------------------------
    // Constantes del sistema Payday
    // -----------------------------------------------------------------------

    /** Salario base para jugadores sin empleo asignado */
    private static final double SALARIO_BASE_DESEMPLEADO = 150.0;

    /** Porcentaje mínimo garantizado al jugador después de impuestos */
    private static final double PORCENTAJE_MINIMO_NETO = 0.60;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo PlayerProfile.
     *
     * @param plugin  Instancia del plugin principal
     * @param db      Conector de base de datos
     * @param economy Gestor de economía
     */
    public PlayerProfile(RoleplayCore plugin, DatabaseConnector db, EconomyManager economy) {
        this.plugin  = plugin;
        this.db      = db;
        this.economy = economy;
    }

    // -----------------------------------------------------------------------
    // Función principal: handlePayday (Procesamiento masivo de salarios)
    // -----------------------------------------------------------------------

    /**
     * Procesa el ciclo de pago (Payday) para <b>todos los jugadores en línea</b>.
     * <p>
     * Para cada jugador conectado:
     * <ol>
     *   <li>Obtiene su salario bruto según el empleo registrado en la DB.</li>
     *   <li>Calcula el porcentaje de impuesto progresivo según su riqueza.</li>
     *   <li>Aplica el descuento de impuestos y transfiere el salario neto.</li>
     *   <li>El monto del impuesto se deposita al fondo estatal (para subsidios).</li>
     *   <li>Notifica al jugador del cobro mediante mensaje en el chat.</li>
     * </ol>
     * El procesamiento es <b>asíncrono y en paralelo</b> para todos los jugadores,
     * usando {@link CompletableFuture#allOf(CompletableFuture[])} para esperar
     * a que todos finalicen antes de reportar el resultado masivo.
     * </p>
     *
     * @return CompletableFuture con la lista de resultados de pago de cada jugador
     */
    public CompletableFuture<List<PaydayResult>> handlePayday() {
        plugin.getLogger().info("[PlayerProfile] Iniciando ciclo Payday para todos los jugadores en línea...");

        // Obtener lista de jugadores en línea (captura en el hilo actual)
        List<Player> jugadoresEnLinea = new ArrayList<>(Bukkit.getOnlinePlayers());

        if (jugadoresEnLinea.isEmpty()) {
            plugin.getLogger().info("[PlayerProfile] Payday ejecutado: no hay jugadores en línea.");
            return CompletableFuture.completedFuture(List.of());
        }

        // Procesar el pago de cada jugador de forma asíncrona e independiente
        List<CompletableFuture<PaydayResult>> futurosPago = jugadoresEnLinea.stream()
                .map(jugador -> procesarPagoIndividual(jugador.getUniqueId(), jugador.getName()))
                .toList();

        // Esperar a que TODOS los pagos finalicen antes de retornar
        return CompletableFuture.allOf(futurosPago.toArray(new CompletableFuture[0]))
                .thenApply(ignorado -> {
                    // Recopilar todos los resultados
                    List<PaydayResult> resultados = futurosPago.stream()
                            .map(futuro -> {
                                try {
                                    return futuro.join();
                                } catch (Exception e) {
                                    // Si un pago individual falla, continuar con los demás
                                    plugin.getLogger().log(Level.WARNING,
                                            "[PlayerProfile] Error en pago individual durante Payday.", e);
                                    return null;
                                }
                            })
                            .filter(resultado -> resultado != null)
                            .toList();

                    // Calcular estadísticas del ciclo Payday
                    double totalPagado    = resultados.stream().mapToDouble(PaydayResult::salarioNeto).sum();
                    double totalImpuestos = resultados.stream().mapToDouble(PaydayResult::impuestos).sum();

                    plugin.getLogger().info(String.format(
                            "[PlayerProfile] Payday completado: %d jugadores pagados | Total neto: $%.2f | Impuestos recaudados: $%.2f",
                            resultados.size(), totalPagado, totalImpuestos
                    ));

                    return resultados;
                });
    }

    /**
     * Procesa el pago individual de un jugador durante el ciclo Payday.
     * <p>
     * Obtiene el salario del trabajo del jugador desde la DB, calcula los
     * impuestos progresivos y acredita el salario neto al banco del jugador.
     * El impuesto se deposita automáticamente al fondo estatal.
     * </p>
     *
     * @param jugadorId     UUID del jugador a pagar
     * @param nombreJugador Nombre del jugador (para logs y mensajes)
     * @return CompletableFuture con el resultado del pago
     */
    private CompletableFuture<PaydayResult> procesarPagoIndividual(UUID jugadorId, String nombreJugador) {
        return db.executeTransaction(conexion -> {
            // 1. Obtener los datos de empleo y saldo bancario del jugador
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT p.bank_balance, j.salario_base, j.nombre_trabajo " +
                    "FROM rp_players p " +
                    "LEFT JOIN rp_jobs j ON p.trabajo_id = j.id " +
                    "WHERE p.uuid = ? " +
                    "FOR UPDATE"  // Bloqueo optimista para evitar condiciones de carrera
            );
            ps.setString(1, jugadorId.toString());
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                // El jugador no está registrado: crear registro básico
                plugin.getLogger().warning("[PlayerProfile] Jugador " + nombreJugador + " no encontrado en DB. Saltando Payday.");
                return PaydayResult.fallido(jugadorId);
            }

            double saldoBancario  = rs.getDouble("bank_balance");
            double salarioBruto   = rs.wasNull() || rs.getDouble("salario_base") == 0
                    ? SALARIO_BASE_DESEMPLEADO      // Jugador sin trabajo: salario mínimo
                    : rs.getDouble("salario_base");

            // 2. Calcular el porcentaje de impuesto progresivo
            // Usamos una variable final para permitir su uso dentro de lambdas
            final double porcentajeImpuesto = Math.min(
                    economy.calcularPorcentajeImpuesto(saldoBancario),
                    1.0 - PORCENTAJE_MINIMO_NETO
            );

            final double montoImpuesto  = salarioBruto * porcentajeImpuesto;
            final double salarioNeto    = salarioBruto - montoImpuesto;

            // 3. Acreditar el salario neto al banco del jugador
            PreparedStatement psAcreditar = conexion.prepareStatement(
                    "UPDATE rp_players SET bank_balance = bank_balance + ? WHERE uuid = ?"
            );
            psAcreditar.setDouble(1, salarioNeto);
            psAcreditar.setString(2, jugadorId.toString());
            psAcreditar.executeUpdate();

            // 4. Depositar los impuestos al fondo estatal
            PreparedStatement psFondo = conexion.prepareStatement(
                    "UPDATE rp_state_fund SET saldo = saldo + ? WHERE id = 1"
            );
            psFondo.setDouble(1, montoImpuesto);
            psFondo.executeUpdate();

            // 5. Registrar la transacción de Payday en el log de auditoría
            PreparedStatement psLog = conexion.prepareStatement(
                    "INSERT INTO rp_transactions (emisor_uuid, receptor_uuid, monto, tipo, timestamp) " +
                    "VALUES ('ESTADO', ?, ?, 'PAYDAY', NOW())"
            );
            psLog.setString(1, jugadorId.toString());
            psLog.setDouble(2, salarioNeto);
            psLog.executeUpdate();

            // 6. Construir el resultado del pago
            PaydayResult resultado = new PaydayResult(
                    jugadorId,
                    salarioBruto,
                    montoImpuesto,
                    salarioNeto,
                    porcentajeImpuesto,
                    Instant.now(),
                    true
            );

            // 7. Notificar al jugador en el hilo principal (si está en línea)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player jugadorEnLinea = Bukkit.getPlayer(jugadorId);
                if (jugadorEnLinea != null && jugadorEnLinea.isOnline()) {
                    jugadorEnLinea.sendMessage("§a[PAYDAY] §fHas recibido tu salario:");
                    jugadorEnLinea.sendMessage(String.format(
                            "§7Salario bruto: §e$%.2f §7| Impuestos (§c%.0f%%§7): §c-$%.2f §7| §fNeto: §a$%.2f",
                            salarioBruto, porcentajeImpuesto * 100, montoImpuesto, salarioNeto
                    ));
                }
            });

            // Invalidar caché del jugador para reflejar el nuevo saldo
            economy.limpiarCacheJugador(jugadorId);

            return resultado;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "[PlayerProfile] Error en Payday para jugador " + nombreJugador, error);
            return PaydayResult.fallido(jugadorId);
        });
    }

    // -----------------------------------------------------------------------
    // Gestión de perfiles
    // -----------------------------------------------------------------------

    /**
     * Crea el perfil de un nuevo jugador en la base de datos al conectarse por primera vez.
     * <p>
     * Incluye asignación automática de subsidio estatal si está habilitado
     * en la configuración del servidor.
     * </p>
     *
     * @param jugadorId     UUID del nuevo jugador
     * @param nombreJugador Nombre del jugador
     * @return CompletableFuture que completa cuando el perfil fue creado
     */
    public CompletableFuture<Void> crearPerfil(UUID jugadorId, String nombreJugador) {
        return db.executeTransaction(conexion -> {
            // Verificar si ya existe el perfil
            PreparedStatement psVerificar = conexion.prepareStatement(
                    "SELECT COUNT(*) FROM rp_players WHERE uuid = ?"
            );
            psVerificar.setString(1, jugadorId.toString());
            ResultSet rs = psVerificar.executeQuery();

            if (rs.next() && rs.getInt(1) > 0) {
                // El perfil ya existe (reconexión)
                return null;
            }

            // Saldo inicial de bienvenida desde la configuración
            double saldoInicial = plugin.getConfig().getDouble("economy.saldo-inicial", 500.0);

            // Insertar perfil nuevo
            PreparedStatement psInsertar = conexion.prepareStatement(
                    "INSERT INTO rp_players (uuid, nombre, bank_balance, cash_balance, dirty_money, trabajo_id, fecha_registro) " +
                    "VALUES (?, ?, ?, 0.0, 0.0, NULL, NOW())"
            );
            psInsertar.setString(1, jugadorId.toString());
            psInsertar.setString(2, nombreJugador);
            psInsertar.setDouble(3, saldoInicial);
            psInsertar.executeUpdate();

            plugin.getLogger().info("[PlayerProfile] Perfil creado para: " + nombreJugador + " (saldo inicial: $" + saldoInicial + ")");
            return null;
        });
    }

    /**
     * Verifica si un jugador ha estado inactivo más de N días.
     * <p>
     * Usado por el sistema de herencias para detectar jugadores inactivos
     * y subastar sus propiedades automáticamente.
     * </p>
     *
     * @param jugadorId UUID del jugador
     * @param diasLimite Número de días de inactividad para considerar al jugador "inactivo"
     * @return CompletableFuture con true si el jugador ha estado inactivo por más de N días
     */
    public CompletableFuture<Boolean> esInactivo(UUID jugadorId, int diasLimite) {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT DATEDIFF(NOW(), ultima_conexion) AS dias_inactivo " +
                    "FROM rp_players WHERE uuid = ? LIMIT 1"
            );
            ps.setString(1, jugadorId.toString());
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) return false;

            int diasInactivo = rs.getInt("dias_inactivo");
            return diasInactivo >= diasLimite;
        });
    }

    /**
     * Actualiza la última conexión del jugador al conectarse.
     * Usado para el sistema de detección de inactividad (herencias).
     *
     * @param jugadorId UUID del jugador
     * @return CompletableFuture que completa cuando se actualizó la fecha
     */
    public CompletableFuture<Void> actualizarUltimaConexion(UUID jugadorId) {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE rp_players SET ultima_conexion = NOW() WHERE uuid = ?"
            );
            ps.setString(1, jugadorId.toString());
            ps.executeUpdate();
            return null;
        });
    }
}
