package com.soyjuanpiece.roleplaycore.database;

import java.sql.Connection;

/**
 * Interfaz funcional genérica para encapsular operaciones de base de datos.
 * <p>
 * Es utilizada por {@link DatabaseConnector#executeTransaction(TransactionCallback)}
 * para ejecutar cualquier operación SQL dentro de un contexto transaccional ACID.
 * Al ser una {@code @FunctionalInterface}, puede usarse con lambdas o referencias
 * a métodos, manteniendo el código limpio y expresivo.
 * </p>
 *
 * <pre>{@code
 * // Ejemplo de uso con lambda:
 * db.executeTransaction(conexion -> {
 *     PreparedStatement ps = conexion.prepareStatement("INSERT INTO rp_players VALUES (?, ?)");
 *     ps.setString(1, uuid.toString());
 *     ps.setDouble(2, saldo);
 *     ps.executeUpdate();
 *     return saldo;
 * });
 * }</pre>
 *
 * @param <T> Tipo de resultado que devuelve la operación SQL
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Ejecuta la operación de base de datos usando la conexión proporcionada.
     * <p>
     * La conexión ya tiene el auto-commit desactivado. No se debe llamar
     * {@code commit()} ni {@code rollback()} manualmente; el conector lo gestiona.
     * </p>
     *
     * @param conexion Conexión activa de la base de datos (sin auto-commit)
     * @return Resultado de la operación SQL
     * @throws Exception Si ocurre cualquier error durante la ejecución SQL
     */
    T ejecutar(Connection conexion) throws Exception;
}
