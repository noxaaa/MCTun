package dev.mctun.server;

import dev.mctun.MctunMod;
import dev.mctun.config.ConfigIo;
import dev.mctun.config.ServerConfig;
import dev.mctun.protocol.TunnelPayload;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MctunServerMod implements DedicatedServerModInitializer {
    private ServerTunnelManager manager;

    @Override
    public void onInitializeServer() {
        ServerConfig config = ConfigIo.loadServer();
        if (config.allowAllDestinations()) {
            MctunMod.LOGGER.warn("MCTun server egress is fully open. Any authorized tunnel client can reach arbitrary destinations.");
        }

        manager = new ServerTunnelManager(config, new ServerTunnelTransport());
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayload.ID, (payload, context) ->
                context.server().execute(() -> manager.receive(context.player(), payload.frame())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> manager.disconnect(handler.player));
        MctunMod.LOGGER.info("MCTun server initialized");
    }
}
