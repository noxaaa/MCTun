package dev.mctun.server;

import dev.mctun.MctunMod;
import dev.mctun.config.ServerConfig;
import dev.mctun.metrics.TunnelMetrics;
import dev.mctun.net.NettyResources;
import dev.mctun.protocol.TunnelFrame;
import dev.mctun.protocol.TunnelFrameType;
import dev.mctun.transport.ServerFrameSender;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerTunnelManager {
    private static final int UDP_PENDING_LIMIT = 1024;

    private final ServerConfig config;
    private final ServerFrameSender transport;
    private final TunnelMetrics metrics = new TunnelMetrics();
    private final NettyResources netty = new NettyResources("mctun-server");
    private final Map<StreamKey, RemoteEndpoint> streams = new ConcurrentHashMap<>();
    private final AtomicLong globalOutboundPending = new AtomicLong();

    public ServerTunnelManager(ServerConfig config, ServerFrameSender transport) {
        this.config = config;
        this.transport = transport;
    }

    public TunnelMetrics metrics() {
        return metrics;
    }

    public void receive(UUID playerId, TunnelFrame frame) {
        if (!config.enabled()) {
            return;
        }

        switch (frame.type()) {
            case OPEN_TCP -> openTcp(playerId, frame);
            case OPEN_BIND -> openBind(playerId, frame);
            case OPEN_UDP -> openUdp(playerId, frame);
            case TCP_DATA -> write(playerId, frame);
            case UDP_DATAGRAM -> writeUdp(playerId, frame);
            case WINDOW_UPDATE -> windowUpdate(playerId, frame);
            case CLOSE, ERROR -> close(playerId, frame.streamId());
            case HELLO, OPEN_RESULT, BIND_ACCEPTED -> {
            }
        }
    }

    public void disconnect(UUID playerId) {
        streams.entrySet().removeIf(entry -> {
            if (entry.getKey().playerId().equals(playerId)) {
                entry.getValue().close();
                entry.getValue().release();
                metrics.streamClosed();
                return true;
            }
            return false;
        });
    }

    public void shutdown() {
        streams.values().forEach(endpoint -> {
            endpoint.close();
            endpoint.release();
            metrics.streamClosed();
        });
        streams.clear();
        netty.close();
    }

    private void openTcp(UUID playerId, TunnelFrame frame) {
        StreamKey key = new StreamKey(playerId, frame.streamId());
        Bootstrap bootstrap = new Bootstrap()
                .group(netty.workers())
                .channel(netty.socketChannel())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new TargetHandler(playerId, frame.streamId()));
                    }
                });

        bootstrap.connect(frame.host(), frame.port()).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }

            Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
            streams.put(key, new RemoteStream(playerId, frame.streamId(), channel));
            metrics.streamOpened();
            InetSocketAddress local = (InetSocketAddress) channel.localAddress();
            String host = local.getAddress() == null ? "0.0.0.0" : local.getAddress().getHostAddress();
            transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 0, host, local.getPort(), ""));
            channel.closeFuture().addListener(close -> {
                RemoteEndpoint removed = streams.remove(key);
                if (removed != null) {
                    removed.release();
                    metrics.streamClosed();
                }
                transport.send(playerId, TunnelFrame.close(frame.streamId(), 0, "target channel closed"));
            });
        });
    }

    private void openBind(UUID playerId, TunnelFrame frame) {
        StreamKey key = new StreamKey(playerId, frame.streamId());
        AtomicReference<Channel> bindChannelRef = new AtomicReference<>();
        AtomicBoolean accepted = new AtomicBoolean();
        ServerBootstrap bootstrap = netty.serverBootstrap()
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new BindAcceptHandler(playerId, frame.streamId(), key, bindChannelRef, accepted))
                                .addLast(new TargetHandler(playerId, frame.streamId()));
                    }
                });

        int port = chooseBindPort(frame.port());
        bootstrap.bind(config.bindListenHost(), port).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }

            Channel bindChannel = ((io.netty.channel.ChannelFuture) future).channel();
            bindChannelRef.set(bindChannel);
            streams.put(key, new PendingBind(bindChannel));
            metrics.streamOpened();
            InetSocketAddress local = (InetSocketAddress) bindChannel.localAddress();
            String advertisedHost = config.bindAdvertiseHost() == null || config.bindAdvertiseHost().isBlank()
                    ? advertisedAddress(local)
                    : config.bindAdvertiseHost();
            transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 0, advertisedHost, local.getPort(), ""));
        });
    }

    private void write(UUID playerId, TunnelFrame frame) {
        RemoteEndpoint stream = streams.get(new StreamKey(playerId, frame.streamId()));
        if (stream instanceof RemoteStream) {
            stream.write(frame.payload());
        }
    }

    private void writeUdp(UUID playerId, TunnelFrame frame) {
        RemoteEndpoint endpoint = streams.get(new StreamKey(playerId, frame.streamId()));
        if (endpoint instanceof RemoteUdpAssociation udp) {
            udp.write(frame);
        }
    }

    private void windowUpdate(UUID playerId, TunnelFrame frame) {
        RemoteEndpoint stream = streams.get(new StreamKey(playerId, frame.streamId()));
        if (stream != null) {
            stream.onWindowUpdate(frame.code());
        }
    }

    private void close(UUID playerId, int streamId) {
        RemoteEndpoint stream = streams.remove(new StreamKey(playerId, streamId));
        if (stream != null) {
            stream.close();
            stream.release();
            metrics.streamClosed();
        }
    }

    private int chooseBindPort(int requestedPort) {
        // SOCKS5 BIND request dst port is the expected peer port, not the local bind port.
        return 0;
    }

    private String advertisedAddress(InetSocketAddress local) {
        if (local.getAddress() == null || local.getAddress().isAnyLocalAddress()) {
            MctunMod.LOGGER.warn("MCTun BIND advertise host is not configured; replying with 0.0.0.0 may not be reachable by peers");
            return "0.0.0.0";
        }
        return local.getAddress().getHostAddress();
    }

    private record StreamKey(UUID playerId, int streamId) {
    }

    private interface RemoteEndpoint {
        void write(byte[] bytes);

        void close();

        default void release() {
        }

        default void onWindowUpdate(int bytes) {
        }
    }

    private final class RemoteStream implements RemoteEndpoint {
        private final UUID playerId;
        private final int streamId;
        private final Channel channel;
        private final AtomicInteger outboundPending = new AtomicInteger();
        private final AtomicBoolean readPaused = new AtomicBoolean();

        private RemoteStream(UUID playerId, int streamId, Channel channel) {
            this.playerId = playerId;
            this.streamId = streamId;
            this.channel = channel;
        }

        @Override
        public void write(byte[] bytes) {
            if (channel.isActive()) {
                metrics.bytesIn(bytes.length);
                channel.writeAndFlush(Unpooled.wrappedBuffer(bytes))
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                transport.send(playerId, TunnelFrame.windowUpdate(streamId, bytes.length));
                            }
                        });
            }
        }

        @Override
        public void close() {
            channel.close();
        }

        @Override
        public void release() {
            releaseOutboundPending(outboundPending.getAndSet(0));
        }

        @Override
        public void onWindowUpdate(int bytes) {
            int released = Math.max(0, Math.min(bytes, outboundPending.get()));
            if (released == 0) {
                return;
            }
            outboundPending.addAndGet(-released);
            releaseOutboundPending(released);
            if (readPaused.get() && belowResumeThreshold()) {
                readPaused.set(false);
                channel.config().setAutoRead(true);
            }
        }

        void sendToClient(UUID playerId, int streamId, byte[] bytes) {
            outboundPending.addAndGet(bytes.length);
            globalOutboundPending.addAndGet(bytes.length);
            metrics.bytesOut(bytes.length);
            transport.send(playerId, TunnelFrame.tcpData(streamId, bytes));
            pauseIfNeeded();
        }

        private void pauseIfNeeded() {
            if (outboundPending.get() >= config.streamWindowBytes() || globalOutboundPending.get() >= config.globalPendingBytes()) {
                if (readPaused.compareAndSet(false, true)) {
                    metrics.windowPause();
                    channel.config().setAutoRead(false);
                }
            }
        }

        private boolean belowResumeThreshold() {
            return outboundPending.get() < config.streamWindowBytes() / 2
                    && globalOutboundPending.get() < config.globalPendingBytes() / 2;
        }
    }

    private final class PendingBind implements RemoteEndpoint {
        private final Channel bindChannel;

        private PendingBind(Channel bindChannel) {
            this.bindChannel = bindChannel;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void close() {
            bindChannel.close();
        }
    }

    private void openUdp(UUID playerId, TunnelFrame frame) {
        StreamKey key = new StreamKey(playerId, frame.streamId());
        Bootstrap bootstrap = new Bootstrap()
                .group(netty.workers())
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel channel) {
                        channel.pipeline().addLast(new UdpTargetHandler(playerId, frame.streamId()));
                    }
                });

        bootstrap.bind(0).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }
            Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
            streams.put(key, new RemoteUdpAssociation(channel));
            metrics.streamOpened();
            metrics.udpAssociationOpened();
            InetSocketAddress local = (InetSocketAddress) channel.localAddress();
            transport.send(playerId, TunnelFrame.openResult(frame.streamId(), 0, "0.0.0.0", local.getPort(), ""));
            channel.closeFuture().addListener(close -> {
                RemoteEndpoint removed = streams.remove(key);
                if (removed != null) {
                    removed.release();
                    metrics.streamClosed();
                }
            });
        });
    }

    private final class RemoteUdpAssociation implements RemoteEndpoint {
        private final Channel channel;
        private final AtomicInteger pendingDatagrams = new AtomicInteger();

        private RemoteUdpAssociation(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void write(byte[] bytes) {
        }

        void write(TunnelFrame frame) {
            if (channel.isActive()) {
                if (pendingDatagrams.incrementAndGet() > UDP_PENDING_LIMIT) {
                    pendingDatagrams.decrementAndGet();
                    metrics.droppedUdpDatagram();
                    return;
                }
                metrics.bytesIn(frame.payload().length);
                channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(frame.payload()), new InetSocketAddress(frame.host(), frame.port())))
                        .addListener(future -> pendingDatagrams.decrementAndGet());
            }
        }

        @Override
        public void close() {
            channel.close();
        }

        @Override
        public void release() {
            metrics.udpAssociationClosed();
        }
    }

    private final class UdpTargetHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final UUID playerId;
        private final int streamId;

        private UdpTargetHandler(UUID playerId, int streamId) {
            super(false);
            this.playerId = playerId;
            this.streamId = streamId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            try {
                InetSocketAddress sender = packet.sender();
                byte[] bytes = ByteBufUtil.getBytes(packet.content());
                metrics.bytesOut(bytes.length);
                transport.send(playerId, TunnelFrame.udpDatagram(streamId, sender.getHostString(), sender.getPort(), bytes));
            } finally {
                packet.release();
            }
        }
    }

    private final class BindAcceptHandler extends ChannelInboundHandlerAdapter {
        private final UUID playerId;
        private final int streamId;
        private final StreamKey key;
        private final AtomicReference<Channel> bindChannelRef;
        private final AtomicBoolean accepted;

        private BindAcceptHandler(UUID playerId, int streamId, StreamKey key, AtomicReference<Channel> bindChannelRef, AtomicBoolean accepted) {
            this.playerId = playerId;
            this.streamId = streamId;
            this.key = key;
            this.bindChannelRef = bindChannelRef;
            this.accepted = accepted;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (!accepted.compareAndSet(false, true)) {
                ctx.close();
                return;
            }

            streams.put(key, new RemoteStream(playerId, streamId, ctx.channel()));
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            String remoteHost = remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
            transport.send(playerId, TunnelFrame.bindAccepted(streamId, remoteHost, remote.getPort()));

            Channel bindChannel = bindChannelRef.get();
            if (bindChannel != null) {
                bindChannel.close();
            }
            ctx.channel().closeFuture().addListener(close -> {
                RemoteEndpoint removed = streams.remove(key);
                if (removed != null) {
                    removed.release();
                    metrics.streamClosed();
                    transport.send(playerId, TunnelFrame.close(streamId, 0, "bind peer closed"));
                }
            });
            super.channelActive(ctx);
        }
    }

    private final class TargetHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final UUID playerId;
        private final int streamId;

        private TargetHandler(UUID playerId, int streamId) {
            super(false);
            this.playerId = playerId;
            this.streamId = streamId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            try {
                RemoteEndpoint endpoint = streams.get(new StreamKey(playerId, streamId));
                if (endpoint instanceof RemoteStream stream) {
                    while (msg.isReadable()) {
                        int length = Math.min(msg.readableBytes(), config.chunkSize());
                        byte[] bytes = new byte[length];
                        msg.readBytes(bytes);
                        stream.sendToClient(playerId, streamId, bytes);
                    }
                }
            } finally {
                msg.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            MctunMod.LOGGER.debug("Target channel failed", cause);
            ctx.close();
        }
    }

    private void releaseOutboundPending(int bytes) {
        if (bytes > 0) {
            globalOutboundPending.addAndGet(-bytes);
        }
    }
}
