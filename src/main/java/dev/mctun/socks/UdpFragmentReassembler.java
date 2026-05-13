package dev.mctun.socks;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.Optional;

public final class UdpFragmentReassembler {
    private final int timeoutMillis;
    private final int maxBytes;
    private final Clock clock;
    private ByteArrayOutputStream buffer;
    private long startedAtMillis;
    private int expectedFragment = 1;

    public UdpFragmentReassembler(int timeoutMillis, int maxBytes) {
        this(timeoutMillis, maxBytes, Clock.systemUTC());
    }

    UdpFragmentReassembler(int timeoutMillis, int maxBytes, Clock clock) {
        this.timeoutMillis = timeoutMillis;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    public Optional<byte[]> accept(UdpSocksPacket packet) {
        int fragment = packet.fragment();
        if (fragment == 0) {
            reset();
            return Optional.of(packet.payload());
        }

        boolean last = (fragment & 0x80) != 0;
        int index = fragment & 0x7f;
        long now = clock.millis();
        if (buffer == null || now - startedAtMillis > timeoutMillis || index != expectedFragment) {
            reset();
            buffer = new ByteArrayOutputStream();
            startedAtMillis = now;
            expectedFragment = 1;
        }
        if (index != expectedFragment) {
            reset();
            return Optional.empty();
        }

        if (buffer.size() + packet.payload().length > maxBytes) {
            reset();
            return Optional.empty();
        }

        buffer.writeBytes(packet.payload());
        expectedFragment++;

        if (last) {
            byte[] bytes = buffer.toByteArray();
            reset();
            return Optional.of(bytes);
        }
        return Optional.empty();
    }

    private void reset() {
        buffer = null;
        startedAtMillis = 0;
        expectedFragment = 1;
    }
}
