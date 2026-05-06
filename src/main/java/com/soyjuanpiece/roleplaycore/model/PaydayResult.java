package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Record inmutable que representa el resultado de un ciclo de pago (Payday).
 * <p>
 * Al usar Java 21 Records, garantizamos inmutabilidad total y generación
 * automática de equals(), hashCode() y toString(), reduciendo el boilerplate.
 * </p>
 *
 * @param jugadorId     UUID del jugador que recibió el pago
 * @param salarioBruto  Salario bruto antes de aplicar impuestos
 * @param impuestos     Monto descontado por impuestos
 * @param salarioNeto   Salario neto que el jugador recibe efectivamente
 * @param nivelImpuesto Porcentaje de impuesto aplicado (0.0 a 1.0)
 * @param timestamp     Momento exacto en que se procesó el pago
 * @param exitoso       Indica si la transacción se completó sin errores
 */
public record PaydayResult(
        UUID jugadorId,
        double salarioBruto,
        double impuestos,
        double salarioNeto,
        double nivelImpuesto,
        Instant timestamp,
        boolean exitoso
) {

    /**
     * Crea un resultado de pago fallido con todos los montos en cero.
     *
     * @param jugadorId UUID del jugador afectado
     * @return instancia de PaydayResult representando un fallo
     */
    public static PaydayResult fallido(UUID jugadorId) {
        return new PaydayResult(jugadorId, 0.0, 0.0, 0.0, 0.0, Instant.now(), false);
    }

    /**
     * Calcula el porcentaje efectivo de retención impositiva.
     *
     * @return porcentaje de impuestos como valor entre 0 y 100
     */
    public double porcentajeRetencion() {
        if (salarioBruto == 0.0) return 0.0;
        return (impuestos / salarioBruto) * 100.0;
    }
}
