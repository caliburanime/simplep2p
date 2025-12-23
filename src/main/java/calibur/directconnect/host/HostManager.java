package calibur.directconnect.host;

import calibur.directconnect.config.ModConfig;
import calibur.directconnect.network.ReliableUdp;
import calibur.directconnect.network.NetworkUtils;
import calibur.directconnect.network.RegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages hosting a Minecraft server over P2P.
 * Handles registration with registry and bridging UDP connections to the
 * internal server.
 */
public class HostManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");
    private static final int MC_SERVER_PORT = 25565; // Internal MC server port

    private static HostManager instance;

    private final ModConfig config;
    private final RegistryClient registry;
    private final ReliableUdp udp;
    private final ExecutorService executor;

    private DatagramSocket punchSocket;
    private final ConcurrentHashMap<InetSocketAddress, TcpBridge> bridges = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectionIdCounter = new AtomicInteger(0);

    private Consumer<String> onStatusChange;
    private String currentStatus = "Not hosting";

    private HostManager() {
        this.config = ModConfig.getInstance();
        this.registry = new RegistryClient();
        this.udp = new ReliableUdp();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "DirectConnect-Host");
            t.setDaemon(true);
            return t;
        });

        setupEventHandlers();
    }

    public static synchronized HostManager getInstance() {
        if (instance == null) {
            instance = new HostManager();
        }
        return instance;
    }

    private void setupEventHandlers() {
        // Handle punch requests from registry
        registry.setOnPunchRequest(punch -> {
            LOGGER.info("[DirectConnect] Punch request from {}:{}", punch.clientIp, punch.clientPort);

            // Send dummy UDP packets to punch hole
            if (punchSocket != null && !punchSocket.isClosed()) {
                executor.submit(() -> {
                    NetworkUtils.punchHole(punchSocket, punch.clientIp, punch.clientPort, 5);
                });
            }
        });

        // Handle code assignment
        registry.setOnCodeAssigned(code -> {
            updateStatus("Hosting: p2p." + code);
        });

        // Handle registry disconnect
        registry.setOnDisconnect(reason -> {
            LOGGER.warn("[DirectConnect] Registry disconnected: {}", reason);
            updateStatus("Registry disconnected");
        });

        // Handle UDP connections
        udp.setOnConnect(sender -> {
            LOGGER.info("[DirectConnect] Client connected via UDP from {}", sender);

            // Create TCP bridge to internal MC server
            executor.submit(() -> {
                try {
                    int connId = connectionIdCounter.incrementAndGet();
                    TcpBridge bridge = new TcpBridge(connId, sender, "127.0.0.1", MC_SERVER_PORT);
                    bridges.put(sender, bridge);
                    bridge.start();
                } catch (Exception e) {
                    LOGGER.error("[DirectConnect] Failed to create bridge: {}", e.getMessage());
                }
            });
        });

        // Handle UDP data
        udp.setOnData((sender, data) -> {
            TcpBridge bridge = bridges.get(sender);
            if (bridge != null) {
                bridge.sendToTcp(data);
            }
        });

        // Handle UDP disconnect
        udp.setOnDisconnect(sender -> {
            LOGGER.info("[DirectConnect] Client disconnected: {}", sender);
            TcpBridge bridge = bridges.remove(sender);
            if (bridge != null) {
                bridge.close();
            }
        });
    }

    /**
     * Starts hosting the server.
     * 
     * @return true if started successfully
     */
    public boolean start() {
        if (running.get()) {
            LOGGER.warn("[DirectConnect] Already hosting");
            return false;
        }

        LOGGER.info("[DirectConnect] Starting P2P host...");
        updateStatus("Starting...");

        try {
            // 1. Get UDP port
            int port = config.getUdpPort();

            // 2. Create separate socket for hole punching
            punchSocket = NetworkUtils.createUdpSocket();
            LOGGER.info("[DirectConnect] Punch socket on port {}", punchSocket.getLocalPort());

            // 3. Start reliable UDP server
            if (!udp.startServer(port)) {
                throw new IOException("Failed to start UDP server");
            }
            LOGGER.info("[DirectConnect] UDP server started on port {}", port);

            // 4. Get local and public IP
            String localIp = NetworkUtils.getLocalIp();

            // Use STUN to detect public WAN IP (works behind CGNAT)
            java.net.InetSocketAddress publicAddr = NetworkUtils.getPublicAddress(punchSocket);
            String wanIp = publicAddr != null ? publicAddr.getAddress().getHostAddress() : null;
            int wanPort = publicAddr != null ? publicAddr.getPort() : port;

            if (wanIp != null) {
                LOGGER.info("[DirectConnect] STUN detected WAN: {}:{}", wanIp, wanPort);
            } else {
                LOGGER.warn("[DirectConnect] STUN failed, using local IP only");
            }

            // 5. Register with registry (send both local and WAN IP)
            String shareCode = config.getShareCode();

            registry.register(localIp, port, shareCode, wanIp, wanPort)
                    .thenAccept(success -> {
                        if (success) {
                            running.set(true);
                            updateStatus("Hosting: p2p." + config.getShareCode());
                            LOGGER.info("[DirectConnect] Hosting started: p2p.{}", config.getShareCode());
                        } else {
                            LOGGER.error("[DirectConnect] Failed to register with registry");
                            stop();
                        }
                    })
                    .exceptionally(e -> {
                        LOGGER.error("[DirectConnect] Registration failed: {}", e.getMessage());
                        stop();
                        return null;
                    });

            return true;
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to start hosting: {}", e.getMessage());
            stop();
            return false;
        }
    }

    /**
     * Stops hosting the server.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        LOGGER.info("[DirectConnect] Stopping P2P host...");

        // Close all bridges
        bridges.values().forEach(TcpBridge::close);
        bridges.clear();

        // Stop UDP
        udp.stop();

        // Close punch socket
        if (punchSocket != null && !punchSocket.isClosed()) {
            punchSocket.close();
        }

        // Disconnect from registry
        registry.disconnect();

        updateStatus("Not hosting");
        LOGGER.info("[DirectConnect] Hosting stopped");
    }

    /**
     * Regenerates the share code.
     * 
     * @return The new share code
     */
    public String regenerateCode() {
        if (registry.isConnected()) {
            registry.requestRegenerate();
            return config.getShareCode(); // Will be updated async
        } else {
            return config.regenerateShareCode();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getShareCode() {
        return config.getShareCode();
    }

    public String getFullUri() {
        return config.getFullShareUri();
    }

    public String getStatus() {
        return currentStatus;
    }

    public void setOnStatusChange(Consumer<String> handler) {
        this.onStatusChange = handler;
    }

    private void updateStatus(String status) {
        this.currentStatus = status;
        if (onStatusChange != null) {
            onStatusChange.accept(status);
        }
    }

    // --- TCP Bridge (UDP <-> MC Server) ---

    private class TcpBridge {
        private final int connectionId;
        private final InetSocketAddress remoteAddress;
        private Socket tcpSocket;
        private InputStream tcpIn;
        private OutputStream tcpOut;
        private volatile boolean active = false;

        TcpBridge(int connectionId, InetSocketAddress remoteAddress, String host, int port) throws IOException {
            this.connectionId = connectionId;
            this.remoteAddress = remoteAddress;
            this.tcpSocket = new Socket(host, port);
            this.tcpSocket.setTcpNoDelay(true);
            this.tcpIn = tcpSocket.getInputStream();
            this.tcpOut = tcpSocket.getOutputStream();
        }

        void start() {
            active = true;

            // Read from TCP and send via UDP
            executor.submit(() -> {
                byte[] buffer = new byte[4096];
                try {
                    while (active && !tcpSocket.isClosed()) {
                        int read = tcpIn.read(buffer);
                        if (read == -1)
                            break;

                        byte[] data = new byte[read];
                        System.arraycopy(buffer, 0, data, 0, read);

                        udp.sendTo(remoteAddress, data);
                    }
                } catch (IOException e) {
                    if (active) {
                        LOGGER.debug("[DirectConnect] Bridge read error: {}", e.getMessage());
                    }
                } finally {
                    close();
                }
            });
        }

        void sendToTcp(byte[] data) {
            if (!active || tcpSocket.isClosed())
                return;

            try {
                tcpOut.write(data);
                tcpOut.flush();
            } catch (IOException e) {
                LOGGER.debug("[DirectConnect] Bridge write error: {}", e.getMessage());
                close();
            }
        }

        void close() {
            active = false;
            try {
                if (tcpSocket != null && !tcpSocket.isClosed()) {
                    tcpSocket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
