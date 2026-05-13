package dev.mctun.transport;

import dev.mctun.protocol.TunnelFrame;

import java.util.UUID;

@FunctionalInterface
public interface ServerFrameSender {
    void send(UUID playerId, TunnelFrame frame);
}
