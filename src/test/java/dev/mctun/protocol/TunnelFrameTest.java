package dev.mctun.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class TunnelFrameTest {
    @Test
    void roundTripsDataFrame() {
        TunnelFrame frame = TunnelFrame.udpDatagram(42, "example.com", 443, new byte[]{1, 2, 3});
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        frame.write(buf);

        TunnelFrame decoded = TunnelFrame.read(buf);
        assertEquals(frame.type(), decoded.type());
        assertEquals(frame.streamId(), decoded.streamId());
        assertEquals(frame.host(), decoded.host());
        assertEquals(frame.port(), decoded.port());
        assertArrayEquals(frame.payload(), decoded.payload());
    }
}
