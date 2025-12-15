package net.calibur.simplep2p;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import static net.minecraft.commands.Commands.literal;

public class SimpleP2P implements ModInitializer {

	// Global reference to our active connection
	public static UDPConnection activeConnection;

	// CONFIG: Change this to your public Cloud VPS IP later!
	private static final String SIGNALING_IP = "127.0.0.1";
	private static final int SIGNALING_PORT = 5000;

	@Override
	public void onInitialize() {
		System.out.println("[SimpleP2P] Initializing...");

		// 1. Load Config (Gets the persistent key)
		P2PConfig.load();

		// 2. REGISTER "AUTO-START" (For Dedicated Servers)
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// Only auto-start if it's a dedicated server (no GUI)
			if (!server.isSingleplayer()) {
				int realServerPort = server.getPort();
				System.out.println("--------------------------------------------------");
				System.out.println("[SimpleP2P] Dedicated Server Detected.");
				System.out.println("[SimpleP2P] Port: " + realServerPort);

				// START HOST LOGIC
				// Note: We pass 'server' as the last argument so we can wake it up later!
				startHost(server, realServerPort, null);

				System.out.println("[SimpleP2P] Auto-Host Started with Key: " + P2PConfig.SESSION_KEY);
				System.out.println("--------------------------------------------------");
			}
		});

		// 3. REGISTER MANUAL COMMAND (For Singleplayer / LAN)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("p2p")
					.then(literal("host")
							.executes(context -> {
								// Get the server instance from the command source
								MinecraftServer server = context.getSource().getServer();

								// Check if they opened to LAN
								int port = server.getPort();

								// If port is -1 or very generic, they might not have opened to LAN yet.
								// However, server.getPort() usually returns the correct port if LAN is open.
								if (port <= 0) {
									context.getSource().sendSuccess(
											() -> Component
													.literal("§c[Error] Please press Esc -> 'Open to LAN' first!"),
											false);
									return 0;
								}

								// START HOST LOGIC manually
								startHost(server, port, context.getSource());

								context.getSource().sendSuccess(
										() -> Component
												.literal("§a[P2P] Host Started! Key: §f" + P2PConfig.SESSION_KEY),
										false);
								return 1;
							})));
		});
	}

	// --- HELPER METHOD TO START HOSTING ---
	// This avoids writing the same code twice (once for Auto, once for Manual)
	private void startHost(MinecraftServer server, int port, net.minecraft.commands.CommandSourceStack source) {
		// Close old connection if it exists
		if (activeConnection != null) {
			activeConnection.close();
		}

		// Create the connection
		// We pass 'server' here so UDPConnection can use server.execute() to wake it up
		activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, source, server);

		// Register the key with Python Server
		activeConnection.register(P2PConfig.SESSION_KEY);

		// Start the Proxy Bridge to the local port
		activeConnection.startHostProxy(port);
	}
}