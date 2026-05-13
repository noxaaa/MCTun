package dev.mctun.server;

import dev.mctun.MctunMod;
import dev.mctun.config.ServerConfig;
import dev.mctun.net.NettyResources;
import dev.mctun.protocol.TunnelFrame;
import dev.mctun.protocol.TunnelFrameType;
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
import net.minecraft.server.network.ServerPlayerEntity;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerTunnelManager {
    private final ServerConfig config;
    private final ServerTunnelTransport transport;
    private final NettyResources netty = new NettyResources("mctun-server");
    private final Map<StreamKey, RemoteEndpoint> streams = new ConcurrentHashMap<>();

    public ServerTunnelManager(ServerConfig config, ServerTunnelTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    public void receive(ServerPlayerEntity player, TunnelFrame frame) {
        if (!config.enabled()) {
            return;
        }

        switch (frame.type()) {
            case OPEN_TCP -> openTcp(player, frame);
            case OPEN_BIND -> openBind(player, frame);
            case OPEN_UDP -> openUdp(player, frame);
            case TCP_DATA -> write(player, frame);
            case UDP_DATAGRAM -> writeUdp(player, frame);
            case CLOSE, ERROR -> close(player, frame.streamId());
            case HELLO, OPEN_RESULT, BIND_ACCEPTED, WINDOW_UPDATE -> {
            }
        }
    }

    public void disconnect(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        streams.entrySet().removeIf(entry -> {
            if (entry.getKey().playerId().equals(playerId)) {
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    private void openTcp(ServerPlayerEntity player, TunnelFrame frame) {
        StreamKey key = new StreamKey(player.getUuid(), frame.streamId());
        Bootstrap bootstrap = new Bootstrap()
                .group(netty.workers())
                .channel(netty.socketChannel())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new TargetHandler(player, frame.streamId()));
                    }
                });

        bootstrap.connect(frame.host(), frame.port()).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(player, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }

            Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
            streams.put(key, new RemoteStream(channel));
            InetSocketAddress local = (InetSocketAddress) channel.localAddress();
            String host = local.getAddress() == null ? "0.0.0.0" : local.getAddress().getHostAddress();
            transport.send(player, TunnelFrame.openResult(frame.streamId(), 0, host, local.getPort(), ""));
            channel.closeFuture().addListener(close -> {
                streams.remove(key);
                transport.send(player, TunnelFrame.close(frame.streamId(), 0, "target channel closed"));
            });
        });
    }

    private void openBind(ServerPlayerEntity player, TunnelFrame frame) {
        StreamKey key = new StreamKey(player.getUuid(), frame.streamId());
        AtomicReference<Channel> bindChannelRef = new AtomicReference<>();
        AtomicBoolean accepted = new AtomicBoolean();
        ServerBootstrap bootstrap = netty.serverBootstrap()
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new BindAcceptHandler(player, frame.streamId(), key, bindChannelRef, accepted))
                                .addLast(new TargetHandler(player, frame.streamId()));
                    }
                });

        int port = chooseBindPort(frame.port());
        bootstrap.bind(config.bindListenHost(), port).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(player, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }

            Channel bindChannel = ((io.netty.channel.ChannelFuture) future).channel();
            bindChannelRef.set(bindChannel);
            InetSocketAddress local = (InetSocketAddress) bindChannel.localAddress();
            String advertisedHost = config.bindAdvertiseHost() == null || config.bindAdvertiseHost().isBlank()
                    ? advertisedAddress(local)
                    : config.bindAdvertiseHost();
            transport.send(player, TunnelFrame.openResult(frame.streamId(), 0, advertisedHost, local.getPort(), ""));
        });
    }

    private void write(ServerPlayerEntity player, TunnelFrame frame) {
        RemoteEndpoint stream = streams.get(new StreamKey(player.getUuid(), frame.streamId()));
        if (stream instanceof RemoteStream) {
            stream.write(frame.payload());
        }
    }

    private void writeUdp(ServerPlayerEntity player, TunnelFrame frame) {
        RemoteEndpoint endpoint = streams.get(new StreamKey(player.getUuid(), frame.streamId()));
        if (endpoint instanceof RemoteUdpAssociation udp) {
            udp.write(frame);
        }
    }

    private void close(ServerPlayerEntity player, int streamId) {
        RemoteEndpoint stream = streams.remove(new StreamKey(player.getUuid(), streamId));
        if (stream != null) {
            stream.close();
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
    }

    private static final class RemoteStream implements RemoteEndpoint {
        private final Channel channel;

        private RemoteStream(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void write(byte[] bytes) {
            if (channel.isActive()) {
                channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
            }
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    private void openUdp(ServerPlayerEntity player, TunnelFrame frame) {
        StreamKey key = new StreamKey(player.getUuid(), frame.streamId());
        Bootstrap bootstrap = new Bootstrap()
                .group(netty.workers())
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel channel) {
                        channel.pipeline().addLast(new UdpTargetHandler(player, frame.streamId()));
                    }
                });

        bootstrap.bind(0).addListener(future -> {
            if (!future.isSuccess()) {
                transport.send(player, TunnelFrame.openResult(frame.streamId(), 1, "0.0.0.0", 0, future.cause().getMessage()));
                return;
            }
            Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
            streams.put(key, new RemoteUdpAssociation(channel));
            InetSocketAddress local = (InetSocketAddress) channel.localAddress();
            transport.send(player, TunnelFrame.openResult(frame.streamId(), 0, "0.0.0.0", local.getPort(), ""));
            channel.closeFuture().addListener(close -> streams.remove(key));
        });
    }

    private static final class RemoteUdpAssociation implements RemoteEndpoint {
        private final Channel channel;

        private RemoteUdpAssociation(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void write(byte[] bytes) {
        }

        void write(TunnelFrame frame) {
            if (channel.isActive()) {
                channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(frame.payload()), new InetSocketAddress(frame.host(), frame.port())));
            }
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    private final class UdpTargetHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        private final ServerPlayerEntity player;
        private final int streamId;

        private UdpTargetHandler(ServerPlayerEntity player, int streamId) {
            super(false);
            this.player = player;
            this.streamId = streamId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            try {
                InetSocketAddress sender = packet.sender();
                byte[] bytes = ByteBufUtil.getBytes(packet.content());
                transport.send(player, TunnelFrame.udpDatagram(streamId, sender.getHostString(), sender.getPort(), bytes));
            } finally {
                packet.release();
            }
        }
    }

    private final class BindAcceptHandler extends ChannelInboundHandlerAdapter {
        private final ServerPlayerEntity player;
        private final int streamId;
        private final StreamKey key;
        private final AtomicReference<Channel> bindChannelRef;
        private final AtomicBoolean accepted;

        private BindAcceptHandler(ServerPlayerEntity player, int streamId, StreamKey key, AtomicReference<Channel> bindChannelRef, AtomicBoolean accepted) {
            this.player = player;
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

            streams.put(key, new RemoteStream(ctx.channel()));
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            String remoteHost = remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
            transport.send(player, TunnelFrame.bindAccepted(streamId, remoteHost, remote.getPort()));

            Channel bindChannel = bindChannelRef.get();
            if (bindChannel != null) {
                bindChannel.close();
            }
            ctx.channel().closeFuture().addListener(close -> {
                streams.remove(key);
                transport.send(player, TunnelFrame.close(streamId, 0, "bind peer closed"));
            });
            super.channelActive(ctx);
        }
    }

    private final class TargetHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final ServerPlayerEntity player;
        private final int streamId;

        private TargetHandler(ServerPlayerEntity player, int streamId) {
            super(false);
            this.player = player;
            this.streamId = streamId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            try {
                transport.send(player, TunnelFrame.tcpData(streamId, ByteBufUtil.getBytes(msg)));
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
}
