package net.calibur.simplep2p;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UDPConnection {

    private DatagramSocket udpSocket;
    private boolean running = false;
    private InetAddress signalingIP;
    private int signalingPort;
    private InetAddress peerIP;
    private int peerPort;
    private boolean isConnectedToPeer = false;

    private ServerSocket gameListener;
    private Socket gameSocket;
    private InputStream gameIn;
    private OutputStream gameOut;
    private boolean isHost = false;

    private CommandSourceStack source;

    public UDPConnection(String signalIp, int signalPort, CommandSourceStack source) {
        this.source = source;
        try {
            this.signalingIP = InetAddress.getByName(signalIp);
            this.signalingPort = signalPort;
            this.udpSocket = new DatagramSocket();
            this.running = true;
            new Thread(this::listenLoop).start();
        } catch (Exception e) {
            log("§c[Error] Failed to start UDP Socket: " + e.getMessage());
        }
    }

    // 1. The Listener Loop (Background Thread)
    private void listenLoop() {
        byte[] buffer = new byte[4096];

        while (running && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                // Separate Control Messages from Game Data
                // We'll use a simple trick: If packet starts with "CTRL:", it's a command.
                // If it's binary garbage, it's game data.
                String textData = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                if (textData.startsWith("CTRL:")) {
                    handleControlMessage(textData.substring(5), packet.getAddress(), packet.getPort());
                } else {
                    if (gameOut != null && isConnectedToPeer) {
                        gameOut.write(packet.getData(), 0, packet.getLength());
                        gameOut.flush();
                    }
                }

            } catch (Exception e) {
                if (running) System.out.println("UDP Error: " + e.getMessage());
            }
        }
    }

    private void handleControlMessage(String msg, InetAddress ip, int port) {
        if (msg.startsWith("PEER")) {
            // "PEER IP PORT
            String[] parts = msg.split(" ");

            try {
                this.peerIP = InetAddress.getByName(parts[1]);
                this.peerPort = Integer.parseInt(parts[2]);
                log("§e[P2P] Peer Found at " + peerIP + ":" + peerPort);
                startHolePunching();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (msg.equals("HELLO")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION ESTABLISHED! <<<");

                // If we are the Client, tell the user to join localhost now
                if (!isHost) {
                    log("§b[ACTION] join localhost:33333 to play!");
                }
            }
            sendControl("HELLO_CONFIRM", peerIP, peerPort);
        } else if (msg.equals("HELLO_CONFIRM")) {
            if (!isConnectedToPeer) {
                isConnectedToPeer = true;
                log("§a>>> CONNECTION CONFIRMED! <<<");
            }
        }
    }


    // --- 3. START PROXY (The Bridge) ---

    // CALLED BY HOST: Connects to the real local server (port 25565)
    public void startHostProxy(int targetPort) {
        this.isHost = true;
        new Thread(() -> {
            try {
                gameSocket = new Socket("127.0.0.1", targetPort);
                gameIn = gameSocket.getInputStream();
                gameOut = gameSocket.getOutputStream();
                pumpGameToNetwork();
            } catch (Exception e) {
                log("§c[Proxy Error] Is the LAN world open? " + e.getMessage());
            }
        }).start();
    }

    // CALLED BY CLIENT: Opens a fake server (port 33333) for the player to join
    public void startClientProxy() {
        this.isHost = false;
        new Thread(() -> {
            try {
                gameListener = new ServerSocket(33333);
                log("§e[Proxy] Listening for you on localhost:33333...");

                // Wait for the player to click "Direct Connect" -> "localhost:33333"
                gameSocket = gameListener.accept();
                log("§a[Proxy] You connected! Tunneling data...");

                gameIn = gameSocket.getInputStream();
                gameOut = gameSocket.getOutputStream();

                pumpGameToNetwork();

            } catch (Exception e) {
                log("§c[Proxy Error] " + e.getMessage());
            }
        }).start();
    }

    // --- 4. THE PUMP (Game -> Network) ---
    private void pumpGameToNetwork() {
        byte[] buffer = new byte[4096];
        try {
            while (running) {
                // Read from Game (TCP)
                int bytesRead = gameIn.read(buffer);
                if (bytesRead == -1) break; // Connection closed

                // Send to Network (UDP)
                if (isConnectedToPeer) {
                    DatagramPacket packet = new DatagramPacket(buffer, bytesRead, peerIP, peerPort);
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            log("§c[Pump Error] " + e.getMessage());
        }
    }


    // --- HELPERS ---
    public void register(String key) {
        sendControl("REGISTER " + key, signalingIP, signalingPort);
    }

    public void lookup(String key) {
        sendControl("LOOKUP " + key, signalingIP, signalingPort);
    }

    private void sendControl(String msg, InetAddress ip, int port) {
        try {
            byte[] data = ("CTRL:" + msg).getBytes(StandardCharsets.UTF_8);
            udpSocket.send(new DatagramPacket(data, data.length, ip, port));
        } catch (Exception e) {
        }
    }

    private void startHolePunching() {
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                if (isConnectedToPeer) break;
                sendControl("HELLO", peerIP, peerPort);
                try {
                    Thread.sleep(200);
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void log(String s) {
        if (source != null) source.sendSuccess(() -> Component.literal(s), false);
    }

    public void close() {
        running = false;
        try {
            if (udpSocket != null) udpSocket.close();
        } catch (Exception e) {
        }
        try {
            if (gameSocket != null) gameSocket.close();
        } catch (Exception e) {
        }
        try {
            if (gameListener != null) gameListener.close();
        } catch (Exception e) {
        }
    }
}