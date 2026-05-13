package dev.mctun.config;

public record ClientConfig(
        boolean enabled,
        String listenHost,
        int listenPort,
        AuthMode authMode,
        String username,
        String password,
        int maxStreams,
        int chunkSize,
        int streamWindowBytes,
        int globalPendingBytes,
        int udpAssociations,
        int udpFragmentTimeoutMillis,
        int udpMaxReassemblyBytes,
        int coalesceMicros,
        int connectTimeoutMillis
) {
    public static ClientConfig defaults() {
        return new ClientConfig(
                true,
                "127.0.0.1",
                1080,
                AuthMode.NO_AUTH,
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
                5000
        );
    }

    public enum AuthMode {
        NO_AUTH,
        USERNAME_PASSWORD
    }
}
