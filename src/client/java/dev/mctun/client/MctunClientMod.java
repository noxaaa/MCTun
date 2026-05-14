package dev.mctun.client;

import dev.mctun.MctunMod;
import dev.mctun.config.ConfigIo;
import dev.mctun.config.ClientConfig;
import dev.mctun.protocol.TunnelPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MctunClientMod implements ClientModInitializer {
    private static volatile ClientTunnelManager activeManager;

    private ClientTunnelManager tunnelManager;
    private DirectSocksManager directManager;

    @Override
    public void onInitializeClient() {
        ClientConfig config = ConfigIo.loadClient();
        if (config.mode() == ClientConfig.Mode.DIRECT) {
            activeManager = null;
            directManager = new DirectSocksManager(config);
            directManager.start();
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> directManager.stop());
        } else {
            tunnelManager = new ClientTunnelManager(config, new ClientTunnelTransport());
            activeManager = tunnelManager;
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> tunnelManager.start());
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> tunnelManager.stop());
            ClientPlayNetworking.registerGlobalReceiver(TunnelPayload.ID, (payload, context) ->
                    handleTunnelPayload(payload));
        }

        MctunMod.LOGGER.info("MCTun client initialized");
    }

    public static boolean handleTunnelPayload(TunnelPayload payload) {
        ClientTunnelManager manager = activeManager;
        if (manager == null) {
            return false;
        }
        manager.receive(payload.frame());
        return true;
    }
}
