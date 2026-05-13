package dev.mctun.client;

import dev.mctun.MctunMod;
import dev.mctun.config.ClientConfig;
import dev.mctun.net.NettyResources;
import dev.mctun.protocol.TunnelFrame;
import dev.mctun.protocol.TunnelFrameType;
import dev.mctun.socks.UdpFragmentReassembler;
import dev.mctun.socks.UdpSocksPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.SocksVersion;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientTunnelManager {
    private final ClientConfig config;
    private final ClientTunnelTransport transport;
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final Map<Integer, LocalStream> streams = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private NettyResources netty;
    private Channel listener;

    public ClientTunnelManager(ClientConfig config, ClientTunnelTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    public void start() {
        if (!config.enabled() || !started.compareAndSet(false, true)) {
            return;
        }

        netty = new NettyResources("mctun-client");
        ServerBootstrap bootstrap = netty.serverBootstrap()
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(Socks5ServerEncoder.DEFAULT)
                                .addLast(new Socks5InitialRequestDecoder())
                                .addLast(new SocksHandler());
                    }
                });

        listener = bootstrap.bind(new InetSocketAddress(config.listenHost(), config.listenPort()))
                .addListener(future -> {
                    if (future.isSuccess()) {
                        MctunMod.LOGGER.info("MCTun SOCKS5 listener started on {}:{}", config.listenHost(), config.listenPort());
                    } else {
                        MctunMod.LOGGER.error("Failed to start MCTun SOCKS5 listener", future.cause());
                        stop();
                    }
                })
                .channel();
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        streams.values().forEach(LocalStream::close);
        streams.clear();
        if (listener != null) {
            listener.close();
            listener = null;
        }
        if (netty != null) {
            netty.close();
            netty = null;
        }
    }

    public void receive(TunnelFrame frame) {
        if (frame.type() == TunnelFrameType.OPEN_RESULT) {
            LocalStream stream = streams.get(frame.streamId());
            if (stream != null) {
                stream.onOpenResult(frame);
            }
            return;
        }
        if (frame.type() == TunnelFrameType.BIND_ACCEPTED) {
            LocalStream stream = streams.get(frame.streamId());
            if (stream != null) {
                stream.onBindAccepted(frame);
            }
            return;
        }

        LocalStream stream = streams.get(frame.streamId());
        if (stream == null) {
            return;
        }

        switch (frame.type()) {
            case TCP_DATA -> stream.write(frame.payload());
            case UDP_DATAGRAM -> stream.writeUdp(frame);
            case CLOSE, ERROR -> stream.close();
            case WINDOW_UPDATE, HELLO, OPEN_TCP, OPEN_BIND, BIND_ACCEPTED, OPEN_UDP -> {
            }
        }
    }

    private final class SocksHandler extends SimpleChannelInboundHandler<Object> {
        private int streamId;

        SocksHandler() {
            super(false);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Socks5InitialRequest request) {
                handleInitial(ctx, request);
                return;
            }
            if (msg instanceof Socks5PasswordAuthRequest request) {
                handlePassword(ctx, request);
                return;
            }
            if (msg instanceof Socks5CommandRequest request) {
                handleCommand(ctx, request);
                return;
            }
            if (msg instanceof ByteBuf buf) {
                handleData(ctx, buf);
            }
        }

        private void handleInitial(ChannelHandlerContext ctx, Socks5InitialRequest request) {
            if (request.version() != SocksVersion.SOCKS5) {
                ctx.close();
                return;
            }

            if (requiresPassword()) {
                if (!request.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
                    ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                ctx.pipeline().replace(Socks5InitialRequestDecoder.class, "socks-password-decoder", new Socks5PasswordAuthRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
                return;
            }

            if (!request.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            ctx.pipeline().replace(Socks5InitialRequestDecoder.class, "socks-command-decoder", new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        }

        private void handlePassword(ChannelHandlerContext ctx, Socks5PasswordAuthRequest request) {
            if (config.username().equals(request.username()) && config.password().equals(request.password())) {
                ctx.pipeline().replace(Socks5PasswordAuthRequestDecoder.class, "socks-command-decoder", new Socks5CommandRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
            } else {
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(ChannelFutureListener.CLOSE);
            }
        }

        private void handleCommand(ChannelHandlerContext ctx, Socks5CommandRequest request) {
            streamId = nextStreamId.getAndIncrement();
            LocalStream stream = new LocalStream(streamId, ctx.channel(), request);
            streams.put(streamId, stream);
            ctx.channel().closeFuture().addListener(future -> {
                streams.remove(streamId);
                transport.send(TunnelFrame.close(streamId, 0, "local channel closed"));
            });

            if (request.type() == Socks5CommandType.CONNECT) {
                ctx.channel().config().setAutoRead(false);
                transport.send(TunnelFrame.openTcp(streamId, request.dstAddr(), request.dstPort()));
                return;
            }
            if (request.type() == Socks5CommandType.BIND) {
                ctx.channel().config().setAutoRead(false);
                transport.send(TunnelFrame.openBind(streamId, request.dstAddr(), request.dstPort()));
                return;
            }
            if (request.type() == Socks5CommandType.UDP_ASSOCIATE) {
                stream.openUdpRelay();
                transport.send(TunnelFrame.openUdp(streamId, request.dstAddr(), request.dstPort()));
                return;
            }

            stream.closeWithStatus(Socks5CommandStatus.COMMAND_UNSUPPORTED);
        }

        private void handleData(ChannelHandlerContext ctx, ByteBuf buf) {
            try {
                if (streamId == 0 || !buf.isReadable()) {
                    return;
                }
                transport.send(TunnelFrame.tcpData(streamId, ByteBufUtil.getBytes(buf)));
            } finally {
                buf.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            MctunMod.LOGGER.debug("SOCKS client channel failed", cause);
            ctx.close();
        }

        private boolean requiresPassword() {
            return config.authMode() == ClientConfig.AuthMode.USERNAME_PASSWORD;
        }
    }

    private final class LocalStream {
        private final int id;
        private final Channel channel;
        private final Socks5CommandRequest request;
        private UdpRelay udpRelay;

        private LocalStream(int id, Channel channel, Socks5CommandRequest request) {
            this.id = id;
            this.channel = channel;
            this.request = request;
        }

        void onOpenResult(TunnelFrame frame) {
            Socks5CommandStatus status = frame.code() == 0 ? Socks5CommandStatus.SUCCESS : Socks5CommandStatus.FAILURE;
            String replyHost = frame.host();
            int replyPort = frame.port();
            if (status == Socks5CommandStatus.SUCCESS && request.type() == Socks5CommandType.UDP_ASSOCIATE && udpRelay != null) {
                InetSocketAddress local = (InetSocketAddress) udpRelay.channel.localAddress();
                replyHost = local.getAddress() == null ? local.getHostString() : local.getAddress().getHostAddress();
                replyPort = local.getPort();
            }
            channel.writeAndFlush(new DefaultSocks5CommandResponse(status, replyAddressType(request), replyHost, replyPort))
                    .addListener(future -> {
                        if (future.isSuccess() && status == Socks5CommandStatus.SUCCESS && request.type() == Socks5CommandType.CONNECT) {
                            removeSocksDecoders(channel);
                            channel.config().setAutoRead(true);
                        } else if (future.isSuccess() && status == Socks5CommandStatus.SUCCESS && request.type() == Socks5CommandType.BIND) {
                            channel.config().setAutoRead(false);
                        } else if (future.isSuccess() && status == Socks5CommandStatus.SUCCESS && request.type() == Socks5CommandType.UDP_ASSOCIATE) {
                            channel.config().setAutoRead(false);
                        } else {
                            close();
                        }
                    });
        }

        void onBindAccepted(TunnelFrame frame) {
            channel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, replyAddressType(request), frame.host(), frame.port()))
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            removeSocksDecoders(channel);
                            channel.config().setAutoRead(true);
                        } else {
                            close();
                        }
                    });
        }

        void closeWithStatus(Socks5CommandStatus status) {
            channel.writeAndFlush(new DefaultSocks5CommandResponse(status, replyAddressType(request), "0.0.0.0", 0))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        void write(byte[] bytes) {
            if (channel.isActive()) {
                channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
            }
        }

        void writeUdp(TunnelFrame frame) {
            if (udpRelay != null) {
                udpRelay.write(frame);
            }
        }

        void openUdpRelay() {
            Bootstrap bootstrap = new Bootstrap()
                    .group(netty.workers())
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel channel) {
                            channel.pipeline().addLast(new UdpRelayHandler(id));
                        }
                    });
            Channel udpChannel = bootstrap.bind(config.listenHost(), 0).syncUninterruptibly().channel();
            udpRelay = new UdpRelay(udpChannel);
        }

        void close() {
            if (udpRelay != null) {
                udpRelay.close();
            }
            channel.close();
        }

        private void removeSocksDecoders(Channel channel) {
            if (channel.pipeline().get(Socks5CommandRequestDecoder.class) != null) {
                channel.pipeline().remove(Socks5CommandRequestDecoder.class);
            }
        }

        private Socks5AddressType replyAddressType(Socks5CommandRequest request) {
            return request.dstAddrType();
        }
    }

    private final class UdpRelay {
        private final Channel channel;
        private volatile InetSocketAddress applicationAddress;

        private UdpRelay(Channel channel) {
            this.channel = channel;
        }

        void remember(InetSocketAddress sender) {
            applicationAddress = sender;
        }

        void write(TunnelFrame frame) {
            InetSocketAddress recipient = applicationAddress;
            if (recipient == null || !channel.isActive()) {
                return;
            }
            UdpSocksPacket packet = new UdpSocksPacket(0, io.netty.handler.codec.socksx.v5.Socks5AddressType.DOMAIN, frame.host(), frame.port(), frame.payload());
            channel.writeAndFlush(new DatagramPacket(packet.toByteBuf(), recipient));
        }

        void close() {
            channel.close();
        }
    }

    private final class UdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final int streamId;
        private final UdpFragmentReassembler reassembler;

        private UdpRelayHandler(int streamId) {
            super(false);
            this.streamId = streamId;
            this.reassembler = new UdpFragmentReassembler(config.udpFragmentTimeoutMillis(), config.udpMaxReassemblyBytes());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            try {
                LocalStream stream = streams.get(streamId);
                if (stream == null) {
                    return;
                }
                stream.udpRelay.remember(packet.sender());
                UdpSocksPacket socksPacket = UdpSocksPacket.read(packet.content());
                reassembler.accept(socksPacket).ifPresent(payload ->
                        transport.send(TunnelFrame.udpDatagram(streamId, socksPacket.host(), socksPacket.port(), payload)));
            } finally {
                packet.release();
            }
        }
    }
}
