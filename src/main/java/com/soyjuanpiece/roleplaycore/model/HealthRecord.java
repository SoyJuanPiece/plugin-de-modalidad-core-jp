package com.soyjuanpiece.roleplaycore.model;

import com.soyjuanpiece.roleplaycore.health.BloodType;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro médico de un jugador.
 *
 * @param jugadorId       UUID del jugador
 * @param tipoSangre      Tipo de sangre
 * @param nivelSalud      Puntos de vida reales (0-100)
 * @param nivelSed        Nivel de hidratación (0-100; 0 = deshidratado)
 * @param nivelHambre     Nivel de hambre (0-100; 0 = hambriento)
 * @param lesionActiva    Si tiene una lesión grave activa
 * @param descripcionLesion Descripción de la lesión (null si no hay)
 * @param enHospital      Si está ingresado en el hospital
 * @param ultimaActualizacion Cuando se actualizaron estos datos
 */
public record HealthRecord(
        UUID jugadorId,
        BloodType tipoSangre,
        int nivelSalud,
        int nivelSed,
        int nivelHambre,
        boolean lesionActiva,
        String descripcionLesion,
        boolean enHospital,
        Instant ultimaActualizacion
) {
    /**
     * Crea un registro médico nuevo con valores por defecto (plena salud).
     */
    public static HealthRecord crear(UUID jugadorId, BloodType tipoSangre) {
        return new HealthRecord(
                jugadorId, tipoSangre, 100, 100, 100,
                false, null, false, Instant.now()
        );
    }

    /**
     * Verifica si el jugador necesita atención médica urgente.
     */
    public boolean necesitaAtencionUrgente() {
        return nivelSalud <= 20 || nivelSed <= 10 || nivelHambre <= 10 || lesionActiva;
    }

    /**
     * Estado resumido para mostrar al jugador.
     */
    public String resumenEstado() {
        if (nivelSalud <= 0) return "§4MUERTO";
        if (enHospital) return "§c🏥 Hospitalizado";
        if (lesionActiva) return "§c⚠ Lesionado";
        if (nivelSalud <= 30) return "§c❤ Crítico";
        if (nivelSalud <= 60) return "§e❤ Herido";
        return "§a❤ Saludable";
    }
}
