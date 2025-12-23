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
