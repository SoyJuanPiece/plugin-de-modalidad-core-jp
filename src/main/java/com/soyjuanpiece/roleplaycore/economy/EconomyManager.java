package com.soyjuanpiece.roleplaycore.economy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soyjuanpiece.roleplaycore.RoleplayCore;
import com.soyjuanpiece.roleplaycore.database.DatabaseConnector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Gestor de economía del servidor RoleplayCore.
 * <p>
 * Responsable de:
 * <ul>
 *   <li>Gestionar saldos bancarios (BankBalance) y dinero efectivo (CashBalance).</li>
 *   <li>Procesar transferencias atómicas entre jugadores.</li>
 *   <li>Calcular y distribuir impuestos al fondo estatal.</li>
 *   <li>Gestionar dinero sucio (obtenido de crímenes, sin lavar).</li>
 * </ul>
 * Utiliza caché <b>Caffeine</b> para perfiles de saldo con expiración automática,
 * reduciendo las consultas a la base de datos en lecturas frecuentes.
 * </p>
 */
public class EconomyManager {

    /** Referencia al plugin principal */
    private final RoleplayCore plugin;

    /** Conector de base de datos para operaciones persistentes */
    private final DatabaseConnector db;

    // -----------------------------------------------------------------------
    // Caché Caffeine para saldos de jugadores
    // -----------------------------------------------------------------------

    /**
     * Caché de saldo bancario por UUID de jugador.
     * Tiempo de expiración tras el último acceso: 10 minutos.
     * Capacidad máxima: 500 entradas (jugadores en caché simultáneamente).
     */
    private final Cache<UUID, Double> cacheBankBalance;

    /**
     * Caché de saldo en efectivo por UUID de jugador.
     * El efectivo cambia con más frecuencia, por eso tiene expiración más corta.
     */
    private final Cache<UUID, Double> cacheCashBalance;

    /**
     * Caché de dinero sucio por UUID de jugador.
     * Dinero obtenido de crímenes que aún no ha sido lavado en el banco.
     */
    private final Cache<UUID, Double> cacheDineroSucio;

    // -----------------------------------------------------------------------
    // Constantes de impuestos
    // -----------------------------------------------------------------------

    /** Porcentaje de impuesto base aplicado a jugadores nuevos (bajo) */
    private static final double IMPUESTO_BASE = 0.05;

    /** Porcentaje máximo de impuesto para los jugadores más ricos */
    private static final double IMPUESTO_MAXIMO = 0.35;

    /** Umbral de riqueza (saldo bancario) para considerar a un jugador "rico" */
    private static final double UMBRAL_RICO = 100_000.0;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Crea un nuevo EconomyManager e inicializa los cachés Caffeine.
     *
     * @param plugin Instancia del plugin principal
     * @param db     Conector de base de datos
     */
    public EconomyManager(RoleplayCore plugin, DatabaseConnector db) {
        this.plugin = plugin;
        this.db     = db;

        // Inicializar caché Caffeine para BankBalance
        this.cacheBankBalance = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats() // Habilitar estadísticas de caché para monitoreo
                .build();

        // Inicializar caché para CashBalance (expiración más corta: 5 minutos)
        this.cacheCashBalance = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .build();

        // Inicializar caché para dinero sucio (expiración: 10 minutos)
        this.cacheDineroSucio = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    // -----------------------------------------------------------------------
    // Operaciones de saldo
    // -----------------------------------------------------------------------

    /**
     * Obtiene el saldo bancario de un jugador de forma asíncrona.
     * <p>
     * Primero consulta la caché Caffeine; si no existe, realiza una consulta a la DB.
     * </p>
     *
     * @param jugadorId UUID del jugador
     * @return CompletableFuture con el saldo bancario (0.0 si no existe)
     */
    public CompletableFuture<Double> obtenerBankBalance(UUID jugadorId) {
        // Intentar obtener de caché primero
        Double saldoCacheado = cacheBankBalance.getIfPresent(jugadorId);
        if (saldoCacheado != null) {
            return CompletableFuture.completedFuture(saldoCacheado);
        }

        // Consultar base de datos si no está en caché
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "SELECT bank_balance FROM rp_players WHERE uuid = ? LIMIT 1"
            );
            ps.setString(1, jugadorId.toString());
            ResultSet rs = ps.executeQuery();

            double saldo = rs.next() ? rs.getDouble("bank_balance") : 0.0;

            // Almacenar en caché para futuras consultas
            cacheBankBalance.put(jugadorId, saldo);
            return saldo;
        });
    }

    /**
     * Realiza una transferencia de dinero entre dos jugadores de forma atómica.
     * <p>
     * La operación es atómica (ACID): si cualquier parte falla, se revierte
     * completamente. El registro en {@code rp_transactions} también es parte
     * de la transacción para garantizar la trazabilidad.
     * </p>
     *
     * @param emisorId    UUID del jugador que envía el dinero
     * @param receptorId  UUID del jugador que recibe el dinero
     * @param monto       Cantidad a transferir (debe ser positivo)
     * @return CompletableFuture con true si la transferencia fue exitosa
     */
    public CompletableFuture<Boolean> transferirDinero(UUID emisorId, UUID receptorId, double monto) {
        if (monto <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        return db.executeTransaction(conexion -> {
            // 1. Verificar que el emisor tiene saldo suficiente (con bloqueo de fila)
            PreparedStatement psVerificar = conexion.prepareStatement(
                    "SELECT bank_balance FROM rp_players WHERE uuid = ? FOR UPDATE"
            );
            psVerificar.setString(1, emisorId.toString());
            ResultSet rsEmisor = psVerificar.executeQuery();

            if (!rsEmisor.next()) {
                throw new IllegalStateException("El jugador emisor no existe en la base de datos.");
            }

            double saldoEmisor = rsEmisor.getDouble("bank_balance");
            if (saldoEmisor < monto) {
                throw new IllegalStateException("Saldo insuficiente para realizar la transferencia.");
            }

            // 2. Descontar del emisor
            PreparedStatement psDescontar = conexion.prepareStatement(
                    "UPDATE rp_players SET bank_balance = bank_balance - ? WHERE uuid = ?"
            );
            psDescontar.setDouble(1, monto);
            psDescontar.setString(2, emisorId.toString());
            psDescontar.executeUpdate();

            // 3. Acreditar al receptor
            PreparedStatement psAcreditar = conexion.prepareStatement(
                    "UPDATE rp_players SET bank_balance = bank_balance + ? WHERE uuid = ?"
            );
            psAcreditar.setDouble(1, monto);
            psAcreditar.setString(2, receptorId.toString());
            psAcreditar.executeUpdate();

            // 4. Registrar en el log de transacciones para auditoría
            PreparedStatement psLog = conexion.prepareStatement(
                    "INSERT INTO rp_transactions (emisor_uuid, receptor_uuid, monto, tipo, timestamp) " +
                    "VALUES (?, ?, ?, 'TRANSFERENCIA', NOW())"
            );
            psLog.setString(1, emisorId.toString());
            psLog.setString(2, receptorId.toString());
            psLog.setDouble(3, monto);
            psLog.executeUpdate();

            // 5. Invalidar caché de ambos jugadores para forzar recarga
            cacheBankBalance.invalidate(emisorId);
            cacheBankBalance.invalidate(receptorId);

            return true;
        });
    }

    /**
     * Agrega dinero sucio al perfil del jugador (producto de un crimen).
     * <p>
     * El dinero sucio no puede usarse directamente para compras normales.
     * Debe ser "lavado" en el banco perdiendo el 25% en el proceso.
     * </p>
     *
     * @param jugadorId UUID del jugador criminal
     * @param monto     Cantidad de dinero sucio a agregar
     * @return CompletableFuture que completa cuando se ha guardado el cambio
     */
    public CompletableFuture<Void> agregarDineroSucio(UUID jugadorId, double monto) {
        return db.executeTransaction(conexion -> {
            PreparedStatement ps = conexion.prepareStatement(
                    "UPDATE rp_players SET dirty_money = dirty_money + ? WHERE uuid = ?"
            );
            ps.setDouble(1, monto);
            ps.setString(2, jugadorId.toString());
            ps.executeUpdate();

            // Invalidar caché de dinero sucio
            cacheDineroSucio.invalidate(jugadorId);
            return null;
        });
    }

    /**
     * Lava dinero sucio del jugador, aplicando una comisión del 25%.
     * <p>
     * El monto neto (75%) se acredita al BankBalance del jugador.
     * Esta operación es atómica: si falla, el dinero sucio no se pierde.
     * </p>
     *
     * @param jugadorId UUID del jugador que lava dinero
     * @param montoSucio Cantidad de dinero sucio a lavar
     * @return CompletableFuture con el monto neto acreditado al banco
     */
    public CompletableFuture<Double> lavarDinero(UUID jugadorId, double montoSucio) {
        // La comisión de lavado es del 25%
        double comision  = montoSucio * 0.25;
        double montoNeto = montoSucio - comision;

        return db.executeTransaction(conexion -> {
            // Verificar que el jugador tiene suficiente dinero sucio
            PreparedStatement psVerificar = conexion.prepareStatement(
                    "SELECT dirty_money FROM rp_players WHERE uuid = ? FOR UPDATE"
            );
            psVerificar.setString(1, jugadorId.toString());
            ResultSet rs = psVerificar.executeQuery();

            if (!rs.next() || rs.getDouble("dirty_money") < montoSucio) {
                throw new IllegalStateException("Dinero sucio insuficiente para lavar.");
            }

            // Descontar el dinero sucio
            PreparedStatement psDescontar = conexion.prepareStatement(
                    "UPDATE rp_players SET dirty_money = dirty_money - ? WHERE uuid = ?"
            );
            psDescontar.setDouble(1, montoSucio);
            psDescontar.setString(2, jugadorId.toString());
            psDescontar.executeUpdate();

            // Acreditar el 75% al banco
            PreparedStatement psAcreditar = conexion.prepareStatement(
                    "UPDATE rp_players SET bank_balance = bank_balance + ? WHERE uuid = ?"
            );
            psAcreditar.setDouble(1, montoNeto);
            psAcreditar.setString(2, jugadorId.toString());
            psAcreditar.executeUpdate();

            // Registrar la operación de lavado en el log de transacciones
            PreparedStatement psLog = conexion.prepareStatement(
                    "INSERT INTO rp_transactions (emisor_uuid, receptor_uuid, monto, tipo, timestamp) " +
                    "VALUES (?, 'BANK', ?, 'LAVADO', NOW())"
            );
            psLog.setString(1, jugadorId.toString());
            psLog.setDouble(2, montoNeto);
            psLog.executeUpdate();

            // Invalidar cachés
            cacheDineroSucio.invalidate(jugadorId);
            cacheBankBalance.invalidate(jugadorId);

            return montoNeto;
        });
    }

    /**
     * Calcula el porcentaje de impuesto correspondiente a un jugador
     * basándose en su saldo bancario (sistema impositivo progresivo).
     * <p>
     * Usa Pattern Matching de Java 21 con expresiones switch para determinar
     * el nivel impositivo de forma clara y concisa.
     * </p>
     *
     * @param saldoBancario Saldo bancario actual del jugador
     * @return Porcentaje de impuesto como valor entre 0.0 y 1.0
     */
    public double calcularPorcentajeImpuesto(double saldoBancario) {
        // Pattern Matching en switch (Java 21): impuesto progresivo por tramos
        return switch ((int) Math.floor(saldoBancario / 10_000)) {
            case 0      -> IMPUESTO_BASE;             // < $10,000: 5%
            case 1      -> 0.08;                       // $10k - $20k: 8%
            case 2, 3   -> 0.12;                       // $20k - $40k: 12%
            case 4, 5   -> 0.18;                       // $40k - $60k: 18%
            case 6, 7, 8, 9 -> 0.25;                   // $60k - $100k: 25%
            default     -> IMPUESTO_MAXIMO;            // > $100k: 35%
        };
    }

    /**
     * Invalida las entradas de caché de un jugador al desconectarse.
     * Debe llamarse desde el evento {@code PlayerQuitEvent}.
     *
     * @param jugadorId UUID del jugador que se desconectó
     */
    public void limpiarCacheJugador(UUID jugadorId) {
        cacheBankBalance.invalidate(jugadorId);
        cacheCashBalance.invalidate(jugadorId);
        cacheDineroSucio.invalidate(jugadorId);
    }

    /**
     * Registra en el log las estadísticas de uso de la caché.
     * Útil para monitoreo y optimización en producción.
     */
    public void logEstadisticasCache() {
        plugin.getLogger().info(String.format(
                "[EconomyManager] Caché BankBalance → %s",
                cacheBankBalance.stats().toString()
        ));
    }
}
