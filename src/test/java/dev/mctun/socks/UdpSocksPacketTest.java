package dev.mctun.socks;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class UdpSocksPacketTest {
    @Test
    void roundTripsIpv4Packet() {
        roundTrip(UdpSocksPacket.fromHost(0, "127.0.0.1", 5353, new byte[]{1, 2, 3}), Socks5AddressType.IPv4);
    }

    @Test
    void roundTripsIpv6Packet() {
        roundTrip(UdpSocksPacket.fromHost(0, "::1", 5353, new byte[]{4, 5, 6}), Socks5AddressType.IPv6);
    }

    @Test
    void roundTripsDomainPacket() {
        roundTrip(UdpSocksPacket.fromHost(0, "example.com", 5353, new byte[]{7, 8, 9}), Socks5AddressType.DOMAIN);
    }

    private static void roundTrip(UdpSocksPacket packet, Socks5AddressType expectedType) {
        ByteBuf buf = packet.toByteBuf();
        try {
            UdpSocksPacket decoded = UdpSocksPacket.read(buf);
            assertEquals(expectedType, decoded.addressType());
            assertEquals(packet.port(), decoded.port());
            assertArrayEquals(packet.payload(), decoded.payload());
        } finally {
            buf.release();
        }
    }
}
