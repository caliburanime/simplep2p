package calibur.directconnect.network;

import calibur.directconnect.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Client for communicating with the P2P Registry server.
 * Handles both HTTP (lookup) and WebSocket (registration) connections.
 */
public class RegistryClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final ModConfig config;

    private WebSocket webSocket;
    private boolean connected = false;
    private Consumer<PunchRequest> onPunchRequest;
    private Consumer<String> onCodeAssigned;
    private Consumer<String> onDisconnect;

    /**
     * Represents an endpoint returned from lookup.
     */
    public static class Endpoint {
        public final String ip;
        public final int port;
        public final String type; // "WAN" or "LAN"

        public Endpoint(String ip, int port, String type) {
            this.ip = ip;
            this.port = port;
            this.type = type;
        }

        @Override
        public String toString() {
            return type + ":" + ip + ":" + port;
        }
    }

    /**
     * Represents a punch request from a client.
     */
    public static class PunchRequest {
        public final String clientIp;
        public final int clientPort;

        public PunchRequest(String clientIp, int clientPort) {
            this.clientIp = clientIp;
            this.clientPort = clientPort;
        }
    }

    public RegistryClient() {
        this.config = ModConfig.getInstance();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // --- HTTP Methods ---

    /**
     * Looks up a share code and returns the host's endpoints.
     * 
     * @param shareCode  The share code (with or without p2p:// prefix)
     * @param clientPort Client's UDP port for hole punching
     * @return List of endpoints to try
     */
    public CompletableFuture<List<Endpoint>> lookup(String shareCode, int clientPort) {
        // Strip p2p:// prefix if present
        String code = shareCode.toLowerCase().trim();
        if (code.startsWith("p2p://")) {
            code = code.substring(6);
        }

        String requestBody = GSON.toJson(new LookupRequest(code, clientPort));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getRegistryUrl() + "/lookup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return parseEndpoints(response.body());
                    } else if (response.statusCode() == 404) {
                        LOGGER.warn("[DirectConnect] Share code not found: {}", shareCode);
                        return new ArrayList<Endpoint>();
                    } else {
                        LOGGER.error("[DirectConnect] Lookup failed: {} - {}",
                                response.statusCode(), response.body());
                        return new ArrayList<Endpoint>();
                    }
                })
                .<List<Endpoint>>thenApply(list -> list)
                .exceptionally(e -> {
                    LOGGER.error("[DirectConnect] Lookup error: {}", e.getMessage());
                    return new ArrayList<Endpoint>();
                });
    }

    private List<Endpoint> parseEndpoints(String json) {
        List<Endpoint> endpoints = new ArrayList<>();
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            JsonArray arr = obj.getAsJsonArray("endpoints");

            for (int i = 0; i < arr.size(); i++) {
                JsonObject ep = arr.get(i).getAsJsonObject();
                endpoints.add(new Endpoint(
                        ep.get("ip").getAsString(),
                        ep.get("port").getAsInt(),
                        ep.get("type").getAsString()));
            }
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to parse endpoints: {}", e.getMessage());
        }
        return endpoints;
    }

    // --- WebSocket Methods (for Host Registration) ---

    /**
     * Registers as a host with the registry.
     * 
     * @param localIp       Local LAN IP
     * @param port          UDP port
     * @param requestedCode Requested share code (for persistence)
     */
    public CompletableFuture<Boolean> register(String localIp, int port, String requestedCode) {
        String wsUrl = config.getRegistryUrl()
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                + "/ws/register";

        LOGGER.info("[DirectConnect] Connecting to registry: {}", wsUrl);

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        private StringBuilder messageBuffer = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            connected = true;

                            // Send registration payload
                            JsonObject payload = new JsonObject();
                            payload.addProperty("local_ip", localIp);
                            payload.addProperty("port", port);
                            if (requestedCode != null && !requestedCode.isEmpty()) {
                                payload.addProperty("requested_code", requestedCode);
                            }

                            ws.sendText(GSON.toJson(payload), true);
                            ws.request(1);

                            LOGGER.info("[DirectConnect] Sent registration: {}:{}", localIp, port);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            messageBuffer.append(data);

                            if (last) {
                                handleMessage(messageBuffer.toString(), result);
                                messageBuffer = new StringBuilder();
                            }

                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            LOGGER.info("[DirectConnect] Registry connection closed: {}", reason);
                            connected = false;
                            if (onDisconnect != null) {
                                onDisconnect.accept(reason);
                            }
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            LOGGER.error("[DirectConnect] Registry error: {}", error.getMessage());
                            connected = false;
                            if (!result.isDone()) {
                                result.complete(false);
                            }
                        }
                    });
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to connect to registry: {}", e.getMessage());
            result.complete(false);
        }

        return result;
    }

    private void handleMessage(String json, CompletableFuture<Boolean> registrationResult) {
        try {
            JsonObject msg = GSON.fromJson(json, JsonObject.class);
            String type = msg.get("type").getAsString();

            switch (type) {
                case "REGISTERED":
                    String code = msg.get("code").getAsString();
                    LOGGER.info("[DirectConnect] Registered with code: {}", code);

                    // Update config with assigned code
                    config.setShareCode(code);

                    if (onCodeAssigned != null) {
                        onCodeAssigned.accept(code);
                    }

                    if (!registrationResult.isDone()) {
                        registrationResult.complete(true);
                    }
                    break;

                case "CODE_CONFLICT":
                    String assigned = msg.get("assigned").getAsString();
                    LOGGER.warn("[DirectConnect] Code conflict, assigned: {}", assigned);
                    config.setShareCode(assigned);

                    if (onCodeAssigned != null) {
                        onCodeAssigned.accept(assigned);
                    }

                    if (!registrationResult.isDone()) {
                        registrationResult.complete(true);
                    }
                    break;

                case "PUNCH_REQUEST":
                    String clientIp = msg.get("client_ip").getAsString();
                    int clientPort = msg.has("client_port") ? msg.get("client_port").getAsInt() : 0;

                    LOGGER.info("[DirectConnect] Punch request from: {}:{}", clientIp, clientPort);

                    if (onPunchRequest != null) {
                        onPunchRequest.accept(new PunchRequest(clientIp, clientPort));
                    }
                    break;

                case "PING":
                    // Respond with PONG
                    if (webSocket != null && connected) {
                        JsonObject pong = new JsonObject();
                        pong.addProperty("type", "PONG");
                        webSocket.sendText(GSON.toJson(pong), true);
                    }
                    break;

                case "CODE_REGENERATED":
                    String newCode = msg.get("new_code").getAsString();
                    LOGGER.info("[DirectConnect] Code regenerated: {}", newCode);
                    config.setShareCode(newCode);

                    if (onCodeAssigned != null) {
                        onCodeAssigned.accept(newCode);
                    }
                    break;

                default:
                    LOGGER.debug("[DirectConnect] Unknown message type: {}", type);
            }
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to parse message: {}", e.getMessage());
        }
    }

    /**
     * Requests a new share code from the registry.
     */
    public void requestRegenerate() {
        if (webSocket != null && connected) {
            JsonObject request = new JsonObject();
            request.addProperty("type", "REGENERATE");
            webSocket.sendText(GSON.toJson(request), true);
            LOGGER.info("[DirectConnect] Requested code regeneration");
        }
    }

    /**
     * Closes the WebSocket connection.
     */
    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(1000, "Client closing");
            connected = false;
        }
    }

    // --- Event Handlers ---

    public void setOnPunchRequest(Consumer<PunchRequest> handler) {
        this.onPunchRequest = handler;
    }

    public void setOnCodeAssigned(Consumer<String> handler) {
        this.onCodeAssigned = handler;
    }

    public void setOnDisconnect(Consumer<String> handler) {
        this.onDisconnect = handler;
    }

    public boolean isConnected() {
        return connected;
    }

    // --- Helper Classes ---

    private static class LookupRequest {
        final String share_code;
        final int client_port;

        LookupRequest(String shareCode, int clientPort) {
            this.share_code = shareCode;
            this.client_port = clientPort;
        }
    }
}
