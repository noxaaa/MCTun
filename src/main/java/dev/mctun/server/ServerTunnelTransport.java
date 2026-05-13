package dev.mctun.server;

import dev.mctun.TunnelNetworking;
import dev.mctun.protocol.TunnelFrame;
import dev.mctun.transport.ServerFrameSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerTunnelTransport implements ServerFrameSender {
    private final Map<UUID, ServerPlayerEntity> players = new ConcurrentHashMap<>();

    public void register(ServerPlayerEntity player) {
        players.put(player.getUuid(), player);
    }

    public void unregister(ServerPlayerEntity player) {
        players.remove(player.getUuid());
    }

    @Override
    public void send(UUID playerId, TunnelFrame frame) {
        ServerPlayerEntity player = players.get(playerId);
        if (player != null) {
            ServerPlayNetworking.send(player, TunnelNetworking.payload(frame));
        }
    }
}
