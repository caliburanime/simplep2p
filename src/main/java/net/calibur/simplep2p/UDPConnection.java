package net.calibur.simplep2p;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
    private ServerSocket gameListener; // Client side listener
    private Socket gameSocket;         // The active pipe to the game
    private InputStream gameIn;
    private OutputStream gameOut;
    private boolean isHost = false;

    // We need to remember the local port to restart the proxy later
    private int localServerPort = -1;

    private CommandSourceStack source;
    private MinecraftServer server;

    private static final int PACKET_SIZE = 1024;
    private static final byte HEADER_GAME_DATA = 0x01;
    private static final byte HEADER_CONTROL   = 0x02;

    public UDPConnection(String signalIp, int signalPort, CommandSourceStack source, MinecraftServer server) {
        this.source = source;
        this.server = server;
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
                        try {
                            gameOut.write(data, 1, len - 1);
                            gameOut.flush();
                        } catch (SocketException e) {
                            // If writing to game fails (server closed connection), stop being connected
                            isConnectedToPeer = false;
                        }
                    }
                }
            } catch (SocketException e) {
                if (e.getMessage().contains("Connection reset") || e.getMessage().contains("connection was aborted")) continue;
                if (running) System.out.println("UDP Error: " + e.getMessage());
            } catch (Exception e) {
                if (running) System.out.println("UDP Error: " + e.getMessage());
            }
        }
    }

    private void handleControlMessage(String msg, InetAddress ip, int port) {
        if (msg.startsWith("PEER")) {
            // FIX 1: NEW PEER INFO MEANS NEW SESSION!
            // We must reset the state so the handshake happens again.
            isConnectedToPeer = false;

            String[] parts = msg.split(" ");
            try {
                this.peerIP = InetAddress.getByName(parts[1]);
                this.peerPort = Integer.parseInt(parts[2]);
                log("§e[P2P] Peer Found at " + peerIP + ":" + peerPort);
                startHolePunching();
            } catch (Exception e) { e.printStackTrace(); }
        }
        else if (msg.equals("HELLO")) {
            // Only run init logic if we weren't already connected
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION ESTABLISHED! <<<");

                // FIX 2: RESTART THE PROXY
                // When a new player connects, we need to open a NEW connection to the local server.
                // The old socket is likely dead/closed.
                if (isHost && localServerPort != -1) {
                    restartHostProxy();
                    wakeUpServer();
                }
            }
            // Always confirm so they know we heard them
            sendControl("HELLO_CONFIRM", peerIP, peerPort);
        }
        else if (msg.equals("HELLO_CONFIRM")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION CONFIRMED! <<<");

                if (isHost && localServerPort != -1) {
                    restartHostProxy();
                    wakeUpServer();
                }
            }
        }
    }

    private void wakeUpServer() {
        if (server != null) {
            System.out.println("[SimpleP2P] Ringing Doorbell (Wake Up)...");
            server.execute(() -> System.out.println("[SimpleP2P] Server Woken Up!"));
        }
    }

    // --- PROXY LOGIC ---

    public void startHostProxy(int targetPort) {
        this.isHost = true;
        this.localServerPort = targetPort; // Save for later restarts
        // We don't start the thread immediately here anymore.
        // We wait for the handshake (HELLO) to ensure we connect at the right time.
    }

    private void restartHostProxy() {
        // Close old socket if exists
        try { if (gameSocket != null) gameSocket.close(); } catch (Exception e) {}

        new Thread(() -> {
            try {
                System.out.println("[SimpleP2P] Connecting to local server port " + localServerPort);
                gameSocket = new Socket("127.0.0.1", localServerPort);
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
                if (bytesRead == -1) break; // Local server closed connection

                if (isConnectedToPeer) {
                    byte[] packetData = new byte[bytesRead + 1];
                    packetData[0] = HEADER_GAME_DATA;
                    System.arraycopy(chunk, 0, packetData, 1, bytesRead);
                    DatagramPacket packet = new DatagramPacket(packetData, packetData.length, peerIP, peerPort);
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            // This is normal when a player leaves
        } finally {
            // If the loop exits, the game session is over.
            // Reset connection state so the next join works.
            isConnectedToPeer = false;
        }
    }

    // --- HELPERS ---
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