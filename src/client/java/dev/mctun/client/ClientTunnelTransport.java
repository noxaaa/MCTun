package dev.mctun.client;

import dev.mctun.TunnelNetworking;
import dev.mctun.protocol.TunnelFrame;
import dev.mctun.transport.FrameSender;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientTunnelTransport implements FrameSender {
    @Override
    public void send(TunnelFrame frame) {
        if (ClientPlayNetworking.canSend(TunnelNetworking.TUNNEL_ID)) {
            ClientPlayNetworking.send(TunnelNetworking.payload(frame));
        }
    }
}
