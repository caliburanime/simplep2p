package net.calibur.simplep2p.mixin.client;

import net.minecraft.client.Minecraft; // "Minecraft" in Mojang mappings
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.calibur.simplep2p.SimpleP2P;
import net.calibur.simplep2p.UDPConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class MixinConnectScreen {

    // We inject code at the HEAD (start) of the startConnecting method
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void onStartConnecting(Screen screen, Minecraft minecraft, ServerAddress serverAddress, ServerData serverData, boolean bl, TransferState transferState, CallbackInfo ci) {

        String inputHost = serverAddress.getHost();

        // LOGIC: How do we know it's a Key and not an IP?
        // IPs usually have dots (127.0.0.1) or colons (2001:db8::).
        // Our keys are UUID chunks (e.g., "a1b2c3d4").
        // Let's assume if there is NO dot, it is a key.
        if (!inputHost.contains(".") || !inputHost.contains(":") || !inputHost.contains("localhost")) {

            System.out.println("[Mixin] Intercepted Connection to Key: " + inputHost);

            // 1. CANCEL the vanilla connection process
            ci.cancel();

            // 2. Start our P2P Logic
            // We need a thread because we can't freeze the GUI
            new Thread(() -> {
                if (SimpleP2P.activeConnection != null) SimpleP2P.activeConnection.close();

                // We don't have a chat source here, so pass null
                SimpleP2P.activeConnection = new UDPConnection("127.0.0.1", 5000, null, null);
                SimpleP2P.activeConnection.lookup(inputHost);
                SimpleP2P.activeConnection.startClientProxy();

                // 3. Wait for P2P to be ready (Simulated wait)
                // In a real mod, we would listen for the "CONNECTED" event.
                // For now, let's wait 2 seconds for hole punching.
                try { Thread.sleep(2000); } catch (InterruptedException e) {}

                // 4. Re-trigger the connection, but this time to LOCALHOST
                minecraft.execute(() -> {
                    ConnectScreen.startConnecting(
                            screen,
                            minecraft,
                            new ServerAddress("127.0.0.1", 33333), // Connect to our Proxy
                            serverData,
                            false,
                            null
                    );
                });
            }).start();
        }
    }
}