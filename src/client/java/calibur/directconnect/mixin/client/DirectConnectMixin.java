package calibur.directconnect.mixin.client;

import calibur.directconnect.join.JoinManager;
import calibur.directconnect.network.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept server connection attempts and redirect p2p. addresses.
 */
@Mixin(ConnectScreen.class)
public class DirectConnectMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

    // Static flag to prevent re-interception of our own redirected connection
    private static volatile boolean isRedirecting = false;

    /**
     * Intercepts the connect method to check for p2p. addresses.
     * Updated for 1.21.10 method signature.
     */
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void onConnect(Minecraft minecraft, ServerAddress address,
            ServerData serverData, TransferState transferState,
            CallbackInfo ci) {

        // Skip if this is our own redirected connection
        if (isRedirecting) {
            LOGGER.debug("[DirectConnect] Skipping interception (redirecting)");
            return;
        }

        // Check if this is a p2p. address
        String fullAddress = serverData != null ? serverData.ip : null;

        if (fullAddress != null && NetworkUtils.isP2pAddress(fullAddress)) {
            LOGGER.info("[DirectConnect] Intercepted P2P address: {}", fullAddress);

            // Cancel the normal connection
            ci.cancel();

            // Store parent screen for error recovery
            Screen currentScreen = minecraft.screen;
            Screen parentScreen = currentScreen instanceof ConnectScreen ? new JoinMultiplayerScreen(null)
                    : currentScreen;

            // Start P2P connection
            JoinManager joinManager = JoinManager.getInstance();

            joinManager.setOnStatusChange(status -> {
                LOGGER.info("[DirectConnect] Status: {}", status);
            });

            joinManager.setOnError(error -> {
                LOGGER.error("[DirectConnect] Connection failed: {}", error);
                minecraft.execute(() -> {
                    minecraft.setScreen(parentScreen);
                });
            });

            // Start the join process
            joinManager.join(fullAddress).thenAccept(proxyPort -> {
                LOGGER.info("[DirectConnect] Proxy ready on port {}, redirecting Minecraft...", proxyPort);

                minecraft.execute(() -> {
                    // First, clear any existing screen to reset connection state
                    minecraft.setScreen(null);

                    // Create proxy server data
                    ServerAddress proxyAddress = new ServerAddress("127.0.0.1", proxyPort);
                    ServerData proxyServerData = new ServerData(
                            serverData.name + " (P2P)",
                            "127.0.0.1:" + proxyPort,
                            serverData.type());

                    // Set flag to prevent re-interception
                    isRedirecting = true;
                    try {
                        // Start fresh connection to local proxy
                        ConnectScreen.startConnecting(parentScreen, minecraft,
                                proxyAddress, proxyServerData, false, null);
                    } finally {
                        // Clear flag after a short delay (connection is now started)
                        new Thread(() -> {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException ignored) {
                            }
                            isRedirecting = false;
                        }).start();
                    }
                });
            }).exceptionally(e -> {
                LOGGER.error("[DirectConnect] Join failed: {}", e.getMessage());
                minecraft.execute(() -> {
                    minecraft.setScreen(parentScreen);
                });
                return null;
            });
        }
    }
}
