package net.calibur.simplep2p;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer; // Import this!
import net.minecraft.commands.CommandSourceStack;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UDPConnection {

    private DatagramSocket udpSocket;
    private boolean running = false;
    private InetAddress peerIP;
    private int peerPort;
    private boolean isConnectedToPeer = false;

    // SIGNALING
    private InetAddress signalingIP;
    private int signalingPort;

    // PROXY VARIABLES
    private ServerSocket gameListener;
    private Socket gameSocket;
    private InputStream gameIn;
    private OutputStream gameOut;
    private boolean isHost = false;

    private CommandSourceStack source;
    private MinecraftServer server; // NEW: Reference to the server engine

    private static final int PACKET_SIZE = 1024;
    private static final byte HEADER_GAME_DATA = 0x01;
    private static final byte HEADER_CONTROL   = 0x02;

    // UPDATED CONSTRUCTOR: Now accepts 'MinecraftServer server'
    public UDPConnection(String signalIp, int signalPort, CommandSourceStack source, MinecraftServer server) {
        this.source = source;
        this.server = server; // Save it
        try {
            this.signalingIP = InetAddress.getByName(signalIp);
            this.signalingPort = signalPort;
            this.udpSocket = new DatagramSocket();
            this.running = true;
            new Thread(this::udpListenLoop).start();
        } catch (Exception e) {
            log("§c[Error] Start Failed: " + e.getMessage());
        }
    }

    private void udpListenLoop() {
        byte[] buffer = new byte[PACKET_SIZE + 100];

        while (running && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                byte[] data = packet.getData();
                int len = packet.getLength();
                if (len < 1) continue;
                byte packetType = data[0];

                if (packetType == HEADER_CONTROL) {
                    String msg = new String(data, 1, len - 1, StandardCharsets.UTF_8);
                    handleControlMessage(msg, packet.getAddress(), packet.getPort());
                }
                else if (packetType == HEADER_GAME_DATA) {
                    if (gameOut != null && isConnectedToPeer) {
                        gameOut.write(data, 1, len - 1);
                        gameOut.flush();
                    }
                }
            } catch (SocketException e) {
                // Ignore Windows "Connection Reset" errors
                if (e.getMessage().contains("Connection reset") || e.getMessage().contains("connection was aborted")) continue;
                if (running) System.out.println("UDP Error: " + e.getMessage());
            } catch (Exception e) {
                if (running) System.out.println("UDP Error: " + e.getMessage());
            }
        }
    }

    private void handleControlMessage(String msg, InetAddress ip, int port) {
        if (msg.startsWith("PEER")) {
            String[] parts = msg.split(" ");
            try {
                this.peerIP = InetAddress.getByName(parts[1]);
                this.peerPort = Integer.parseInt(parts[2]);
                log("§e[P2P] Peer Found at " + peerIP + ":" + peerPort);
                startHolePunching();
            } catch (Exception e) { e.printStackTrace(); }
        }
        else if (msg.equals("HELLO")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION ESTABLISHED! <<<");

                // --- NEW FIX: THE DOORBELL ---
                // If we are the Host, wake up the server!
                if (isHost && server != null) {
                    System.out.println("[SimpleP2P] Ringing the doorbell to wake up the server...");
                    // This forces the main thread to execute a task, breaking the "Sleep" loop.
                    server.execute(() -> {
                        System.out.println("[SimpleP2P] Server Woken Up!");
                    });
                }
            }
            sendControl("HELLO_CONFIRM", peerIP, peerPort);
        }
        else if (msg.equals("HELLO_CONFIRM")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION CONFIRMED! <<<");

                // Same fix for confirm, just in case
                if (isHost && server != null) {
                    server.execute(() -> System.out.println("[SimpleP2P] Server Woken Up (Confirm)!"));
                }
            }
        }
    }

    public void startHostProxy(int targetPort) {
        this.isHost = true;
        new Thread(() -> {
            try {
                gameSocket = new Socket("127.0.0.1", targetPort);
                gameIn = gameSocket.getInputStream();
                gameOut = gameSocket.getOutputStream();
                pumpGameToNetwork();
            } catch (Exception e) {
                log("§c[Proxy Error] " + e.getMessage());
            }
        }).start();
    }

    public void startClientProxy() {
        this.isHost = false;
        new Thread(() -> {
            try {
                gameListener = new ServerSocket(33333);
                gameSocket = gameListener.accept();
                gameIn = gameSocket.getInputStream();
                gameOut = gameSocket.getOutputStream();
                pumpGameToNetwork();
            } catch (Exception e) {
                log("§c[Proxy Error] " + e.getMessage());
            }
        }).start();
    }

    private void pumpGameToNetwork() {
        byte[] chunk = new byte[PACKET_SIZE];
        try {
            while (running) {
                int bytesRead = gameIn.read(chunk);
                if (bytesRead == -1) break;

                if (isConnectedToPeer) {
                    byte[] packetData = new byte[bytesRead + 1];
                    packetData[0] = HEADER_GAME_DATA;
                    System.arraycopy(chunk, 0, packetData, 1, bytesRead);
                    DatagramPacket packet = new DatagramPacket(packetData, packetData.length, peerIP, peerPort);
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            log("§c[Pump Error] " + e.getMessage());
        }
    }

    public void register(String key) { sendControl("REGISTER " + key, signalingIP, signalingPort); }
    public void lookup(String key) { sendControl("LOOKUP " + key, signalingIP, signalingPort); }

    private void sendControl(String msg, InetAddress ip, int port) {
        try {
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
            byte[] packetData = new byte[msgBytes.length + 1];
            packetData[0] = HEADER_CONTROL;
            System.arraycopy(msgBytes, 0, packetData, 1, msgBytes.length);
            udpSocket.send(new DatagramPacket(packetData, packetData.length, ip, port));
        } catch (Exception e) {}
    }

    private void startHolePunching() {
        new Thread(() -> {
            for (int i=0; i<10; i++) {
                if (isConnectedToPeer) break;
                sendControl("HELLO", peerIP, peerPort);
                try { Thread.sleep(200); } catch (Exception e) {}
            }
        }).start();
    }

    private void log(String s) {
        if (source != null) source.sendSuccess(() -> Component.literal(s), false);
        else System.out.println("[SimpleP2P] " + s);
    }

    public void close() {
        running = false;
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception e) {}
        try { if (gameSocket != null) gameSocket.close(); } catch (Exception e) {}
        try { if (gameListener != null) gameListener.close(); } catch (Exception e) {}
    }
}