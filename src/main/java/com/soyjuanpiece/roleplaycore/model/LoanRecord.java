package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de un préstamo bancario.
 *
 * @param prestamoId      UUID único
 * @param prestatarioId   UUID del jugador que recibe el préstamo
 * @param monto           Monto original del préstamo
 * @param saldoPendiente  Monto que falta por pagar
 * @param tasaInteres     Tasa de interés por ciclo Payday (p. ej. 0.05 = 5%)
 * @param cuotas          Número total de cuotas
 * @param cuotasPagadas   Cuotas ya pagadas
 * @param inicio          Cuando se otorgó el préstamo
 * @param enMora          Si el jugador está en mora (más de 2 cuotas sin pagar)
 */
public record LoanRecord(
        UUID prestamoId,
        UUID prestatarioId,
        double monto,
        double saldoPendiente,
        double tasaInteres,
        int cuotas,
        int cuotasPagadas,
        Instant inicio,
        boolean enMora
) {
    /**
     * Crea un nuevo préstamo con los datos básicos.
     */
    public static LoanRecord crear(UUID prestatarioId, double monto,
                                    double tasaInteres, int cuotas) {
        return new LoanRecord(
                UUID.randomUUID(), prestatarioId, monto, monto,
                tasaInteres, cuotas, 0, Instant.now(), false
        );
    }

    /**
     * Calcula el valor de cada cuota (capital + interés).
     */
    public double valorCuota() {
        return (monto / cuotas) * (1 + tasaInteres);
    }

    /**
     * Retorna el número de cuotas pendientes.
     */
    public int cuotasPendientes() {
        return cuotas - cuotasPagadas;
    }

    /**
     * Verifica si el préstamo está completamente pagado.
     */
    public boolean estaPagado() {
        return saldoPendiente <= 0 || cuotasPagadas >= cuotas;
    }
}
