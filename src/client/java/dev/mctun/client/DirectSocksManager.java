package dev.mctun.client;

import dev.mctun.MctunMod;
import dev.mctun.config.ClientConfig;
import dev.mctun.metrics.TunnelMetrics;
import dev.mctun.net.NettyResources;
import dev.mctun.socks.SocksAddresses;
import dev.mctun.socks.UdpFragmentReassembler;
import dev.mctun.socks.UdpSocksPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
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
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectSocksManager {
    private static final int UDP_PENDING_LIMIT = 1024;

    private final ClientConfig config;
    private final TunnelMetrics metrics = new TunnelMetrics();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final Map<Integer, DirectStream> streams = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private NettyResources netty;
    private Channel listener;

    public DirectSocksManager(ClientConfig config) {
        this.config = config;
    }

    public TunnelMetrics metrics() {
        return metrics;
    }

    public InetSocketAddress boundAddress() {
        if (listener == null || listener.localAddress() == null) {
            return null;
        }
        SocketAddress address = listener.localAddress();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    public void start() {
        if (!config.enabled() || !started.compareAndSet(false, true)) {
            return;
        }

        netty = new NettyResources("mctun-direct");
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
                        MctunMod.LOGGER.info("MCTun direct SOCKS5 listener started on {}:{}", config.listenHost(), config.listenPort());
                    } else {
                        MctunMod.LOGGER.error("Failed to start MCTun direct SOCKS5 listener", future.cause());
                        stop();
                    }
                })
                .channel();
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        streams.values().forEach(DirectStream::close);
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
                handleData(buf);
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
            DirectStream stream = new DirectStream(streamId, ctx.channel(), request);
            streams.put(streamId, stream);
            metrics.streamOpened();
            ctx.channel().closeFuture().addListener(future -> {
                DirectStream removed = streams.remove(streamId);
                if (removed != null) {
                    removed.closeResources();
                    metrics.streamClosed();
                }
            });

            if (request.type() == Socks5CommandType.CONNECT) {
                stream.openConnect();
                return;
            }
            if (request.type() == Socks5CommandType.BIND) {
                stream.openBind();
                return;
            }
            if (request.type() == Socks5CommandType.UDP_ASSOCIATE) {
                stream.openUdpRelay();
                return;
            }

            stream.closeWithStatus(Socks5CommandStatus.COMMAND_UNSUPPORTED);
        }

        private void handleData(ByteBuf buf) {
            try {
                if (streamId == 0 || !buf.isReadable()) {
                    return;
                }
                DirectStream stream = streams.get(streamId);
                if (stream != null) {
                    stream.writeToTarget(buf);
                }
            } finally {
                buf.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            MctunMod.LOGGER.debug("Direct SOCKS client channel failed", cause);
            ctx.close();
        }

        private boolean requiresPassword() {
            return config.authMode() == ClientConfig.AuthMode.USERNAME_PASSWORD;
        }
    }

    private final class DirectStream {
        private final int id;
        private final Channel applicationChannel;
        private final Socks5CommandRequest request;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Channel targetChannel;
        private Channel bindChannel;
        private DirectUdpRelay udpRelay;

        private DirectStream(int id, Channel applicationChannel, Socks5CommandRequest request) {
            this.id = id;
            this.applicationChannel = applicationChannel;
            this.request = request;
        }

        void openConnect() {
            applicationChannel.config().setAutoRead(false);
            Bootstrap bootstrap = new Bootstrap()
                    .group(netty.workers())
                    .channel(netty.socketChannel())
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new TargetHandler(id));
                        }
                    });

            bootstrap.connect(request.dstAddr(), request.dstPort()).addListener(future -> {
                if (!future.isSuccess()) {
                    closeWithStatus(Socks5CommandStatus.FAILURE);
                    return;
                }
                targetChannel = ((io.netty.channel.ChannelFuture) future).channel();
                InetSocketAddress local = (InetSocketAddress) targetChannel.localAddress();
                String host = hostString(local);
                applicationChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, replyAddressType(host), host, local.getPort()))
                        .addListener(reply -> {
                            if (reply.isSuccess()) {
                                removeSocksDecoders(applicationChannel);
                                applicationChannel.config().setAutoRead(true);
                                targetChannel.config().setAutoRead(true);
                            } else {
                                close();
                            }
                        });
                targetChannel.closeFuture().addListener(close -> close());
            });
        }

        void openBind() {
            applicationChannel.config().setAutoRead(false);
            ServerBootstrap bootstrap = netty.serverBootstrap()
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new BindAcceptHandler(id))
                                    .addLast(new TargetHandler(id));
                        }
                    });

            bootstrap.bind(config.listenHost(), 0).addListener(future -> {
                if (!future.isSuccess()) {
                    closeWithStatus(Socks5CommandStatus.FAILURE);
                    return;
                }
                bindChannel = ((io.netty.channel.ChannelFuture) future).channel();
                InetSocketAddress local = (InetSocketAddress) bindChannel.localAddress();
                String host = hostString(local);
                applicationChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, replyAddressType(host), host, local.getPort()))
                        .addListener(reply -> {
                            if (!reply.isSuccess()) {
                                close();
                            }
                        });
            });
        }

        void openUdpRelay() {
            applicationChannel.config().setAutoRead(false);
            Bootstrap bootstrap = new Bootstrap()
                    .group(netty.workers())
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel channel) {
                            channel.pipeline().addLast(new DirectUdpRelayHandler(id));
                        }
                    });

            bootstrap.bind(config.listenHost(), 0).addListener(future -> {
                if (!future.isSuccess()) {
                    closeWithStatus(Socks5CommandStatus.FAILURE);
                    return;
                }
                Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
                udpRelay = new DirectUdpRelay(channel);
                metrics.udpAssociationOpened();
                InetSocketAddress local = (InetSocketAddress) channel.localAddress();
                String host = hostString(local);
                applicationChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, replyAddressType(host), host, local.getPort()))
                        .addListener(reply -> {
                            if (!reply.isSuccess()) {
                                close();
                            }
                        });
            });
        }

        void closeWithStatus(Socks5CommandStatus status) {
            applicationChannel.writeAndFlush(new DefaultSocks5CommandResponse(status, Socks5AddressType.IPv4, "0.0.0.0", 0))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        void writeToTarget(ByteBuf buf) {
            Channel target = targetChannel;
            if (target == null || !target.isActive()) {
                return;
            }
            while (buf.isReadable()) {
                int length = Math.min(buf.readableBytes(), config.chunkSize());
                ByteBuf slice = buf.readRetainedSlice(length);
                metrics.bytesOut(length);
                target.writeAndFlush(slice);
            }
        }

        void writeToApplication(ByteBuf buf) {
            if (applicationChannel.isActive()) {
                metrics.bytesIn(buf.readableBytes());
                applicationChannel.writeAndFlush(buf.retainedDuplicate());
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                streams.remove(id);
                closeResources();
                metrics.streamClosed();
            }
        }

        void closeResources() {
            if (targetChannel != null) {
                targetChannel.close();
                targetChannel = null;
            }
            if (bindChannel != null) {
                bindChannel.close();
                bindChannel = null;
            }
            if (udpRelay != null) {
                udpRelay.close();
                udpRelay = null;
                metrics.udpAssociationClosed();
            }
            applicationChannel.close();
        }

        private void removeSocksDecoders(Channel channel) {
            if (channel.pipeline().get(Socks5CommandRequestDecoder.class) != null) {
                channel.pipeline().remove(Socks5CommandRequestDecoder.class);
            }
        }

        private Socks5AddressType replyAddressType(String host) {
            return SocksAddresses.typeForHost(host);
        }
    }

    private final class TargetHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final int streamId;

        private TargetHandler(int streamId) {
            super(false);
            this.streamId = streamId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            try {
                DirectStream stream = streams.get(streamId);
                if (stream != null) {
                    stream.writeToApplication(msg);
                }
            } finally {
                msg.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            MctunMod.LOGGER.debug("Direct target channel failed", cause);
            ctx.close();
        }
    }

    private final class BindAcceptHandler extends ChannelInboundHandlerAdapter {
        private final int streamId;

        private BindAcceptHandler(int streamId) {
            this.streamId = streamId;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            DirectStream stream = streams.get(streamId);
            if (stream == null) {
                ctx.close();
                return;
            }
            if (stream.targetChannel != null) {
                ctx.close();
                return;
            }
            stream.targetChannel = ctx.channel();
            if (stream.bindChannel != null) {
                stream.bindChannel.close();
                stream.bindChannel = null;
            }
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            String host = hostString(remote);
            stream.applicationChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, SocksAddresses.typeForHost(host), host, remote.getPort()))
                    .addListener(reply -> {
                        if (reply.isSuccess()) {
                            stream.removeSocksDecoders(stream.applicationChannel);
                            stream.applicationChannel.config().setAutoRead(true);
                            ctx.channel().config().setAutoRead(true);
                        } else {
                            stream.close();
                        }
                    });
            ctx.channel().closeFuture().addListener(close -> stream.close());
            super.channelActive(ctx);
        }
    }

    private final class DirectUdpRelay {
        private final Channel channel;
        private final AtomicInteger pendingDatagrams = new AtomicInteger();
        private volatile InetSocketAddress applicationAddress;

        private DirectUdpRelay(Channel channel) {
            this.channel = channel;
        }

        boolean isApplicationAddress(InetSocketAddress sender) {
            InetSocketAddress address = applicationAddress;
            if (address == null) {
                applicationAddress = sender;
                return true;
            }
            return address.equals(sender);
        }

        void writeToApplication(InetSocketAddress sender, ByteBuf payload) {
            InetSocketAddress recipient = applicationAddress;
            if (recipient == null || !channel.isActive()) {
                return;
            }
            if (pendingDatagrams.incrementAndGet() > UDP_PENDING_LIMIT) {
                pendingDatagrams.decrementAndGet();
                metrics.droppedUdpDatagram();
                return;
            }
            byte[] bytes = ByteBufUtil.getBytes(payload);
            metrics.bytesIn(bytes.length);
            UdpSocksPacket packet = UdpSocksPacket.fromHost(0, sender.getHostString(), sender.getPort(), bytes);
            channel.writeAndFlush(new DatagramPacket(packet.toByteBuf(), recipient))
                    .addListener(future -> pendingDatagrams.decrementAndGet());
        }

        void writeToTarget(UdpSocksPacket packet) {
            if (!channel.isActive()) {
                return;
            }
            if (pendingDatagrams.incrementAndGet() > UDP_PENDING_LIMIT) {
                pendingDatagrams.decrementAndGet();
                metrics.droppedUdpDatagram();
                return;
            }
            metrics.bytesOut(packet.payload().length);
            channel.writeAndFlush(new DatagramPacket(io.netty.buffer.Unpooled.wrappedBuffer(packet.payload()), new InetSocketAddress(packet.host(), packet.port())))
                    .addListener(future -> pendingDatagrams.decrementAndGet());
        }

        void close() {
            channel.close();
        }
    }

    private final class DirectUdpRelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final int streamId;
        private final UdpFragmentReassembler reassembler;

        private DirectUdpRelayHandler(int streamId) {
            super(false);
            this.streamId = streamId;
            this.reassembler = new UdpFragmentReassembler(config.udpFragmentTimeoutMillis(), config.udpMaxReassemblyBytes());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            try {
                DirectStream stream = streams.get(streamId);
                if (stream == null || stream.udpRelay == null) {
                    return;
                }
                InetSocketAddress sender = packet.sender();
                if (!stream.udpRelay.isApplicationAddress(sender)) {
                    stream.udpRelay.writeToApplication(sender, packet.content());
                    return;
                }

                UdpSocksPacket socksPacket;
                try {
                    socksPacket = UdpSocksPacket.read(packet.content());
                } catch (IllegalArgumentException ex) {
                    metrics.malformedUdpDatagram();
                    return;
                }
                UdpFragmentReassembler.Result result = reassembler.acceptPacket(socksPacket);
                if (result.status() == UdpFragmentReassembler.Status.COMPLETE) {
                    stream.udpRelay.writeToTarget(new UdpSocksPacket(0, socksPacket.addressType(), socksPacket.host(), socksPacket.port(), result.payload()));
                } else if (result.status() == UdpFragmentReassembler.Status.DROPPED) {
                    metrics.droppedUdpDatagram();
                }
            } finally {
                packet.release();
            }
        }
    }

    private String hostString(InetSocketAddress address) {
        if (address.getAddress() == null) {
            return address.getHostString();
        }
        return address.getAddress().getHostAddress();
    }
}
