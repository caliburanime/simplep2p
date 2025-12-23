package calibur.directconnect;

import calibur.directconnect.command.HostCommand;
import calibur.directconnect.config.ModConfig;
import calibur.directconnect.host.HostManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectConnect - P2P Minecraft Bridge
 * 
 * A decentralized peer-to-peer connection mod for Minecraft.
 * Uses UDP hole punching and KCP reliable UDP for direct connections.
 */
public class DirectConnect implements ModInitializer {
    public static final String MOD_ID = "direct-connect";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[DirectConnect] Initializing P2P bridge mod...");

        // Load configuration
        ModConfig config = ModConfig.getInstance();
        LOGGER.info("[DirectConnect] Config loaded: registry={}", config.getRegistryUrl());

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HostCommand.register(dispatcher);
            LOGGER.info("[DirectConnect] Commands registered");
        });

        // Auto-start hosting for dedicated servers
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Check if this is a dedicated server (not integrated/singleplayer)
            if (server.isDedicatedServer()) {
                LOGGER.info("[DirectConnect] Dedicated server detected, auto-starting P2P host...");

                HostManager host = HostManager.getInstance();
                if (host.start()) {
                    LOGGER.info("[DirectConnect] Auto-hosting started: {}", host.getFullUri());
                } else {
                    LOGGER.warn("[DirectConnect] Auto-hosting failed. Players can still connect directly.");
                }
            } else {
                LOGGER.info("[DirectConnect] Integrated server. Use /host to share your world.");
            }
        });

        // Cleanup on server stop
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            HostManager host = HostManager.getInstance();
            if (host.isRunning()) {
                LOGGER.info("[DirectConnect] Server stopping, cleaning up P2P host...");
                host.stop();
            }
        });

        LOGGER.info("[DirectConnect] Initialization complete!");
    }
}
