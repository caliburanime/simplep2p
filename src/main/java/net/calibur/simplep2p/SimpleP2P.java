package net.calibur.simplep2p;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;


public class SimpleP2P implements ModInitializer {

	// Global reference to our active connection
	public static UDPConnection activeConnection;

	// CONFIG: Change this to your server IP later!
	private static final String SIGNALING_IP = "127.0.0.1";
	private static final int SIGNALING_PORT = 5000;

	@Override
	public void onInitialize() {
		System.out.println("SimpleP2P Phase 7 (UDP) Loading...");

		// 1. Load Config (Gets the persistent key)
		P2PConfig.load();

		// 2. Register Server Start Event
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// This code runs when the Dedicated Server (or Singleplayer Host) is fully ready.

			int realServerPort = server.getPort(); // e.g., 25565
			System.out.println("--------------------------------------------------");
			System.out.println("[SimpleP2P] Server Started on Port: " + realServerPort);
			System.out.println("[SimpleP2P] P2P Key: " + P2PConfig.SESSION_KEY);
			System.out.println("--------------------------------------------------");

			// Start the UDP Host automatically
			if (activeConnection != null) activeConnection.close();

			// Note: pass null for source because we are in console, not chat
			activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, null);
			activeConnection.register(P2PConfig.SESSION_KEY);

			// HOST PROXY: We don't need a full proxy here!
			// We just need to pump UDP packets to the LOCAL port.
			// We can reuse startHostProxy, but we need to tell it the correct port.
			// (We will update UDPConnection in a second to accept a target port).
			activeConnection.startHostProxy(realServerPort);
		});

	}
}


//CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
//		dispatcher.register(literal("p2p")
//
//// --- HOST ---
//					.then(literal("host")
//							.executes(context -> {
//		// Close old connection if exists
//		if (activeConnection != null) activeConnection.close();
//
//String key = UUID.randomUUID().toString().substring(0, 8);
//								context.getSource().sendSuccess(() -> Component.literal("§e[P2P] Starting UDP Host with key: " + key), false);
//
//// Create Connection & Register
//activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, context.getSource());
//		activeConnection.register(key);
//
//								return 1;
//										})
//										)
//
//										// --- JOIN ---
//										.then(literal("join")
//							.then(argument("secretKey", StringArgumentType.word())
//		.executes(context -> {
//		// Close old connection if exists
//		if (activeConnection != null) activeConnection.close();
//
//String key = StringArgumentType.getString(context, "secretKey");
//										context.getSource().sendSuccess(() -> Component.literal("§e[P2P] Starting UDP Client for key: " + key), false);
//
//// Create Connection & Lookup
//activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, context.getSource());
//		activeConnection.lookup(key);
//
//										return 1;
//												})
//												)
//												)
//												);
//												});