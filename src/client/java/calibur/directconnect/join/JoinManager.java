package calibur.directconnect.join;

import calibur.directconnect.config.ModConfig;
import calibur.directconnect.network.ReliableUdp;
import calibur.directconnect.network.NetworkUtils;
import calibur.directconnect.network.RegistryClient;
import calibur.directconnect.network.RegistryClient.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages joining a P2P server.
 * Handles registry lookup, endpoint racing, and local proxy.
 */
public class JoinManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

    private static JoinManager instance;

    private final ModConfig config;
    private final RegistryClient registry;
    private final ExecutorService executor;

    private ReliableUdp udp;
    private ServerSocket proxyServer;
    private Socket minecraftConnection;

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private Consumer<String> onStatusChange;
    private Consumer<Integer> onProxyReady;
    private Consumer<String> onError;

    private JoinManager() {
        this.config = ModConfig.getInstance();
        this.registry = new RegistryClient();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "DirectConnect-Join");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized JoinManager getInstance() {
        if (instance == null) {
            instance = new JoinManager();
        }
        return instance;
    }

    /**
     * Starts the join process for a P2P address.
     * 
     * @param p2pAddress The p2p. address (e.g., "p2p.happy-llama-42")
     * @return CompletableFuture with the local proxy port
     */
    public CompletableFuture<Integer> join(String p2pAddress) {
        if (connecting.get() || connected.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Already connecting/connected"));
        }

        connecting.set(true);
        updateStatus("Looking up host...");

        CompletableFuture<Integer> result = new CompletableFuture<>();

        // 1. Parse share code
        String shareCode = NetworkUtils.parseShareCode(p2pAddress);
        if (shareCode == null) {
            connecting.set(false);
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid P2P address: " + p2pAddress));
        }

        LOGGER.info("[DirectConnect] Joining: {}", shareCode);

        // 2. Start local proxy server
        try {
            proxyServer = new ServerSocket(0); // Random available port
            int proxyPort = proxyServer.getLocalPort();
            LOGGER.info("[DirectConnect] Local proxy started on port {}", proxyPort);

            // Start accepting connections in background
            executor.submit(() -> acceptMinecraftConnection(result));

            // 3. Lookup endpoints from registry
            registry.lookup(shareCode, proxyPort)
                    .thenAccept(endpoints -> {
                        if (endpoints.isEmpty()) {
                            updateStatus("Host not found");
                            triggerError("Host not found: " + shareCode);
                            cleanup();
                            result.completeExceptionally(new IOException("Host not found"));
                        } else {
                            updateStatus("Connecting to host...");
                            raceEndpoints(endpoints, result);
                        }
                    })
                    .exceptionally(e -> {
                        updateStatus("Lookup failed");
                        triggerError("Registry lookup failed: " + e.getMessage());
                        cleanup();
                        result.completeExceptionally(e);
                        return null;
                    });

        } catch (IOException e) {
            LOGGER.error("[DirectConnect] Failed to start proxy: {}", e.getMessage());
            connecting.set(false);
            return CompletableFuture.failedFuture(e);
        }

        return result;
    }

    /**
     * Races multiple endpoints to find the fastest path.
     */
    private void raceEndpoints(List<Endpoint> endpoints, CompletableFuture<Integer> result) {
        LOGGER.info("[DirectConnect] Racing {} endpoints", endpoints.size());

        AtomicBoolean found = new AtomicBoolean(false);
        AtomicInteger pendingAttempts = new AtomicInteger(endpoints.size());

        // Try all endpoints simultaneously
        for (Endpoint endpoint : endpoints) {
            executor.submit(() -> {
                LOGGER.debug("[DirectConnect] Trying endpoint: {}", endpoint);

                ReliableUdp testUdp = new ReliableUdp();

                testUdp.setOnConnect(sender -> {
                    if (found.compareAndSet(false, true)) {
                        LOGGER.info("[DirectConnect] Connected via {}", endpoint);

                        // Use this UDP connection
                        udp = testUdp;
                        connected.set(true);
                        connecting.set(false);
                        updateStatus("Connected!");

                        // Setup data handler
                        setupDataHandler();

                        // Complete the future with proxy port - this signals mixin to redirect
                        if (!result.isDone()) {
                            result.complete(proxyServer.getLocalPort());
                        }

                        // Also trigger callback if set (for backwards compatibility)
                        if (onProxyReady != null) {
                            onProxyReady.accept(proxyServer.getLocalPort());
                        }
                    } else {
                        // Another endpoint won, close this one
                        testUdp.stop();
                    }
                });

                if (!testUdp.connect(endpoint.ip, endpoint.port)) {
                    int remaining = pendingAttempts.decrementAndGet();
                    if (remaining == 0 && !found.get()) {
                        triggerError("Failed to connect to any endpoint");
                        cleanup();
                        result.completeExceptionally(new IOException("Connection failed"));
                    }
                }
            });
        }

        // Timeout after configured duration
        executor.submit(() -> {
            try {
                Thread.sleep(config.getConnectionTimeout());
                if (!found.get() && connecting.get()) {
                    LOGGER.warn("[DirectConnect] Connection timeout");
                    updateStatus("Connection timeout");
                    triggerError("Connection timed out - host may have strict NAT");
                    cleanup();
                    if (!result.isDone()) {
                        result.completeExceptionally(new IOException("Connection timeout"));
                    }
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    /**
     * Sets up the data handler for the connected UDP.
     */
    private void setupDataHandler() {
        if (udp != null) {
            udp.setOnData((sender, data) -> {
                // Forward data to Minecraft client
                if (minecraftConnection != null && !minecraftConnection.isClosed()) {
                    try {
                        OutputStream out = minecraftConnection.getOutputStream();
                        out.write(data);
                        out.flush();
                    } catch (IOException e) {
                        LOGGER.error("[DirectConnect] Failed to forward to MC: {}", e.getMessage());
                    }
                }
            });

            udp.setOnDisconnect(sender -> {
                LOGGER.info("[DirectConnect] Disconnected from host");
                updateStatus("Disconnected");
                cleanup();
            });
        }
    }

    /**
     * Accepts the Minecraft client connection to the local proxy.
     */
    private void acceptMinecraftConnection(CompletableFuture<Integer> result) {
        try {
            LOGGER.debug("[DirectConnect] Waiting for MC client connection...");
            minecraftConnection = proxyServer.accept();
            minecraftConnection.setTcpNoDelay(true);
            LOGGER.info("[DirectConnect] MC client connected to proxy");

            // Note: result.complete() is called in raceEndpoints when UDP connects

            // Forward MC -> UDP
            InputStream in = minecraftConnection.getInputStream();
            byte[] buffer = new byte[4096];

            while (connected.get() && !minecraftConnection.isClosed()) {
                int read = in.read(buffer);
                if (read == -1)
                    break;

                if (udp != null) {
                    byte[] data = new byte[read];
                    System.arraycopy(buffer, 0, data, 0, read);
                    udp.send(data);
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                LOGGER.error("[DirectConnect] Proxy error: {}", e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    /**
     * Disconnects from the current P2P session.
     */
    public void disconnect() {
        LOGGER.info("[DirectConnect] Disconnecting...");
        cleanup();
    }

    private void cleanup() {
        connecting.set(false);
        connected.set(false);

        if (udp != null) {
            udp.stop();
            udp = null;
        }

        if (minecraftConnection != null) {
            try {
                minecraftConnection.close();
            } catch (IOException ignored) {
            }
            minecraftConnection = null;
        }

        if (proxyServer != null) {
            try {
                proxyServer.close();
            } catch (IOException ignored) {
            }
            proxyServer = null;
        }
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setOnStatusChange(Consumer<String> handler) {
        this.onStatusChange = handler;
    }

    public void setOnProxyReady(Consumer<Integer> handler) {
        this.onProxyReady = handler;
    }

    public void setOnError(Consumer<String> handler) {
        this.onError = handler;
    }

    private void updateStatus(String status) {
        if (onStatusChange != null) {
            onStatusChange.accept(status);
        }
    }

    private void triggerError(String error) {
        LOGGER.error("[DirectConnect] {}", error);
        if (onError != null) {
            onError.accept(error);
        }
    }
}
