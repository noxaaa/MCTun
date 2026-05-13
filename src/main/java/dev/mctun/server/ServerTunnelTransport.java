package dev.mctun.server;

import dev.mctun.TunnelNetworking;
import dev.mctun.protocol.TunnelFrame;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

public final class ServerTunnelTransport {
    public void send(ServerPlayerEntity player, TunnelFrame frame) {
        ServerPlayNetworking.send(player, TunnelNetworking.payload(frame));
    }
}
