package calibur.directconnect.mixin.client;

import calibur.directconnect.join.JoinManager;
import calibur.directconnect.network.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept server connection attempts and redirect p2p:// addresses.
 */
@Mixin(ConnectScreen.class)
public class DirectConnectMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

    /**
     * Intercepts the connect method to check for p2p:// addresses.
     */
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void onConnect(Screen parentScreen, Minecraft minecraft,
            ServerAddress address, ServerData serverData, boolean quickPlay,
            CallbackInfo ci) {

        // Check if this is a p2p:// address
        String fullAddress = serverData != null ? serverData.ip : null;

        if (fullAddress != null && NetworkUtils.isP2pAddress(fullAddress)) {
            LOGGER.info("[DirectConnect] Intercepted P2P address: {}", fullAddress);

            // Cancel the normal connection
            ci.cancel();

            // Start P2P connection
            JoinManager joinManager = JoinManager.getInstance();

            joinManager.setOnStatusChange(status -> {
                LOGGER.info("[DirectConnect] Status: {}", status);
            });

            joinManager.setOnProxyReady(proxyPort -> {
                LOGGER.info("[DirectConnect] Proxy ready on port {}", proxyPort);

                // Redirect Minecraft to connect to localhost:proxyPort
                ServerAddress proxyAddress = new ServerAddress("127.0.0.1", proxyPort);
                ServerData proxyServerData = new ServerData(
                        serverData.name + " (P2P)",
                        "127.0.0.1:" + proxyPort,
                        serverData.type());

                // Connect to local proxy
                minecraft.execute(() -> {
                    ConnectScreen.startConnecting(parentScreen, minecraft,
                            proxyAddress, proxyServerData, quickPlay, null);
                });
            });

            joinManager.setOnError(error -> {
                LOGGER.error("[DirectConnect] Connection failed: {}", error);
                // Return to parent screen with error
                minecraft.execute(() -> {
                    minecraft.setScreen(parentScreen);
                });
            });

            // Start the join process
            joinManager.join(fullAddress).exceptionally(e -> {
                LOGGER.error("[DirectConnect] Join failed: {}", e.getMessage());
                minecraft.execute(() -> {
                    minecraft.setScreen(parentScreen);
                });
                return null;
            });
        }
    }
}
