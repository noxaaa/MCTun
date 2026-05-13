package dev.mctun;

import dev.mctun.protocol.TunnelFrame;
import dev.mctun.protocol.TunnelPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.util.Identifier;

public final class TunnelNetworking {
    public static final Identifier TUNNEL_ID = Identifier.of(MctunMod.MOD_ID, "tunnel");

    private TunnelNetworking() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(TunnelPayload.ID, TunnelPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TunnelPayload.ID, TunnelPayload.CODEC);
    }

    public static TunnelPayload payload(TunnelFrame frame) {
        return new TunnelPayload(frame);
    }
}
