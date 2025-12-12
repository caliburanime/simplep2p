package net.calibur.simplep2p;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component; // "Text" became "Component"
import net.minecraft.commands.Commands; // "CommandManager" became "Commands"
import java.util.UUID;

// We import "literal" from Commands now
import static net.minecraft.commands.Commands.literal;

public class SimpleP2P implements ModInitializer {

	public static String currentSessionKey = "";

	@Override
	public void onInitialize() {
		System.out.println("SimpleP2P is loading (Mojang Mappings)!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

			dispatcher.register(literal("p2p")
					.then(literal("host")
							.executes(context -> {
								// 1. Generate Key
								currentSessionKey = UUID.randomUUID().toString().substring(0, 8);

								// 2. Send Message
								// In Mojang mappings: "sendFeedback" is often "sendSuccess"
								// And "Text.literal" is "Component.literal"
								context.getSource().sendSuccess(() -> Component.literal("§a[P2P] Session Started!"), false);
								context.getSource().sendSuccess(() -> Component.literal("§eSecret Key: §f" + currentSessionKey), false);

								return 1;
							})
					)
			);
		});
	}
}