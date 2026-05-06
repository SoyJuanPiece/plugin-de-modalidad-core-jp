package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de matrimonio entre dos jugadores.
 *
 * @param matrimonioId  UUID único
 * @param conyuge1Id    UUID del primer cónyuge
 * @param conyuge2Id    UUID del segundo cónyuge
 * @param nombre1       Nombre del primer cónyuge
 * @param nombre2       Nombre del segundo cónyuge
 * @param fecha         Fecha de matrimonio
 * @param activo        Si el matrimonio sigue vigente
 * @param regimen       Régimen patrimonial (BIENES_GANANCIALES o SEPARACION_BIENES)
 */
public record MarriageRecord(
        UUID matrimonioId,
        UUID conyuge1Id,
        UUID conyuge2Id,
        String nombre1,
        String nombre2,
        Instant fecha,
        boolean activo,
        String regimen
) {
    /**
     * Crea un nuevo registro de matrimonio con bienes gananciales por defecto.
     */
    public static MarriageRecord crear(UUID c1Id, String nombre1, UUID c2Id, String nombre2) {
        return new MarriageRecord(
                UUID.randomUUID(), c1Id, c2Id, nombre1, nombre2,
                Instant.now(), true, "BIENES_GANANCIALES"
        );
    }

    /**
     * Verifica si un jugador es cónyuge en este matrimonio.
     */
    public boolean esConyuge(UUID jugadorId) {
        return conyuge1Id.equals(jugadorId) || conyuge2Id.equals(jugadorId);
    }

    /**
     * Retorna el ID del otro cónyuge.
     */
    public UUID getOtroConyuge(UUID jugadorId) {
        return conyuge1Id.equals(jugadorId) ? conyuge2Id : conyuge1Id;
    }

    /**
     * Retorna el nombre del otro cónyuge.
     */
    public String getNombreOtroConyuge(UUID jugadorId) {
        return conyuge1Id.equals(jugadorId) ? nombre2 : nombre1;
    }
}
