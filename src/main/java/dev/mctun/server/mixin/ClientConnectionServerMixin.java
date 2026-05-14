package dev.mctun.server.mixin;

import dev.mctun.protocol.TunnelPayload;
import dev.mctun.server.MctunServerMod;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionServerMixin {
    @Shadow
    private volatile PacketListener packetListener;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void mctun$handleTunnelPayload(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof CustomPayloadC2SPacket customPayloadPacket
                && customPayloadPacket.payload() instanceof TunnelPayload payload
                && packetListener instanceof ServerPlayNetworkHandler handler
                && MctunServerMod.handleTunnelPayload(handler, payload)) {
            ci.cancel();
        }
    }
}
