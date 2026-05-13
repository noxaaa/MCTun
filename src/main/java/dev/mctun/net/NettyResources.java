package dev.mctun.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NettyResources implements AutoCloseable {
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;

    public NettyResources(String name) {
        this.boss = new NioEventLoopGroup(1, threadFactory(name + "-boss"));
        this.workers = new NioEventLoopGroup(0, threadFactory(name + "-worker"));
    }

    public EventLoopGroup boss() {
        return boss;
    }

    public EventLoopGroup workers() {
        return workers;
    }

    public Class<? extends ServerChannel> serverSocketChannel() {
        return NioServerSocketChannel.class;
    }

    public Class<? extends SocketChannel> socketChannel() {
        return NioSocketChannel.class;
    }

    public ServerBootstrap serverBootstrap() {
        return new ServerBootstrap().group(boss, workers).channel(serverSocketChannel());
    }

    @Override
    public void close() {
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
