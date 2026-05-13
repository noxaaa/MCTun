package dev.mctun.socks;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class SocksAddresses {
    private SocksAddresses() {
    }

    public static Socks5AddressType typeForHost(String host) {
        if (host == null || host.isBlank()) {
            return Socks5AddressType.IPv4;
        }
        if (isIpv4Literal(host)) {
            return Socks5AddressType.IPv4;
        }
        if (host.indexOf(':') >= 0 && isIpv6Literal(host)) {
            return Socks5AddressType.IPv6;
        }
        return Socks5AddressType.DOMAIN;
    }

    private static boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6Literal(String host) {
        try {
            return InetAddress.getByName(host) instanceof Inet6Address;
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
