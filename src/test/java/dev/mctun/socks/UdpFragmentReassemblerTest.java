package dev.mctun.socks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class UdpFragmentReassemblerTest {
    @Test
    void passesUnfragmentedPayload() {
        UdpFragmentReassembler reassembler = new UdpFragmentReassembler(1000, 1024);
        UdpFragmentReassembler.Result result = reassembler.acceptPacket(packet(0, new byte[]{1, 2}));
        assertEquals(UdpFragmentReassembler.Status.COMPLETE, result.status());
        assertArrayEquals(new byte[]{1, 2}, result.payload());
    }

    @Test
    void reassemblesOrderedFragments() {
        UdpFragmentReassembler reassembler = new UdpFragmentReassembler(1000, 1024);
        assertEquals(UdpFragmentReassembler.Status.PENDING, reassembler.acceptPacket(packet(1, new byte[]{1, 2})).status());
        UdpFragmentReassembler.Result result = reassembler.acceptPacket(packet(0x82, new byte[]{3, 4}));
        assertEquals(UdpFragmentReassembler.Status.COMPLETE, result.status());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result.payload());
    }

    @Test
    void dropsOutOfOrderFragments() {
        UdpFragmentReassembler reassembler = new UdpFragmentReassembler(1000, 1024);
        assertEquals(UdpFragmentReassembler.Status.DROPPED, reassembler.acceptPacket(packet(2, new byte[]{1})).status());
    }

    @Test
    void dropsOversizedReassembly() {
        UdpFragmentReassembler reassembler = new UdpFragmentReassembler(1000, 2);
        assertEquals(UdpFragmentReassembler.Status.PENDING, reassembler.acceptPacket(packet(1, new byte[]{1, 2})).status());
        assertEquals(UdpFragmentReassembler.Status.DROPPED, reassembler.acceptPacket(packet(0x82, new byte[]{3})).status());
    }

    private static UdpSocksPacket packet(int fragment, byte[] payload) {
        return UdpSocksPacket.fromHost(fragment, "127.0.0.1", 53, payload);
    }
}
