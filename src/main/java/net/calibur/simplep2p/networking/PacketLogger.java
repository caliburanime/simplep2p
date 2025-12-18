package net.calibur.simplep2p.networking;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketLogger extends ChannelDuplexHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Simple2P2-Net");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // INBOUND: Data coming FROM the server TO the client
        // 'msg' is usually a Packet<?> object in Minecraft's pipeline,
        // or a ByteBuf if we inject very early.

        LOGGER.info("[INBOUND] Received packet: {}", msg.getClass().getSimpleName());

        // Pass it to the next handler (Minecraft), otherwise the game freezes
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // OUTBOUND: Data going FROM the client TO the server

        LOGGER.info("[OUTBOUND] Sending packet: {}", msg.getClass().getSimpleName());

        // Pass it down the wire
        super.write(ctx, msg, promise);
    }
}