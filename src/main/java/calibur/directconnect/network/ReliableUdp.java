package calibur.directconnect.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.bootstrap.Bootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Simple reliable UDP wrapper using Netty (bundled with Minecraft).
 * Implements basic reliability through acknowledgments and retransmission.
 * 
 * For the P2P bridge, we use this for signaling and small control messages.
 * The actual game data is tunneled over TCP-in-UDP for reliability.
 */
public class ReliableUdp {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

    // Protocol constants
    private static final byte MSG_DATA = 0x01; // Regular data packet
    private static final byte MSG_ACK = 0x02; // Acknowledgment
    private static final byte MSG_HELLO = 0x03; // Connection handshake
    private static final byte MSG_HELLO_ACK = 0x04; // Handshake response
    private static final byte MSG_CLOSE = 0x05; // Connection close

    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 200;
    private static final int TIMEOUT_MS = 10000;

    private EventLoopGroup group;
    private Channel channel;
    private InetSocketAddress remoteAddress;
    private volatile boolean connected = false;
    private volatile boolean isServer = false;

    private final AtomicInteger sequenceNumber = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, PendingPacket> pendingAcks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Callbacks
    private BiConsumer<InetSocketAddress, byte[]> onData;
    private Consumer<InetSocketAddress> onConnect;
    private Consumer<InetSocketAddress> onDisconnect;

    /**
     * Represents a packet waiting for acknowledgment.
     */
    private static class PendingPacket {
        final int seqNum;
        final byte[] data;
        final InetSocketAddress target;
        int retries = 0;
        ScheduledFuture<?> retryFuture;

        PendingPacket(int seqNum, byte[] data, InetSocketAddress target) {
            this.seqNum = seqNum;
            this.data = data;
            this.target = target;
        }
    }

    /**
     * Starts a UDP server (for host).
     * 
     * @param port Port to listen on
     * @return true if started successfully
     */
    public boolean startServer(int port) {
        try {
            group = new NioEventLoopGroup();
            isServer = true;

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ch.pipeline().addLast(new PacketHandler());
                        }
                    });

            channel = bootstrap.bind(port).sync().channel();
            connected = true;

            LOGGER.info("[DirectConnect] UDP server started on port {}", port);
            return true;
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to start UDP server: {}", e.getMessage());
            stop();
            return false;
        }
    }

    /**
     * Connects to a remote UDP endpoint (for client).
     * 
     * @param host Remote host
     * @param port Remote port
     * @return true if connected successfully
     */
    public boolean connect(String host, int port) {
        try {
            group = new NioEventLoopGroup();
            isServer = false;
            remoteAddress = new InetSocketAddress(host, port);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ch.pipeline().addLast(new PacketHandler());
                        }
                    });

            channel = bootstrap.bind(0).sync().channel();

            // Send HELLO to initiate connection
            sendHello(remoteAddress);

            LOGGER.info("[DirectConnect] Connecting to {}:{}", host, port);
            return true;
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to connect: {}", e.getMessage());
            stop();
            return false;
        }
    }

    /**
     * Sends data reliably to the connected remote.
     */
    public void send(byte[] data) {
        if (remoteAddress != null) {
            sendReliable(remoteAddress, data);
        }
    }

    /**
     * Sends data reliably to a specific address.
     */
    public void sendTo(InetSocketAddress target, byte[] data) {
        sendReliable(target, data);
    }

    /**
     * Sends data with reliability (acknowledgment + retransmission).
     */
    private void sendReliable(InetSocketAddress target, byte[] data) {
        if (channel == null || !channel.isActive())
            return;

        int seqNum = sequenceNumber.incrementAndGet();

        // Create packet: [MSG_DATA][seqNum (4 bytes)][data]
        ByteBuf buf = Unpooled.buffer(5 + data.length);
        buf.writeByte(MSG_DATA);
        buf.writeInt(seqNum);
        buf.writeBytes(data);

        DatagramPacket packet = new DatagramPacket(buf, target);
        channel.writeAndFlush(packet);

        // Schedule retransmission
        PendingPacket pending = new PendingPacket(seqNum, data, target);
        pending.retryFuture = scheduler.scheduleAtFixedRate(() -> {
            retryPacket(pending);
        }, RETRY_DELAY_MS, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);

        pendingAcks.put(seqNum, pending);
    }

    /**
     * Retries sending a packet that hasn't been acknowledged.
     */
    private void retryPacket(PendingPacket pending) {
        if (pending.retries >= MAX_RETRIES) {
            LOGGER.warn("[DirectConnect] Packet {} dropped after {} retries", pending.seqNum, MAX_RETRIES);
            pending.retryFuture.cancel(false);
            pendingAcks.remove(pending.seqNum);
            return;
        }

        pending.retries++;

        ByteBuf buf = Unpooled.buffer(5 + pending.data.length);
        buf.writeByte(MSG_DATA);
        buf.writeInt(pending.seqNum);
        buf.writeBytes(pending.data);

        DatagramPacket packet = new DatagramPacket(buf, pending.target);
        channel.writeAndFlush(packet);
    }

    /**
     * Sends a HELLO packet to initiate connection.
     */
    private void sendHello(InetSocketAddress target) {
        ByteBuf buf = Unpooled.buffer(1);
        buf.writeByte(MSG_HELLO);
        channel.writeAndFlush(new DatagramPacket(buf, target));
    }

    /**
     * Sends ACK for a received packet.
     */
    private void sendAck(InetSocketAddress target, int seqNum) {
        ByteBuf buf = Unpooled.buffer(5);
        buf.writeByte(MSG_ACK);
        buf.writeInt(seqNum);
        channel.writeAndFlush(new DatagramPacket(buf, target));
    }

    /**
     * Stops the UDP connection.
     */
    public void stop() {
        connected = false;

        // Cancel all pending retries
        for (PendingPacket pending : pendingAcks.values()) {
            if (pending.retryFuture != null) {
                pending.retryFuture.cancel(false);
            }
        }
        pendingAcks.clear();

        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }

        scheduler.shutdown();
        LOGGER.info("[DirectConnect] UDP stopped");
    }

    // --- Event Handlers ---

    public void setOnData(BiConsumer<InetSocketAddress, byte[]> handler) {
        this.onData = handler;
    }

    public void setOnConnect(Consumer<InetSocketAddress> handler) {
        this.onConnect = handler;
    }

    public void setOnDisconnect(Consumer<InetSocketAddress> handler) {
        this.onDisconnect = handler;
    }

    public boolean isConnected() {
        return connected;
    }

    public int getLocalPort() {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }
        return -1;
    }

    // --- Packet Handler ---

    private class PacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf buf = packet.content();
            InetSocketAddress sender = packet.sender();

            if (buf.readableBytes() < 1)
                return;

            byte msgType = buf.readByte();

            switch (msgType) {
                case MSG_HELLO:
                    // Respond with HELLO_ACK
                    ByteBuf ack = Unpooled.buffer(1);
                    ack.writeByte(MSG_HELLO_ACK);
                    ctx.writeAndFlush(new DatagramPacket(ack, sender));

                    if (isServer) {
                        remoteAddress = sender;
                        connected = true;
                        LOGGER.info("[DirectConnect] Client connected from {}", sender);
                        if (onConnect != null) {
                            onConnect.accept(sender);
                        }
                    }
                    break;

                case MSG_HELLO_ACK:
                    connected = true;
                    LOGGER.info("[DirectConnect] Connected to server {}", sender);
                    if (onConnect != null) {
                        onConnect.accept(sender);
                    }
                    break;

                case MSG_DATA:
                    if (buf.readableBytes() < 4)
                        return;
                    int seqNum = buf.readInt();

                    // Send ACK
                    sendAck(sender, seqNum);

                    // Extract data
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);

                    if (onData != null) {
                        onData.accept(sender, data);
                    }
                    break;

                case MSG_ACK:
                    if (buf.readableBytes() < 4)
                        return;
                    int ackSeq = buf.readInt();

                    PendingPacket pending = pendingAcks.remove(ackSeq);
                    if (pending != null && pending.retryFuture != null) {
                        pending.retryFuture.cancel(false);
                    }
                    break;

                case MSG_CLOSE:
                    LOGGER.info("[DirectConnect] Remote closed connection: {}", sender);
                    if (onDisconnect != null) {
                        onDisconnect.accept(sender);
                    }
                    break;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("[DirectConnect] UDP error: {}", cause.getMessage());
        }
    }
}
