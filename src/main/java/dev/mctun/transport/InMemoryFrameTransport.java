package dev.mctun.transport;

import dev.mctun.protocol.TunnelFrame;

import java.util.UUID;

public final class InMemoryFrameTransport {
    private FrameSender clientReceiver = frame -> {
    };
    private ServerFrameSender serverReceiver = (playerId, frame) -> {
    };

    public FrameSender clientToServer(UUID playerId) {
        return frame -> serverReceiver.send(playerId, frame);
    }

    public ServerFrameSender serverToClient() {
        return (playerId, frame) -> clientReceiver.send(frame);
    }

    public void onClientFrame(FrameSender receiver) {
        this.clientReceiver = receiver;
    }

    public void onServerFrame(ServerFrameSender receiver) {
        this.serverReceiver = receiver;
    }

    public void sendClient(UUID playerId, TunnelFrame frame) {
        serverReceiver.send(playerId, frame);
    }

    public void sendServer(TunnelFrame frame) {
        clientReceiver.send(frame);
    }
}
