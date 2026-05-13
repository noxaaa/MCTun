package dev.mctun.server;

import dev.mctun.MctunMod;
import dev.mctun.config.ConfigIo;
import dev.mctun.config.ServerConfig;
import dev.mctun.protocol.TunnelPayload;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MctunServerMod implements DedicatedServerModInitializer {
    private ServerTunnelManager manager;
    private ServerTunnelTransport transport;

    @Override
    public void onInitializeServer() {
        ServerConfig config = ConfigIo.loadServer();
        if (config.allowAllDestinations()) {
            MctunMod.LOGGER.warn("MCTun server egress is fully open. Any authorized tunnel client can reach arbitrary destinations.");
        }

        transport = new ServerTunnelTransport();
        manager = new ServerTunnelManager(config, transport);
        ServerPlayNetworking.registerGlobalReceiver(TunnelPayload.ID, (payload, context) ->
                context.server().execute(() -> manager.receive(context.player().getUuid(), payload.frame())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> transport.register(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            manager.disconnect(handler.player.getUuid());
            transport.unregister(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> manager.shutdown());
        MctunMod.LOGGER.info("MCTun server initialized");
    }
}
