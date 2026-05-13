package dev.mctun.metrics;

import java.util.concurrent.atomic.AtomicLong;

public final class TunnelMetrics {
    private final AtomicLong activeStreams = new AtomicLong();
    private final AtomicLong activeUdpAssociations = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong droppedUdpDatagrams = new AtomicLong();
    private final AtomicLong malformedUdpDatagrams = new AtomicLong();
    private final AtomicLong windowPauses = new AtomicLong();

    public long activeStreams() {
        return activeStreams.get();
    }

    public long activeUdpAssociations() {
        return activeUdpAssociations.get();
    }

    public long bytesIn() {
        return bytesIn.get();
    }

    public long bytesOut() {
        return bytesOut.get();
    }

    public long droppedUdpDatagrams() {
        return droppedUdpDatagrams.get();
    }

    public long malformedUdpDatagrams() {
        return malformedUdpDatagrams.get();
    }

    public long windowPauses() {
        return windowPauses.get();
    }

    public void streamOpened() {
        activeStreams.incrementAndGet();
    }

    public void streamClosed() {
        decrement(activeStreams);
    }

    public void udpAssociationOpened() {
        activeUdpAssociations.incrementAndGet();
    }

    public void udpAssociationClosed() {
        decrement(activeUdpAssociations);
    }

    public void bytesIn(long bytes) {
        bytesIn.addAndGet(bytes);
    }

    public void bytesOut(long bytes) {
        bytesOut.addAndGet(bytes);
    }

    public void droppedUdpDatagram() {
        droppedUdpDatagrams.incrementAndGet();
    }

    public void malformedUdpDatagram() {
        malformedUdpDatagrams.incrementAndGet();
    }

    public void windowPause() {
        windowPauses.incrementAndGet();
    }

    private static void decrement(AtomicLong counter) {
        long value;
        do {
            value = counter.get();
            if (value == 0) {
                return;
            }
        } while (!counter.compareAndSet(value, value - 1));
    }
}
