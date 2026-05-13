package dev.mctun;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MctunMod implements ModInitializer {
    public static final String MOD_ID = "mctun";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        TunnelNetworking.registerPayloads();
        LOGGER.info("MCTun initialized");
    }
}
