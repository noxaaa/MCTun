package dev.mctun.protocol;

import net.minecraft.network.PacketByteBuf;

public record TunnelFrame(
        TunnelFrameType type,
        int streamId,
        String host,
        int port,
        int code,
        byte[] payload,
        String message
) {
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    public static TunnelFrame hello(int version, int chunkSize, int windowBytes) {
        return new TunnelFrame(TunnelFrameType.HELLO, version, "", chunkSize, windowBytes, new byte[0], "");
    }

    public static TunnelFrame openTcp(int streamId, String host, int port) {
        return new TunnelFrame(TunnelFrameType.OPEN_TCP, streamId, host, port, 0, new byte[0], "");
    }

    public static TunnelFrame openBind(int streamId, String host, int port) {
        return new TunnelFrame(TunnelFrameType.OPEN_BIND, streamId, host, port, 0, new byte[0], "");
    }

    public static TunnelFrame openUdp(int streamId, String host, int port) {
        return new TunnelFrame(TunnelFrameType.OPEN_UDP, streamId, host, port, 0, new byte[0], "");
    }

    public static TunnelFrame openResult(int streamId, int code, String host, int port, String message) {
        return new TunnelFrame(TunnelFrameType.OPEN_RESULT, streamId, host, port, code, new byte[0], message);
    }

    public static TunnelFrame bindAccepted(int streamId, String host, int port) {
        return new TunnelFrame(TunnelFrameType.BIND_ACCEPTED, streamId, host, port, 0, new byte[0], "");
    }

    public static TunnelFrame tcpData(int streamId, byte[] payload) {
        return new TunnelFrame(TunnelFrameType.TCP_DATA, streamId, "", 0, 0, payload, "");
    }

    public static TunnelFrame udpDatagram(int streamId, String host, int port, byte[] payload) {
        return new TunnelFrame(TunnelFrameType.UDP_DATAGRAM, streamId, host, port, 0, payload, "");
    }

    public static TunnelFrame windowUpdate(int streamId, int bytes) {
        return new TunnelFrame(TunnelFrameType.WINDOW_UPDATE, streamId, "", 0, bytes, new byte[0], "");
    }

    public static TunnelFrame close(int streamId, int code, String message) {
        return new TunnelFrame(TunnelFrameType.CLOSE, streamId, "", 0, code, new byte[0], message);
    }

    public static TunnelFrame error(int streamId, int code, String message) {
        return new TunnelFrame(TunnelFrameType.ERROR, streamId, "", 0, code, new byte[0], message);
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(type.wireId());
        buf.writeVarInt(streamId);
        buf.writeString(host == null ? "" : host, 255);
        buf.writeVarInt(port);
        buf.writeVarInt(code);
        byte[] bytes = payload == null ? new byte[0] : payload;
        buf.writeByteArray(bytes);
        buf.writeString(message == null ? "" : message, 1024);
    }

    public static TunnelFrame read(PacketByteBuf buf) {
        TunnelFrameType type = TunnelFrameType.fromWireId(buf.readVarInt());
        int streamId = buf.readVarInt();
        String host = buf.readString(255);
        int port = buf.readVarInt();
        int code = buf.readVarInt();
        byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
        String message = buf.readString(1024);
        return new TunnelFrame(type, streamId, host, port, code, payload, message);
    }
}
