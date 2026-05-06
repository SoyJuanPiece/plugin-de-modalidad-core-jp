package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Record inmutable que representa el resultado de una elección municipal.
 * <p>
 * Generado por {@code TownManager#triggerElection()} al finalizar un período electoral.
 * Contiene el ganador y el historial completo de votos emitidos.
 * </p>
 *
 * @param eleccionId      Identificador único de esta elección
 * @param ganadorId       UUID del jugador ganador (null si no hubo candidatos)
 * @param nombreGanador   Nombre del jugador ganador
 * @param votosGanador    Número de votos obtenidos por el ganador
 * @param totalVotos      Total de votos emitidos en la elección
 * @param historialVotos  Mapa con UUID del candidato → número de votos
 * @param timestamp       Momento exacto en que se resolvió la elección
 * @param ciudadNombre    Nombre de la ciudad donde ocurrió la elección
 */
public record ElectionResult(
        UUID eleccionId,
        UUID ganadorId,
        String nombreGanador,
        int votosGanador,
        int totalVotos,
        Map<UUID, Integer> historialVotos,
        Instant timestamp,
        String ciudadNombre
) {

    /**
     * Verifica si la elección tuvo un ganador válido.
     *
     * @return true si hay un ganador registrado
     */
    public boolean tieneGanador() {
        return ganadorId != null && !nombreGanador.isBlank();
    }

    /**
     * Calcula el porcentaje de votos que obtuvo el ganador.
     *
     * @return porcentaje de votos (0.0 a 100.0), 0 si no hay votos
     */
    public double porcentajeVictoria() {
        if (totalVotos == 0) return 0.0;
        return ((double) votosGanador / totalVotos) * 100.0;
    }

    /**
     * Crea un resultado de elección sin ganador (cuando nadie participó).
     *
     * @param ciudadNombre Nombre de la ciudad
     * @return ElectionResult vacío con ganador nulo
     */
    public static ElectionResult sinParticipantes(String ciudadNombre) {
        return new ElectionResult(
                UUID.randomUUID(),
                null,
                "Sin candidatos",
                0,
                0,
                Map.of(),
                Instant.now(),
                ciudadNombre
        );
    }
}
