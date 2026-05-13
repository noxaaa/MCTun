package dev.mctun.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;

public record UdpSocksPacket(int fragment, Socks5AddressType addressType, String host, int port, byte[] payload) {
    public static UdpSocksPacket fromHost(int fragment, String host, int port, byte[] payload) {
        return new UdpSocksPacket(fragment, SocksAddresses.typeForHost(host), host, port, payload);
    }

    public static UdpSocksPacket read(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            throw new IllegalArgumentException("SOCKS5 UDP packet too short");
        }
        int rsv = buf.readUnsignedShort();
        if (rsv != 0) {
            throw new IllegalArgumentException("Invalid SOCKS5 UDP RSV");
        }
        int fragment = buf.readUnsignedByte();
        Socks5AddressType addressType = Socks5AddressType.valueOf(buf.readByte());
        String host = readAddress(buf, addressType);
        int port = buf.readUnsignedShort();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return new UdpSocksPacket(fragment, addressType, host, port, payload);
    }

    public ByteBuf toByteBuf() {
        ByteBuf buf = Unpooled.buffer(10 + payload.length);
        buf.writeShort(0);
        buf.writeByte(fragment);
        buf.writeByte(addressType.byteValue());
        writeAddress(buf, addressType, host);
        buf.writeShort(port);
        buf.writeBytes(payload);
        return buf;
    }

    public UdpSocksPacket withoutFragment(byte[] reassembledPayload) {
        return new UdpSocksPacket(0, addressType, host, port, reassembledPayload);
    }

    private static String readAddress(ByteBuf buf, Socks5AddressType type) {
        try {
            if (type == Socks5AddressType.IPv4) {
                byte[] bytes = new byte[4];
                buf.readBytes(bytes);
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
            if (type == Socks5AddressType.IPv6) {
                byte[] bytes = new byte[16];
                buf.readBytes(bytes);
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
            if (type == Socks5AddressType.DOMAIN) {
                int length = buf.readUnsignedByte();
                byte[] bytes = new byte[length];
                buf.readBytes(bytes);
                return IDN.toUnicode(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid SOCKS5 UDP address", ex);
        }
        throw new IllegalArgumentException("Unsupported SOCKS5 UDP address type " + type);
    }

    private static void writeAddress(ByteBuf buf, Socks5AddressType type, String host) {
        try {
            if (type == Socks5AddressType.IPv4 || type == Socks5AddressType.IPv6) {
                buf.writeBytes(InetAddress.getByName(host).getAddress());
                return;
            }
            if (type == Socks5AddressType.DOMAIN) {
                byte[] bytes = IDN.toASCII(host).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                if (bytes.length > 255) {
                    throw new IllegalArgumentException("Domain name too long");
                }
                buf.writeByte(bytes.length);
                buf.writeBytes(bytes);
                return;
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid SOCKS5 UDP address " + host, ex);
        }
        throw new IllegalArgumentException("Unsupported SOCKS5 UDP address type " + type);
    }
}
