package dev.mctun.client.mixin;

import dev.mctun.client.MctunClientMod;
import dev.mctun.protocol.TunnelPayload;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionClientMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void mctun$handleTunnelPayload(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof CustomPayloadS2CPacket customPayloadPacket
                && customPayloadPacket.payload() instanceof TunnelPayload payload
                && MctunClientMod.handleTunnelPayload(payload)) {
            ci.cancel();
        }
    }
}
