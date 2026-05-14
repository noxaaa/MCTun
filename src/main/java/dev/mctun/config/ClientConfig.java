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
        int connectTimeoutMillis,
        Mode mode
) {
    public ClientConfig {
        if (authMode == null) {
            authMode = AuthMode.NO_AUTH;
        }
        if (mode == null) {
            mode = Mode.TUNNEL;
        }
    }

    public ClientConfig(
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
        this(
                enabled,
                listenHost,
                listenPort,
                authMode,
                username,
                password,
                maxStreams,
                chunkSize,
                streamWindowBytes,
                globalPendingBytes,
                udpAssociations,
                udpFragmentTimeoutMillis,
                udpMaxReassemblyBytes,
                coalesceMicros,
                connectTimeoutMillis,
                Mode.TUNNEL
        );
    }

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
                5000,
                Mode.TUNNEL
        );
    }

    public enum AuthMode {
        NO_AUTH,
        USERNAME_PASSWORD
    }

    public enum Mode {
        TUNNEL,
        DIRECT
    }
}
