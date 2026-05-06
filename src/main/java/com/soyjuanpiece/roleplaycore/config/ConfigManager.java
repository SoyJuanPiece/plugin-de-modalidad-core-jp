package com.soyjuanpiece.roleplaycore.config;

import com.soyjuanpiece.roleplaycore.RoleplayCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Gestor central de configuración de RoleplayCore.
 * <p>
 * Cada sistema tiene su propio archivo YAML en la carpeta del plugin.
 * Todos los valores tienen defaults seguros por si el administrador
 * borra alguna clave.  Usar {@link #recargarTodo()} para aplicar cambios
 * en caliente sin reiniciar el servidor.
 * </p>
 *
 * <h3>Archivos gestionados:</h3>
 * <ul>
 *   <li>config.yml        – Base de datos y opciones generales</li>
 *   <li>economy.yml       – Economía, impuestos, préstamos, Payday</li>
 *   <li>jobs.yml          – Trabajos, salarios, plazas, requisitos</li>
 *   <li>licenses.yml      – Licencias (coste, duración, renovación)</li>
 *   <li>crime.yml         – Sistema criminal y evidencias forenses</li>
 *   <li>justice.yml       – Prisión, tribunal, fianzas, sentencias</li>
 *   <li>health.yml        – Salud, hambre, sed, lesiones, hospital</li>
 *   <li>housing.yml       – Inmobiliaria, alquileres, hipotecas</li>
 *   <li>drug.yml          – Economía ilegal, precios, efectos</li>
 *   <li>social.yml        – Matrimonio, familia, reputación, clanes</li>
 *   <li>business.yml      – Empresas, empleados, impuestos empresariales</li>
 *   <li>emergency.yml     – Servicios de emergencia (911, despacho)</li>
 *   <li>chat.yml          – Canales de chat RP (local, global, radio)</li>
 *   <li>scoreboard.yml    – Scoreboard lateral (líneas, colores, refresh)</li>
 *   <li>messages.yml      – Todos los mensajes del plugin (multi-idioma)</li>
 *   <li>weapons.yml       – Permisos de armas, efectos, daño personalizado</li>
 *   <li>town.yml          – Ciudad, alcaldía, CityFlags, elecciones</li>
 * </ul>
 */
public class ConfigManager {

    private final RoleplayCore plugin;

    /** Mapa interno: nombre-archivo → FileConfiguration cargada */
    private final Map<String, FileConfiguration> configs = new HashMap<>();

    /** Nombres de todos los archivos que este manager gestiona */
    private static final String[] ARCHIVOS = {
            "economy", "jobs", "licenses", "crime", "justice",
            "health", "housing", "drug", "social", "business",
            "emergency", "chat", "scoreboard", "messages", "weapons", "town"
    };

    public ConfigManager(RoleplayCore plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Carga y recarga
    // -----------------------------------------------------------------------

    /**
     * Carga (o recarga) todos los archivos de configuración.
     * Copia los defaults desde los recursos del JAR si el archivo no existe.
     */
    public void cargarTodo() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        for (String nombre : ARCHIVOS) {
            cargar(nombre);
        }
        plugin.getLogger().info("[ConfigManager] ✓ " + (ARCHIVOS.length + 1)
                + " archivos de configuración cargados.");
    }

    /**
     * Recarga todos los archivos en caliente (sin reiniciar el servidor).
     */
    public void recargarTodo() {
        plugin.reloadConfig();
        for (String nombre : ARCHIVOS) {
            cargar(nombre);
        }
        plugin.getLogger().info("[ConfigManager] ✓ Configuración recargada.");
    }

    /**
     * Carga un archivo YAML individual.
     * Si no existe en el directorio de datos, lo copia desde los recursos.
     */
    private void cargar(String nombre) {
        File archivo = new File(plugin.getDataFolder(), nombre + ".yml");

        if (!archivo.exists()) {
            try {
                plugin.saveResource(nombre + ".yml", false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning(
                        "[ConfigManager] No se encontró " + nombre + ".yml en el JAR. " +
                        "Se creará vacío.");
                try {
                    archivo.getParentFile().mkdirs();
                    archivo.createNewFile();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "[ConfigManager] No se pudo crear " + nombre + ".yml", e);
                    return;
                }
            }
        }

        FileConfiguration fc = YamlConfiguration.loadConfiguration(archivo);
        configs.put(nombre, fc);
    }

    // -----------------------------------------------------------------------
    // Accesores tipados
    // -----------------------------------------------------------------------

    /** Obtiene la configuración de un archivo por nombre. */
    public FileConfiguration get(String nombre) { return configs.get(nombre); }

    public FileConfiguration economy()    { return configs.get("economy"); }
    public FileConfiguration jobs()       { return configs.get("jobs"); }
    public FileConfiguration licenses()   { return configs.get("licenses"); }
    public FileConfiguration crime()      { return configs.get("crime"); }
    public FileConfiguration justice()    { return configs.get("justice"); }
    public FileConfiguration health()     { return configs.get("health"); }
    public FileConfiguration housing()    { return configs.get("housing"); }
    public FileConfiguration drug()       { return configs.get("drug"); }
    public FileConfiguration social()     { return configs.get("social"); }
    public FileConfiguration business()   { return configs.get("business"); }
    public FileConfiguration emergency()  { return configs.get("emergency"); }
    public FileConfiguration chat()       { return configs.get("chat"); }
    public FileConfiguration scoreboard() { return configs.get("scoreboard"); }
    public FileConfiguration messages()   { return configs.get("messages"); }
    public FileConfiguration weapons()    { return configs.get("weapons"); }
    public FileConfiguration town()       { return configs.get("town"); }

    // -----------------------------------------------------------------------
    // Helpers de lectura segura
    // -----------------------------------------------------------------------

    public String getString(String archivo, String ruta, String defecto) {
        FileConfiguration fc = configs.get(archivo);
        return fc != null ? fc.getString(ruta, defecto) : defecto;
    }

    public double getDouble(String archivo, String ruta, double defecto) {
        FileConfiguration fc = configs.get(archivo);
        return fc != null ? fc.getDouble(ruta, defecto) : defecto;
    }

    public int getInt(String archivo, String ruta, int defecto) {
        FileConfiguration fc = configs.get(archivo);
        return fc != null ? fc.getInt(ruta, defecto) : defecto;
    }

    public boolean getBoolean(String archivo, String ruta, boolean defecto) {
        FileConfiguration fc = configs.get(archivo);
        return fc != null ? fc.getBoolean(ruta, defecto) : defecto;
    }

    public long getLong(String archivo, String ruta, long defecto) {
        FileConfiguration fc = configs.get(archivo);
        return fc != null ? fc.getLong(ruta, defecto) : defecto;
    }

    // -----------------------------------------------------------------------
    // Mensajes localizados
    // -----------------------------------------------------------------------

    /**
     * Retorna un mensaje del archivo messages.yml con colores aplicados.
     */
    public String msg(String ruta, String fallback) {
        FileConfiguration m = configs.get("messages");
        if (m == null) return colorear(fallback);
        return colorear(m.getString(ruta, fallback));
    }

    /**
     * Retorna un mensaje con reemplazos de placeholders.
     * Ejemplo: {@code msg("economy.saldo", "Saldo: {valor}", "valor", "500")}
     */
    public String msg(String ruta, String fallback, String... reemplazos) {
        String texto = msg(ruta, fallback);
        for (int i = 0; i + 1 < reemplazos.length; i += 2) {
            texto = texto.replace("{" + reemplazos[i] + "}", reemplazos[i + 1]);
        }
        return texto;
    }

    /** Convierte códigos &a → §a para colorear texto en Minecraft. */
    private String colorear(String texto) {
        return texto == null ? "" : texto.replace('&', '§');
    }
}
