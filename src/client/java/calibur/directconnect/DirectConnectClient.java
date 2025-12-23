package calibur.directconnect;

import calibur.directconnect.join.JoinManager;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side initialization for DirectConnect.
 */
public class DirectConnectClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("DirectConnect");

	@Override
	public void onInitializeClient() {
		LOGGER.info("[DirectConnect] Client-side initialization...");

		// Pre-initialize JoinManager
		JoinManager.getInstance();

		LOGGER.info("[DirectConnect] Client ready. Use p2p://share-code to connect.");
	}
}