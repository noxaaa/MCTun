package dev.mctun.transport;

import dev.mctun.protocol.TunnelFrame;

@FunctionalInterface
public interface FrameSender {
    void send(TunnelFrame frame);
}
