package com.soyjuanpiece.roleplaycore.model;

import com.soyjuanpiece.roleplaycore.crime.CrimeType;
import org.bukkit.Location;

import java.time.Instant;
import java.util.UUID;

/**
 * Record inmutable que representa una evidencia forense dejada en la escena de un crimen.
 * <p>
 * Las evidencias de ADN son invisibles para civiles pero detectables por policías
 * que usen el ítem "Scanner". Usan Java 21 Records para garantizar inmutabilidad.
 * </p>
 *
 * @param evidenciaId  Identificador único de esta evidencia
 * @param criminalId   UUID del jugador que cometió el crimen
 * @param nombreCriminal Nombre del jugador criminal (para mostrar en el scanner)
 * @param tipoCrimen   Tipo de crimen cometido
 * @param mundo        Nombre del mundo donde ocurrió el crimen
 * @param coordX       Coordenada X de la escena del crimen
 * @param coordY       Coordenada Y de la escena del crimen
 * @param coordZ       Coordenada Z de la escena del crimen
 * @param timestamp    Momento exacto en que se cometió el crimen
 * @param expira       Momento en que esta evidencia se destruye automáticamente
 */
public record EvidenceRecord(
        UUID evidenciaId,
        UUID criminalId,
        String nombreCriminal,
        CrimeType tipoCrimen,
        String mundo,
        double coordX,
        double coordY,
        double coordZ,
        Instant timestamp,
        Instant expira
) {

    /**
     * Crea un nuevo registro de evidencia a partir de una ubicación de Bukkit.
     *
     * @param criminalId     UUID del criminal
     * @param nombreCriminal Nombre del criminal
     * @param tipoCrimen     Tipo de crimen
     * @param ubicacion      Ubicación Bukkit de la escena
     * @param duracionSegundos Segundos hasta que la evidencia desaparezca
     * @return nuevo EvidenceRecord con ID generado automáticamente
     */
    public static EvidenceRecord crear(
            UUID criminalId,
            String nombreCriminal,
            CrimeType tipoCrimen,
            Location ubicacion,
            long duracionSegundos
    ) {
        Instant ahora = Instant.now();
        return new EvidenceRecord(
                UUID.randomUUID(),
                criminalId,
                nombreCriminal,
                tipoCrimen,
                ubicacion.getWorld().getName(),
                ubicacion.getX(),
                ubicacion.getY(),
                ubicacion.getZ(),
                ahora,
                ahora.plusSeconds(duracionSegundos)
        );
    }

    /**
     * Verifica si esta evidencia sigue siendo válida (no ha expirado).
     *
     * @return true si la evidencia aún no ha expirado
     */
    public boolean esValida() {
        return Instant.now().isBefore(expira);
    }

    /**
     * Retorna una descripción formateada para mostrar en el scanner policial.
     *
     * @return String con la información del crimen para el scanner
     */
    public String descripcionScanner() {
        return String.format(
                "§c[ADN DETECTADO] §fCriminal: §e%s §f| Crimen: §c%s §f| Hora: §7%s",
                nombreCriminal,
                tipoCrimen.getNombre(),
                timestamp.toString().replace("T", " ").substring(0, 19)
        );
    }
}
