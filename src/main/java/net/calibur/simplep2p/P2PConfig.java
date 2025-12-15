package net.calibur.simplep2p;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public class P2PConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("simplep2p.properties");
    public static String SESSION_KEY;

    public static void load() {
        Properties props = new Properties();
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                    props.load(in);
                }
            }

            // Get key or generate new one
            SESSION_KEY = props.getProperty("session-key");
            if (SESSION_KEY == null || SESSION_KEY.isEmpty()) {
                SESSION_KEY = UUID.randomUUID().toString().substring(0, 8);
                save(props);
            }
        } catch (IOException e) {
            e.printStackTrace();
            SESSION_KEY = "error-key";
        }
    }

    private static void save(Properties props) {
        props.setProperty("session-key", SESSION_KEY);
        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            props.store(out, "SimpleP2P Configuration");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}