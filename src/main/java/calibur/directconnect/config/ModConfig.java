package calibur.directconnect.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Configuration manager for DirectConnect mod.
 * Handles persistent share codes and registry settings.
 */
public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "directconnect.json";

    // Singleton instance
    private static ModConfig instance;

    // Configuration fields
    private String registryUrl = "http://localhost:8000";
    private String shareCode = null;
    private int udpPort = 0; // 0 means random
    private boolean debug = false;
    private int connectionTimeout = 10000; // 10 seconds
    private int heartbeatInterval = 30000; // 30 seconds

    // Word lists for share code generation
    private static final String[] ADJECTIVES = {
            "happy", "bouncy", "sleepy", "noisy", "shiny", "fluffy",
            "brave", "clever", "gentle", "mighty", "swift", "calm",
            "wild", "proud", "quiet", "eager", "jolly", "witty"
    };

    private static final String[] ANIMALS = {
            "llama", "cat", "dog", "eagle", "panda", "tiger",
            "wolf", "bear", "fox", "owl", "hawk", "deer",
            "lion", "otter", "raven", "koala", "lynx", "seal"
    };

    private ModConfig() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance, loading from file if necessary.
     */
    public static synchronized ModConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private static ModConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                LOGGER.info("[DirectConnect] Loaded config from {}", configPath);
                return config;
            } catch (IOException e) {
                LOGGER.error("[DirectConnect] Failed to load config: {}", e.getMessage());
            }
        }

        // Create new config with defaults
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    /**
     * Saves current configuration to file.
     */
    public void save() {
        Path configPath = getConfigPath();

        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            LOGGER.info("[DirectConnect] Saved config to {}", configPath);
        } catch (IOException e) {
            LOGGER.error("[DirectConnect] Failed to save config: {}", e.getMessage());
        }
    }

    /**
     * Gets the config file path.
     */
    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }

    // --- Share Code Management ---

    /**
     * Gets the share code, generating one if not set.
     */
    public String getShareCode() {
        if (shareCode == null || shareCode.isEmpty()) {
            shareCode = generateShareCode();
            save();
        }
        return shareCode;
    }

    /**
     * Sets a specific share code.
     */
    public void setShareCode(String code) {
        this.shareCode = code;
        save();
    }

    /**
     * Regenerates the share code with a new random value.
     * 
     * @return The new share code
     */
    public String regenerateShareCode() {
        this.shareCode = generateShareCode();
        save();
        LOGGER.info("[DirectConnect] Regenerated share code: {}", shareCode);
        return shareCode;
    }

    /**
     * Generates a new random share code in format: adjective-animal-##
     */
    private static String generateShareCode() {
        Random random = new Random();
        String adj = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String animal = ANIMALS[random.nextInt(ANIMALS.length)];
        int number = 10 + random.nextInt(90); // 10-99
        return adj + "-" + animal + "-" + number;
    }

    /**
     * Validates a share code format.
     */
    public static boolean isValidShareCode(String code) {
        if (code == null)
            return false;
        return code.matches("^[a-z]+-[a-z]+-\\d{2,3}$");
    }

    // --- Getters and Setters ---

    public String getRegistryUrl() {
        return registryUrl;
    }

    public void setRegistryUrl(String url) {
        this.registryUrl = url;
        save();
    }

    public int getUdpPort() {
        if (udpPort == 0) {
            // Generate random port in range 51900-51999
            udpPort = 51900 + new Random().nextInt(100);
            save();
        }
        return udpPort;
    }

    public void setUdpPort(int port) {
        this.udpPort = port;
        save();
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        save();
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Returns the full p2p:// URI for this server.
     */
    public String getFullShareUri() {
        return "p2p://" + getShareCode();
    }

    @Override
    public String toString() {
        return "ModConfig{" +
                "registryUrl='" + registryUrl + '\'' +
                ", shareCode='" + shareCode + '\'' +
                ", udpPort=" + udpPort +
                ", debug=" + debug +
                '}';
    }
}
