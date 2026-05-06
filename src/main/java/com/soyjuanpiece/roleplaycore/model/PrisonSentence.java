package com.soyjuanpiece.roleplaycore.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de una sentencia de prisión.
 *
 * @param sentenciaId    UUID único de la sentencia
 * @param presosId       UUID del preso
 * @param nombrePreso    Nombre del preso
 * @param motivo         Descripción del delito
 * @param duracionMinutos Duración de la condena en minutos de juego
 * @param inicio         Cuando comenzó la condena
 * @param fianza         Monto de fianza (0 = sin fianza)
 * @param fianzdPagada   Si la fianza fue pagada y el jugador fue liberado
 * @param juezId         UUID del juez que dictó la sentencia (null si fue automática)
 */
public record PrisonSentence(
        UUID sentenciaId,
        UUID presoId,
        String nombrePreso,
        String motivo,
        int duracionMinutos,
        Instant inicio,
        double fianza,
        boolean fianzaPagada,
        UUID juezId
) {
    /**
     * Crea una nueva sentencia de prisión.
     */
    public static PrisonSentence crear(UUID presoId, String nombrePreso,
                                        String motivo, int duracionMinutos,
                                        double fianza, UUID juezId) {
        return new PrisonSentence(
                UUID.randomUUID(), presoId, nombrePreso, motivo,
                duracionMinutos, Instant.now(), fianza, false, juezId
        );
    }

    /**
     * Retorna los minutos que quedan de condena.
     */
    public long minutosRestantes() {
        long transcurridos = (Instant.now().toEpochMilli() - inicio.toEpochMilli()) / 60_000L;
        return Math.max(0, duracionMinutos - transcurridos);
    }

    /**
     * Verifica si la condena ya terminó.
     */
    public boolean haTerminado() {
        return fianzaPagada || minutosRestantes() <= 0;
    }
}
