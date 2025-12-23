package calibur.directconnect.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility class for network operations.
 * Handles local IP detection and UDP hole punching.
 */
public class NetworkUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

    /**
     * Detects the local LAN IP address.
     * Prefers non-loopback IPv4 addresses.
     */
    public static String getLocalIp() {
        try {
            // Method 1: Try to connect to a public IP to determine preferred interface
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                String ip = socket.getLocalAddress().getHostAddress();
                if (!ip.startsWith("127.")) {
                    LOGGER.debug("[DirectConnect] Local IP (via socket): {}", ip);
                    return ip;
                }
            } catch (Exception ignored) {
            }

            // Method 2: Enumerate network interfaces
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                // Skip loopback and down interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // Prefer IPv4
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            LOGGER.debug("[DirectConnect] Local IP (via interface {}): {}",
                                    iface.getDisplayName(), ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to detect local IP: {}", e.getMessage());
        }

        return "127.0.0.1"; // Fallback
    }

    /**
     * Gets the public WAN IP address using STUN.
     * Works even behind CGNAT.
     * 
     * @param localSocket The local UDP socket to use for STUN
     * @return The public IP and port, or null on failure
     */
    public static InetSocketAddress getPublicAddress(DatagramSocket localSocket) {
        // List of public STUN servers
        String[][] stunServers = {
                { "stun.l.google.com", "19302" },
                { "stun1.l.google.com", "19302" },
                { "stun.cloudflare.com", "3478" },
                { "stun.stunprotocol.org", "3478" }
        };

        for (String[] server : stunServers) {
            try {
                InetSocketAddress stunAddr = new InetSocketAddress(server[0], Integer.parseInt(server[1]));
                InetSocketAddress result = performStunRequest(localSocket, stunAddr);
                if (result != null) {
                    LOGGER.info("[DirectConnect] STUN detected public address: {}", result);
                    return result;
                }
            } catch (Exception e) {
                LOGGER.debug("[DirectConnect] STUN server {} failed: {}", server[0], e.getMessage());
            }
        }

        LOGGER.warn("[DirectConnect] All STUN servers failed, cannot detect public IP");
        return null;
    }

    /**
     * Performs a STUN Binding Request and parses the response.
     * Implements RFC 5389 basics.
     */
    private static InetSocketAddress performStunRequest(DatagramSocket socket, InetSocketAddress stunServer)
            throws Exception {
        // STUN Binding Request
        // Header: Type (2) + Length (2) + Magic Cookie (4) + Transaction ID (12) = 20
        // bytes
        byte[] request = new byte[20];

        // Binding Request type: 0x0001
        request[0] = 0x00;
        request[1] = 0x01;

        // Message length: 0 (no attributes)
        request[2] = 0x00;
        request[3] = 0x00;

        // Magic cookie: 0x2112A442
        request[4] = 0x21;
        request[5] = 0x12;
        request[6] = (byte) 0xA4;
        request[7] = 0x42;

        // Transaction ID: random 12 bytes
        java.util.Random random = new java.util.Random();
        for (int i = 8; i < 20; i++) {
            request[i] = (byte) random.nextInt(256);
        }

        // Send request
        DatagramPacket sendPacket = new DatagramPacket(request, request.length, stunServer);
        socket.setSoTimeout(3000); // 3 second timeout
        socket.send(sendPacket);

        // Receive response
        byte[] response = new byte[256];
        DatagramPacket recvPacket = new DatagramPacket(response, response.length);
        socket.receive(recvPacket);

        // Verify it's a STUN Binding Response (0x0101)
        if (response[0] != 0x01 || response[1] != 0x01) {
            return null;
        }

        // Parse message length
        int msgLength = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);

        // Parse attributes starting at offset 20
        int offset = 20;
        while (offset < 20 + msgLength) {
            int attrType = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            int attrLength = ((response[offset + 2] & 0xFF) << 8) | (response[offset + 3] & 0xFF);
            offset += 4;

            // XOR-MAPPED-ADDRESS (0x0020) or MAPPED-ADDRESS (0x0001)
            if (attrType == 0x0020 || attrType == 0x0001) {
                // Skip first byte (reserved), second byte is family
                int family = response[offset + 1] & 0xFF;

                if (family == 0x01) { // IPv4
                    int port;
                    byte[] ipBytes = new byte[4];

                    if (attrType == 0x0020) {
                        // XOR-Mapped: XOR with magic cookie
                        port = (((response[offset + 2] & 0xFF) ^ 0x21) << 8) |
                                ((response[offset + 3] & 0xFF) ^ 0x12);
                        ipBytes[0] = (byte) ((response[offset + 4] & 0xFF) ^ 0x21);
                        ipBytes[1] = (byte) ((response[offset + 5] & 0xFF) ^ 0x12);
                        ipBytes[2] = (byte) ((response[offset + 6] & 0xFF) ^ 0xA4);
                        ipBytes[3] = (byte) ((response[offset + 7] & 0xFF) ^ 0x42);
                    } else {
                        // Regular Mapped Address
                        port = ((response[offset + 2] & 0xFF) << 8) | (response[offset + 3] & 0xFF);
                        ipBytes[0] = response[offset + 4];
                        ipBytes[1] = response[offset + 5];
                        ipBytes[2] = response[offset + 6];
                        ipBytes[3] = response[offset + 7];
                    }

                    InetAddress ip = InetAddress.getByAddress(ipBytes);
                    return new InetSocketAddress(ip, port);
                }
            }

            // Move to next attribute (padded to 4-byte boundary)
            offset += (attrLength + 3) & ~3;
        }

        return null;
    }

    /**
     * Gets all local IP addresses (for multi-homed hosts).
     */
    public static List<String> getAllLocalIps() {
        List<String> ips = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Failed to enumerate IPs: {}", e.getMessage());
        }

        return ips;
    }

    /**
     * Creates a UDP socket bound to a random available port.
     */
    public static DatagramSocket createUdpSocket() throws SocketException {
        return new DatagramSocket();
    }

    /**
     * Creates a UDP socket bound to a specific port.
     */
    public static DatagramSocket createUdpSocket(int port) throws SocketException {
        return new DatagramSocket(port);
    }

    /**
     * Finds an available port in the given range.
     */
    public static int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                return port;
            } catch (SocketException ignored) {
                // Port in use, try next
            }
        }
        return -1; // No available port found
    }

    /**
     * Sends dummy UDP packets to punch a hole in the firewall.
     * 
     * @param socket     Local socket to send from
     * @param targetIp   Target IP address
     * @param targetPort Target port
     * @param count      Number of packets to send
     */
    public static void punchHole(DatagramSocket socket, String targetIp, int targetPort, int count) {
        try {
            InetAddress target = InetAddress.getByName(targetIp);
            byte[] data = new byte[] { 0x00 }; // Minimal packet

            for (int i = 0; i < count; i++) {
                DatagramPacket packet = new DatagramPacket(data, data.length, target, targetPort);
                socket.send(packet);

                // Small delay between packets
                if (i < count - 1) {
                    Thread.sleep(50);
                }
            }

            LOGGER.debug("[DirectConnect] Sent {} punch packets to {}:{}",
                    count, targetIp, targetPort);
        } catch (Exception e) {
            LOGGER.error("[DirectConnect] Hole punch failed: {}", e.getMessage());
        }
    }

    /**
     * Checks if an address is on the local network (LAN).
     */
    public static boolean isLocalNetwork(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses a p2p. address into just the share code.
     */
    public static String parseShareCode(String input) {
        if (input == null)
            return null;

        String normalized = input.toLowerCase().trim();

        // Handle p2p. prefix (e.g., p2p.happy-llama-42)
        if (normalized.startsWith("p2p.")) {
            return normalized.substring(4);
        }

        // Check if it looks like a valid share code
        if (normalized.matches("^[a-z]+-[a-z]+-\\d{2,3}$")) {
            return normalized;
        }

        return null; // Not a valid share code
    }

    /**
     * Checks if a server address is a p2p. address.
     */
    public static boolean isP2pAddress(String address) {
        if (address == null)
            return false;

        String normalized = address.toLowerCase().trim();

        // Check for p2p. prefix (e.g., p2p.happy-llama-42)
        if (normalized.startsWith("p2p.")) {
            return true;
        }

        // Check if it matches share code pattern without prefix
        // (Disabled for now - require explicit prefix for safety)
        return false;
    }
}
