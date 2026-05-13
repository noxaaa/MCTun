package dev.mctun.protocol;

public enum TunnelFrameType {
    HELLO(0),
    OPEN_TCP(1),
    OPEN_BIND(2),
    BIND_ACCEPTED(3),
    OPEN_UDP(4),
    OPEN_RESULT(5),
    TCP_DATA(6),
    UDP_DATAGRAM(7),
    WINDOW_UPDATE(8),
    CLOSE(9),
    ERROR(10);

    private final int wireId;

    TunnelFrameType(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static TunnelFrameType fromWireId(int wireId) {
        for (TunnelFrameType type : values()) {
            if (type.wireId == wireId) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown tunnel frame type " + wireId);
    }
}
