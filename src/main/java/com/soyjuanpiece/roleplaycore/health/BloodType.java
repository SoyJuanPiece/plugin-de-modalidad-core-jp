package com.soyjuanpiece.roleplaycore.health;

/**
 * Tipos de sangre disponibles para jugadores.
 * Necesario para transfusiones en el sistema médico.
 */
public enum BloodType {
    A_POSITIVO("A+"),
    A_NEGATIVO("A-"),
    B_POSITIVO("B+"),
    B_NEGATIVO("B-"),
    AB_POSITIVO("AB+"),
    AB_NEGATIVO("AB-"),
    O_POSITIVO("O+"),
    O_NEGATIVO("O-");

    private final String etiqueta;

    BloodType(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }

    /**
     * Verifica si este tipo de sangre puede recibir del donante indicado.
     */
    public boolean puedeRecibirDe(BloodType donante) {
        return switch (this) {
            case AB_POSITIVO -> true; // receptor universal
            case AB_NEGATIVO -> donante == A_NEGATIVO || donante == B_NEGATIVO
                    || donante == O_NEGATIVO || donante == AB_NEGATIVO;
            case A_POSITIVO  -> donante == A_POSITIVO || donante == A_NEGATIVO
                    || donante == O_POSITIVO || donante == O_NEGATIVO;
            case A_NEGATIVO  -> donante == A_NEGATIVO || donante == O_NEGATIVO;
            case B_POSITIVO  -> donante == B_POSITIVO || donante == B_NEGATIVO
                    || donante == O_POSITIVO || donante == O_NEGATIVO;
            case B_NEGATIVO  -> donante == B_NEGATIVO || donante == O_NEGATIVO;
            case O_POSITIVO  -> donante == O_POSITIVO || donante == O_NEGATIVO;
            case O_NEGATIVO  -> donante == O_NEGATIVO; // solo recibe de O-
        };
    }
}
