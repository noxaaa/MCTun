package dev.mctun.config;

public record ServerConfig(
        boolean enabled,
        boolean allowAllDestinations,
        int maxStreamsPerPlayer,
        int maxTotalStreams,
        int maxUdpAssociationsPerPlayer,
        String bindListenHost,
        String bindAdvertiseHost,
        int bindPortMin,
        int bindPortMax,
        int chunkSize,
        int streamWindowBytes,
        int globalPendingBytes,
        int connectTimeoutMillis
) {
    public static ServerConfig defaults() {
        return new ServerConfig(
                true,
                true,
                256,
                2048,
                128,
                "0.0.0.0",
                "",
                20_000,
                30_000,
                16 * 1024,
                1024 * 1024,
                32 * 1024 * 1024,
                5000
        );
    }
}
