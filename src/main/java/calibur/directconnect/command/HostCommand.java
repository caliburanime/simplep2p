package calibur.directconnect.command;

import calibur.directconnect.host.HostManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Command handler for /host command.
 * Starts P2P hosting for singleplayer worlds.
 */
public class HostCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("host")
                        .executes(HostCommand::executeHost)
                        .then(Commands.literal("stop")
                                .executes(HostCommand::executeStop))
                        .then(Commands.literal("status")
                                .executes(HostCommand::executeStatus))
                        .then(Commands.literal("regenerate")
                                .executes(HostCommand::executeRegenerate)));
    }

    private static int executeHost(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HostManager host = HostManager.getInstance();

        if (host.isRunning()) {
            source.sendSuccess(() -> Component.literal(
                    "§aAlready hosting: §b" + host.getFullUri()), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(
                "§eStarting P2P host..."), false);

        if (host.start()) {
            // Status will be updated async when registration completes
            host.setOnStatusChange(status -> {
                source.sendSuccess(() -> Component.literal(
                        "§a" + status), false);
            });
            return 1;
        } else {
            source.sendFailure(Component.literal(
                    "§cFailed to start P2P host. Check logs for details."));
            return 0;
        }
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HostManager host = HostManager.getInstance();

        if (!host.isRunning()) {
            source.sendFailure(Component.literal(
                    "§cNot currently hosting."));
            return 0;
        }

        host.stop();
        source.sendSuccess(() -> Component.literal(
                "§aP2P hosting stopped."), false);
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HostManager host = HostManager.getInstance();

        if (host.isRunning()) {
            source.sendSuccess(() -> Component.literal(
                    "§aHosting: §b" + host.getFullUri() + "\n" +
                            "§7Status: " + host.getStatus()),
                    false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§7Not hosting. Use §e/host§7 to start."), false);
        }
        return 1;
    }

    private static int executeRegenerate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HostManager host = HostManager.getInstance();

        String newCode = host.regenerateCode();
        source.sendSuccess(() -> Component.literal(
                "§aShare code regenerated: §b" + newCode + "\n" +
                        "§7Note: Players with the old code will need the new one."),
                false);
        return 1;
    }
}
