package dev.mctun.client;

import dev.mctun.TunnelNetworking;
import dev.mctun.protocol.TunnelFrame;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientTunnelTransport {
    public void send(TunnelFrame frame) {
        if (ClientPlayNetworking.canSend(TunnelNetworking.TUNNEL_ID)) {
            ClientPlayNetworking.send(TunnelNetworking.payload(frame));
        }
    }
}
