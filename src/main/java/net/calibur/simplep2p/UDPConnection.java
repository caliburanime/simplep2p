package net.calibur.simplep2p;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;

import java.net.*;
import java.nio.charset.StandardCharsets;

public class UDPConnection {

    private DatagramSocket socket;
    private boolean running = false;
    private InetAddress signalingIP;
    private int signalingPort;

    // Where is the Peer?
    private InetAddress peerIP;
    private int peerPort;
    private boolean isConnectedToPeer = false;

    // Helper to print to chat
    private CommandSourceStack source;

    public UDPConnection(String signalIp, int signalPort, CommandSourceStack source) {
        this.source = source;
        try {
            this.signalingIP = InetAddress.getByName(signalIp);
            this.signalingPort = signalPort;

            // JAVA TRICK: new DatagramSocket() automatically binds to a random available port (like bind(0))
            this.socket = new DatagramSocket();
            this.running = true;

            // Start listening immediately
            new Thread(this::listenLoop).start();

        } catch (Exception e) {
            log("§c[Error] Failed to start UDP Socket: " + e.getMessage());
        }
    }

    // 1. The Listener Loop (Background Thread)
    private void listenLoop() {
        byte[] buffer = new byte[2048];

        while (running && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();

                // Logic to handle messages
                handleMessage(msg, packet.getAddress(), packet.getPort());

            } catch (Exception e) {
                if (running) log("§c[Listener Error] " + e.getMessage());
            }
        }
    }

    // 2. Message Handler
    private void handleMessage(String msg, InetAddress senderIP, int senderPort) {
        // A. Message from Signaling Server
        if (msg.startsWith("PEER")) {
            // Format: "PEER <IP> <PORT>"
            String[] parts = msg.split(" ");
            try {
                this.peerIP = InetAddress.getByName(parts[1]);
                this.peerPort = Integer.parseInt(parts[2]);

                log("§e[P2P] Received Peer Info: " + peerIP + ":" + peerPort);
                log("§e[P2P] Punching hole through firewall...");

                // Start the punching process
                startHolePunching();

            } catch (Exception e) {
                log("§c[P2P] Failed to parse Peer info: " + e.getMessage());
            }
        }

        // B. Message from the Peer (The other player!)
        else if (msg.equals("HELLO_PEER")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> P2P CONNECTION ESTABLISHED! <<<");
                log("§aYou are now directly connected to the other player.");
            }
            // Send one back to confirm we heard them
            sendRaw("HELLO_CONFIRM", peerIP, peerPort);
        }

        else if (msg.equals("HELLO_CONFIRM")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> P2P CONNECTION ESTABLISHED! <<<");
            }
        }
    }

    // 3. Helper to send data
    private void sendRaw(String msg, InetAddress ip, int port) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, ip, port);
            socket.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 4. Public Actions
    public void register(String key) {
        sendRaw("REGISTER " + key, signalingIP, signalingPort);
    }

    public void lookup(String key) {
        sendRaw("LOOKUP " + key, signalingIP, signalingPort);
    }

    // The "Punch" Logic: Send dummy packets repeatedly to force the router open
    private void startHolePunching() {
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                if (isConnectedToPeer) break; // Stop if we are already connected

                sendRaw("HELLO_PEER", peerIP, peerPort);

                try { Thread.sleep(200); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void log(String text) {
        // Send message to Minecraft Chat
        if (source != null) {
            source.sendSuccess(() -> Component.literal(text), false);
        }
    }

    public void close() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
    }
}