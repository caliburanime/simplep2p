package net.calibur.simplep2p.mixin;


import net.calibur.simplep2p.networking.PacketLogger;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {

    // TARGET: public static void configurePacketHandler(ChannelPipeline pipeline)
    @Inject(method = "configurePacketHandler", at = @At("RETURN"))
    private static void onConfigurePacketHandler(ChannelPipeline pipeline, CallbackInfo ci) {
        // We now have direct access to the pipeline, no need to call channel.pipeline()

        // Ensure we don't inject twice
        if (pipeline.get("simple2p2_logger") == null) {
            // "packet_handler" is the name Minecraft uses for its main handler.
            // We insert our logger just before it to intercept traffic.
            try {
                pipeline.addBefore("packet_handler", "simple2p2_logger", new PacketLogger());
                System.out.println("[Simple2P2] SUCCESSS: Injected Network Logger into pipeline!");
            } catch (Exception e) {
                // If "packet_handler" doesn't exist yet (rare), just add it to the end
                pipeline.addLast("simple2p2_logger", new PacketLogger());
                System.out.println("[Simple2P2] WARNING: 'packet_handler' not found, added logger to end.");
            }
        }
    }
}