package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Llamada de emergencia al 911.
 *
 * @param llamadaId      UUID único de la llamada
 * @param llamanteId     UUID del jugador que llamó
 * @param nombreLlamante Nombre del llamante
 * @param tipo           Tipo de emergencia (POLICIA, MEDICO, BOMBERO)
 * @param descripcion    Descripción de la emergencia
 * @param mundo          Mundo de la emergencia
 * @param x, y, z        Coordenadas
 * @param timestamp      Cuando se realizó la llamada
 * @param atendida       Si ya fue atendida por algún oficial
 * @param atendiendoId   UUID del oficial que atiende (null si no ha sido asignada)
 */
public record EmergencyCall(
        UUID llamadaId,
        UUID llamanteId,
        String nombreLlamante,
        String tipo,
        String descripcion,
        String mundo,
        double x,
        double y,
        double z,
        Instant timestamp,
        boolean atendida,
        UUID atendiendoId
) {
    /**
     * Crea una nueva llamada de emergencia.
     */
    public static EmergencyCall crear(UUID llamanteId, String nombre,
                                       String tipo, String descripcion,
                                       String mundo, double x, double y, double z) {
        return new EmergencyCall(
                UUID.randomUUID(), llamanteId, nombre, tipo, descripcion,
                mundo, x, y, z, Instant.now(), false, null
        );
    }

    /**
     * Retorna una versión marcada como atendida por el oficial indicado.
     */
    public EmergencyCall atendidaPor(UUID oficialId) {
        return new EmergencyCall(llamadaId, llamanteId, nombreLlamante, tipo,
                descripcion, mundo, x, y, z, timestamp, true, oficialId);
    }
}
