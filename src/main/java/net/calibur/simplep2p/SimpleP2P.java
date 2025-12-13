package net.calibur.simplep2p;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import java.util.UUID;
import static net.minecraft.commands.Commands.literal;

import java.net.Socket;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public class SimpleP2P implements ModInitializer {

	public static String currentSessionKey = "";

	private static final String SIGNALING_IP = "127.0.0.1";
	private static final int SIGNALING_PORT = 5000;

	@Override
	public void onInitialize() {
		System.out.println("SimpleP2P is loading!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

			dispatcher.register(literal("p2p")
					.then(literal("host")
							.executes(context -> {
								// 1. Generate Key
								currentSessionKey = UUID.randomUUID().toString().substring(0, 8);

								// 2. Tell the player locally
								context.getSource().sendSuccess(() -> Component.literal("§e[P2P] Registering key: " + currentSessionKey + "..."), false);

								// 3. START BACKGROUND THREAD (The "Worker")
								new Thread(() -> {
									try {
										// --- NETWORKING LOGIC STARTS ---

										// A. Open connection to Python Server
										Socket socket = new Socket(SIGNALING_IP, SIGNALING_PORT);

										// B. Prepare message: "REGISTER <KEY>"
										String message = "REGISTER " + currentSessionKey;

										// C. Send Data
										OutputStream out = socket.getOutputStream();
										out.write(message.getBytes(StandardCharsets.UTF_8));

										// D. Listen for "OK" response
										InputStream in = socket.getInputStream();
										byte[] buffer = new byte[1024];
										int bytesRead = in.read(buffer);
										String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

										// E. Close connection
										socket.close();

										// --- NETWORKING ENDS ---

										// F. Report back to Player
										if (response.equals("OK")) {
											// We need to be careful sending chat from a background thread, but for simple text it's usually safe.
											context.getSource().sendSuccess(() -> Component.literal("§a[P2P] Success! Server registered your key."), false);
										} else {
											context.getSource().sendSuccess(() -> Component.literal("§c[P2P] Server Error: " + response), false);
										}

									} catch (Exception e) {
										// If Python server is offline, this happens
										e.printStackTrace();
										context.getSource().sendSuccess(() -> Component.literal("§c[P2P] Connection Failed! Is the server running?"), false);
									}
								}).start();
								// .start() actually launches the thread

								return 1;
							})
					)
			);
		});
	}
}