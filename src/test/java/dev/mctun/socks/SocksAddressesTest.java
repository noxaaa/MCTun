package dev.mctun.socks;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SocksAddressesTest {
    @Test
    void detectsAddressTypesWithoutDnsLookup() {
        assertEquals(Socks5AddressType.IPv4, SocksAddresses.typeForHost("127.0.0.1"));
        assertEquals(Socks5AddressType.IPv6, SocksAddresses.typeForHost("::1"));
        assertEquals(Socks5AddressType.DOMAIN, SocksAddresses.typeForHost("example.com"));
    }
}
