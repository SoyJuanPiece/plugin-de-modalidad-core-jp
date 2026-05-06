package com.soyjuanpiece.roleplaycore.model;

import com.soyjuanpiece.roleplaycore.housing.PropertyType;

import java.time.Instant;
import java.util.UUID;

/**
 * Propiedad inmobiliaria en el servidor.
 *
 * @param propiedadId   UUID única de la propiedad
 * @param nombre        Nombre visible de la propiedad
 * @param tipo          Tipo de propiedad
 * @param propietarioId UUID del propietario (null = disponible)
 * @param inquilinoId   UUID del inquilino (null = sin arrendar)
 * @param precio        Precio de venta actual
 * @param alquiler      Alquiler mensual actual
 * @param mundo         Mundo donde se ubica
 * @param x, y, z       Coordenadas del portal/entrada
 * @param registrada    Cuando fue registrada en el sistema
 * @param hipotecada    Si tiene hipoteca activa
 */
public record HousingProperty(
        UUID propiedadId,
        String nombre,
        PropertyType tipo,
        UUID propietarioId,
        UUID inquilinoId,
        double precio,
        double alquiler,
        String mundo,
        double x,
        double y,
        double z,
        Instant registrada,
        boolean hipotecada
) {
    /**
     * Verifica si la propiedad está disponible para compra.
     */
    public boolean estaDisponible() {
        return propietarioId == null;
    }

    /**
     * Verifica si la propiedad está arrendada.
     */
    public boolean estaArrendada() {
        return inquilinoId != null;
    }

    /**
     * Verifica si el jugador es propietario o inquilino.
     */
    public boolean tieneAcceso(UUID jugadorId) {
        return jugadorId.equals(propietarioId) || jugadorId.equals(inquilinoId);
    }

    /**
     * Retorna una versión con nuevo propietario.
     */
    public HousingProperty conPropietario(UUID nuevoPropietarioId) {
        return new HousingProperty(propiedadId, nombre, tipo, nuevoPropietarioId, null,
                precio, alquiler, mundo, x, y, z, registrada, hipotecada);
    }
}
