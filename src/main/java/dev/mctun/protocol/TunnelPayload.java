package dev.mctun.protocol;

import dev.mctun.TunnelNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record TunnelPayload(TunnelFrame frame) implements CustomPayload {
    public static final CustomPayload.Id<TunnelPayload> ID = new CustomPayload.Id<>(TunnelNetworking.TUNNEL_ID);
    public static final PacketCodec<RegistryByteBuf, TunnelPayload> CODEC = CustomPayload.codecOf(TunnelPayload::write, TunnelPayload::new);

    private TunnelPayload(RegistryByteBuf buf) {
        this(TunnelFrame.read(buf));
    }

    private void write(RegistryByteBuf buf) {
        frame.write(buf);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
