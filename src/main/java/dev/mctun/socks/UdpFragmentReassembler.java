package dev.mctun.socks;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.Optional;

public final class UdpFragmentReassembler {
    public enum Status {
        COMPLETE,
        PENDING,
        DROPPED
    }

    public record Result(Status status, byte[] payload) {
        public static Result complete(byte[] payload) {
            return new Result(Status.COMPLETE, payload);
        }

        public static Result pending() {
            return new Result(Status.PENDING, new byte[0]);
        }

        public static Result dropped() {
            return new Result(Status.DROPPED, new byte[0]);
        }
    }

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
        Result result = acceptPacket(packet);
        return result.status() == Status.COMPLETE ? Optional.of(result.payload()) : Optional.empty();
    }

    public Result acceptPacket(UdpSocksPacket packet) {
        int fragment = packet.fragment();
        if (fragment == 0) {
            reset();
            return Result.complete(packet.payload());
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
            return Result.dropped();
        }

        if (buffer.size() + packet.payload().length > maxBytes) {
            reset();
            return Result.dropped();
        }

        buffer.writeBytes(packet.payload());
        expectedFragment++;

        if (last) {
            byte[] bytes = buffer.toByteArray();
            reset();
            return Result.complete(bytes);
        }
        return Result.pending();
    }

    private void reset() {
        buffer = null;
        startedAtMillis = 0;
        expectedFragment = 1;
    }
}
