package dev.mctun.integration;

import com.google.gson.Gson;
import dev.mctun.client.DirectSocksManager;
import dev.mctun.config.ClientConfig;
import dev.mctun.socks.SocksAddresses;
import dev.mctun.socks.UdpSocksPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectSocksIntegrationTest {
    @Test
    void directConnectProxiesTcpEcho() throws Exception {
        try (TcpEchoServer echo = TcpEchoServer.start();
             DirectHarness direct = DirectHarness.start(directConfig(ClientConfig.AuthMode.NO_AUTH))) {
            try (Socket socket = openSocksSocket(direct.socksAddress())) {
                negotiateNoAuth(socket);
                SocksReply reply = sendCommand(socket, 1, "127.0.0.1", echo.port());
                assertEquals(0, reply.status());

                socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                assertArrayEquals("ping".getBytes(StandardCharsets.UTF_8), readExact(socket.getInputStream(), 4));
            }
        }
    }

    @Test
    void directBindAcceptsPeerAndProxiesBothDirections() throws Exception {
        try (DirectHarness direct = DirectHarness.start(directConfig(ClientConfig.AuthMode.NO_AUTH))) {
            try (Socket app = openSocksSocket(direct.socksAddress())) {
                negotiateNoAuth(app);
                SocksReply firstReply = sendCommand(app, 2, "127.0.0.1", 0);
                assertEquals(0, firstReply.status());

                try (Socket peer = new Socket(firstReply.host(), firstReply.port())) {
                    peer.setSoTimeout(5000);
                    SocksReply secondReply = readReply(app.getInputStream());
                    assertEquals(0, secondReply.status());

                    app.getOutputStream().write("from-app".getBytes(StandardCharsets.UTF_8));
                    app.getOutputStream().flush();
                    assertArrayEquals("from-app".getBytes(StandardCharsets.UTF_8), readExact(peer.getInputStream(), 8));

                    peer.getOutputStream().write("from-peer".getBytes(StandardCharsets.UTF_8));
                    peer.getOutputStream().flush();
                    assertArrayEquals("from-peer".getBytes(StandardCharsets.UTF_8), readExact(app.getInputStream(), 9));
                }
            }
        }
    }

    @Test
    void directUdpAssociateProxiesDatagramsAndSurvivesMalformedPacket() throws Exception {
        try (UdpEchoServer echo = UdpEchoServer.start();
             DirectHarness direct = DirectHarness.start(directConfig(ClientConfig.AuthMode.NO_AUTH))) {
            try (Socket control = openSocksSocket(direct.socksAddress());
                 DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
                udp.setSoTimeout(5000);
                negotiateNoAuth(control);
                SocksReply udpReply = sendCommand(control, 3, "0.0.0.0", 0);
                assertEquals(0, udpReply.status());

                udp.send(new DatagramPacket(new byte[]{1}, 1, InetAddress.getByName(udpReply.host()), udpReply.port()));
                await(() -> direct.manager().metrics().malformedUdpDatagrams() > 0);

                byte[] request = encodeUdp(UdpSocksPacket.fromHost(0, "127.0.0.1", echo.port(), "hello".getBytes(StandardCharsets.UTF_8)));
                udp.send(new DatagramPacket(request, request.length, InetAddress.getByName(udpReply.host()), udpReply.port()));

                byte[] responseBytes = new byte[512];
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
                udp.receive(response);
                UdpSocksPacket decoded = UdpSocksPacket.read(Unpooled.wrappedBuffer(response.getData(), 0, response.getLength()));
                assertEquals(echo.port(), decoded.port());
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), decoded.payload());
            }
        }
    }

    @Test
    void directUsernamePasswordAuthSucceedsAndFails() throws Exception {
        try (DirectHarness direct = DirectHarness.start(directConfig(ClientConfig.AuthMode.USERNAME_PASSWORD))) {
            try (Socket socket = openSocksSocket(direct.socksAddress())) {
                negotiatePassword(socket, "mctun", "change-me", true);
            }
            try (Socket socket = openSocksSocket(direct.socksAddress())) {
                negotiatePassword(socket, "mctun", "wrong", false);
            }
        }
    }

    @Test
    void directTargetConnectFailureReturnsSocksFailure() throws Exception {
        int port = unusedPort();
        try (DirectHarness direct = DirectHarness.start(directConfig(ClientConfig.AuthMode.NO_AUTH))) {
            try (Socket socket = openSocksSocket(direct.socksAddress())) {
                negotiateNoAuth(socket);
                SocksReply reply = sendCommand(socket, 1, "127.0.0.1", port);
                assertNotEquals(0, reply.status());
            }
        }
    }

    @Test
    void missingModeDefaultsToTunnelForOldJson() {
        String json = """
                {
                  "enabled": true,
                  "listenHost": "127.0.0.1",
                  "listenPort": 1080,
                  "authMode": "NO_AUTH",
                  "username": "mctun",
                  "password": "change-me",
                  "maxStreams": 256,
                  "chunkSize": 16384,
                  "streamWindowBytes": 1048576,
                  "globalPendingBytes": 33554432,
                  "udpAssociations": 128,
                  "udpFragmentTimeoutMillis": 10000,
                  "udpMaxReassemblyBytes": 65536,
                  "coalesceMicros": 1000,
                  "connectTimeoutMillis": 5000
                }
                """;

        ClientConfig config = new Gson().fromJson(json, ClientConfig.class);
        assertEquals(ClientConfig.Mode.TUNNEL, config.mode());
    }

    private static ClientConfig directConfig(ClientConfig.AuthMode authMode) {
        return new ClientConfig(
                true,
                "127.0.0.1",
                0,
                authMode,
                "mctun",
                "change-me",
                256,
                16 * 1024,
                1024 * 1024,
                32 * 1024 * 1024,
                128,
                10_000,
                64 * 1024,
                1000,
                5000,
                ClientConfig.Mode.DIRECT
        );
    }

    private static Socket openSocksSocket(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static void negotiateNoAuth(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{5, 1, 0});
        out.flush();
        assertArrayEquals(new byte[]{5, 0}, readExact(in, 2));
    }

    private static void negotiatePassword(Socket socket, String username, String password, boolean success) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{5, 1, 2});
        out.flush();
        assertArrayEquals(new byte[]{5, 2}, readExact(in, 2));

        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        out.write(1);
        out.write(userBytes.length);
        out.write(userBytes);
        out.write(passBytes.length);
        out.write(passBytes);
        out.flush();
        byte[] response = readExact(in, 2);
        assertEquals(1, response[0]);
        assertEquals(success, response[1] == 0);
    }

    private static SocksReply sendCommand(Socket socket, int command, String host, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(commandRequest(command, host, port));
        out.flush();
        return readReply(socket.getInputStream());
    }

    private static byte[] commandRequest(int command, String host, int port) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(5);
        out.write(command);
        out.write(0);
        Socks5AddressType type = SocksAddresses.typeForHost(host);
        out.write(type.byteValue());
        if (type == Socks5AddressType.IPv4 || type == Socks5AddressType.IPv6) {
            out.write(InetAddress.getByName(host).getAddress());
        } else {
            byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
            out.write(hostBytes.length);
            out.write(hostBytes);
        }
        out.write((port >>> 8) & 0xff);
        out.write(port & 0xff);
        return out.toByteArray();
    }

    private static SocksReply readReply(InputStream in) throws IOException {
        byte[] header = readExact(in, 4);
        int status = header[1] & 0xff;
        int addressType = header[3] & 0xff;
        String host;
        if (addressType == Socks5AddressType.IPv4.byteValue()) {
            host = InetAddress.getByAddress(readExact(in, 4)).getHostAddress();
        } else if (addressType == Socks5AddressType.IPv6.byteValue()) {
            host = InetAddress.getByAddress(readExact(in, 16)).getHostAddress();
        } else {
            int length = readExact(in, 1)[0] & 0xff;
            host = new String(readExact(in, length), StandardCharsets.US_ASCII);
        }
        byte[] portBytes = readExact(in, 2);
        int port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
        return new SocksReply(status, host, port);
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new IOException("Unexpected EOF");
            }
            offset += read;
        }
        return bytes;
    }

    private static byte[] encodeUdp(UdpSocksPacket packet) {
        ByteBuf buf = packet.toByteBuf();
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private record SocksReply(int status, String host, int port) {
    }

    private record DirectHarness(DirectSocksManager manager) implements AutoCloseable {
        static DirectHarness start(ClientConfig config) throws Exception {
            DirectSocksManager manager = new DirectSocksManager(config);
            manager.start();
            await(() -> manager.boundAddress() != null && manager.boundAddress().getPort() > 0);
            return new DirectHarness(manager);
        }

        InetSocketAddress socksAddress() {
            return manager.boundAddress();
        }

        @Override
        public void close() {
            manager.stop();
        }
    }

    private static final class TcpEchoServer implements Closeable {
        private final ServerSocket server;
        private final Thread thread;

        private TcpEchoServer(ServerSocket server, Thread thread) {
            this.server = server;
            this.thread = thread;
        }

        static TcpEchoServer start() throws IOException {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] buf = new byte[1024];
                    int read;
                    while ((read = socket.getInputStream().read(buf)) >= 0) {
                        socket.getOutputStream().write(buf, 0, read);
                        socket.getOutputStream().flush();
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (IOException ex) {
                    if (!server.isClosed()) {
                        throw new RuntimeException(ex);
                    }
                }
            }, "mctun-direct-test-tcp-echo");
            thread.setDaemon(true);
            thread.start();
            return new TcpEchoServer(server, thread);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static final class UdpEchoServer implements Closeable {
        private final DatagramSocket socket;
        private final Thread thread;

        private UdpEchoServer(DatagramSocket socket, Thread thread) {
            this.socket = socket;
            this.thread = thread;
        }

        static UdpEchoServer start() throws IOException {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            socket.setSoTimeout(5000);
            Thread thread = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);
                        byte[] payload = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                        DatagramPacket reply = new DatagramPacket(payload, payload.length, packet.getSocketAddress());
                        socket.send(reply);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException ex) {
                        if (!socket.isClosed()) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }, "mctun-direct-test-udp-echo");
            thread.setDaemon(true);
            thread.start();
            return new UdpEchoServer(socket, thread);
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
