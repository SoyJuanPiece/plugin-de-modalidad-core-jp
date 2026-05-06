package com.soyjuanpiece.roleplaycore.model;

import com.soyjuanpiece.roleplaycore.license.LicenseType;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de una licencia emitida a un jugador.
 *
 * @param licenciaId   UUID único de esta licencia
 * @param jugadorId    UUID del propietario
 * @param tipo         Tipo de licencia
 * @param emitida      Cuando fue emitida
 * @param expira       Cuando expira (null = no expira)
 * @param revocada     Si fue revocada por las autoridades
 */
public record LicenseRecord(
        UUID licenciaId,
        UUID jugadorId,
        LicenseType tipo,
        Instant emitida,
        Instant expira,
        boolean revocada
) {
    /**
     * Crea una nueva licencia con vigencia en días.
     */
    public static LicenseRecord crear(UUID jugadorId, LicenseType tipo, int diasVigencia) {
        Instant ahora  = Instant.now();
        Instant expira = diasVigencia > 0
                ? ahora.plusSeconds(diasVigencia * 86_400L)
                : null;
        return new LicenseRecord(UUID.randomUUID(), jugadorId, tipo, ahora, expira, false);
    }

    /**
     * Retorna true si la licencia está vigente (no revocada y no expirada).
     */
    public boolean esValida() {
        if (revocada) return false;
        if (expira == null) return true;
        return Instant.now().isBefore(expira);
    }
}
