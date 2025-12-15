package net.calibur.simplep2p;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import java.util.UUID;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;


public class SimpleP2P implements ModInitializer {

	// Global reference to our active connection
	public static UDPConnection activeConnection;

	// CONFIG: Change this to your server IP later!
	private static final String SIGNALING_IP = "127.0.0.1";
	private static final int SIGNALING_PORT = 5000;

	@Override
	public void onInitialize() {
		System.out.println("SimpleP2P Phase 7 (UDP) Loading...");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("p2p")

					// --- HOST ---
					.then(literal("host")
							.executes(context -> {
								// Close old connection if exists
								if (activeConnection != null) activeConnection.close();

								String key = UUID.randomUUID().toString().substring(0, 8);
								context.getSource().sendSuccess(() -> Component.literal("§e[P2P] Starting UDP Host with key: " + key), false);

								// Create Connection & Register
								activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, context.getSource());
								activeConnection.register(key);

								return 1;
							})
					)

					// --- JOIN ---
					.then(literal("join")
							.then(argument("secretKey", StringArgumentType.word())
									.executes(context -> {
										// Close old connection if exists
										if (activeConnection != null) activeConnection.close();

										String key = StringArgumentType.getString(context, "secretKey");
										context.getSource().sendSuccess(() -> Component.literal("§e[P2P] Starting UDP Client for key: " + key), false);

										// Create Connection & Lookup
										activeConnection = new UDPConnection(SIGNALING_IP, SIGNALING_PORT, context.getSource());
										activeConnection.lookup(key);

										return 1;
									})
							)
					)
			);
		});
	}
}