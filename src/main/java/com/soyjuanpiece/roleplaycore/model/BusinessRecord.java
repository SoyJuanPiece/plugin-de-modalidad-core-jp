package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de una empresa creada por un jugador.
 *
 * @param empresaId      UUID única
 * @param nombre         Nombre de la empresa
 * @param duenioId       UUID del dueño
 * @param nombreDuenio   Nombre del dueño
 * @param tipo           Tipo de empresa (TIENDA, FABRICA, RESTAURANTE, BANCO, etc.)
 * @param saldoEmpresa   Saldo de la cuenta empresarial
 * @param costoCracion   Costo que se pagó para crearla
 * @param empleados      Número actual de empleados
 * @param maxEmpleados   Máximo de empleados permitido
 * @param activa         Si la empresa está activa
 * @param creada         Cuando fue creada
 */
public record BusinessRecord(
        UUID empresaId,
        String nombre,
        UUID duenioId,
        String nombreDuenio,
        String tipo,
        double saldoEmpresa,
        double costoCreacion,
        int empleados,
        int maxEmpleados,
        boolean activa,
        Instant creada
) {
    /**
     * Crea una nueva empresa.
     */
    public static BusinessRecord crear(UUID duenioId, String nombreDuenio,
                                        String nombre, String tipo,
                                        double costoCreacion, int maxEmpleados) {
        return new BusinessRecord(
                UUID.randomUUID(), nombre, duenioId, nombreDuenio,
                tipo, 0.0, costoCreacion, 0, maxEmpleados, true, Instant.now()
        );
    }

    /**
     * Verifica si el jugador trabaja en esta empresa como dueño.
     */
    public boolean esDuenio(UUID jugadorId) {
        return duenioId.equals(jugadorId);
    }

    /**
     * Verifica si puede contratar más empleados.
     */
    public boolean puedeContratarMas() {
        return empleados < maxEmpleados;
    }
}
