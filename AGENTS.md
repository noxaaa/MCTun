# MCTun Implementation Plan

## Summary
- Create a Fabric client/server mod for Minecraft `1.21.1`, targeting Java `21`.
- Fixed traffic path: `SOCKS5-capable application -> local Minecraft client SOCKS5 listener -> Minecraft server -> destination`.
- Prioritize mature libraries and transfer performance: Netty handles SOCKS5 codecs, socket I/O, event loops, and buffer management. Project code focuses on Minecraft transport adaptation, session orchestration, and small RFC glue where Netty does not provide a full abstraction.
- SOCKS5 scope: `CONNECT`, `BIND`, `UDP ASSOCIATE`, IPv4/IPv6/domain addresses, no-auth, username/password auth, and UDP FRAG reassembly.

## Key Changes
- Scaffold a Fabric Gradle project with common/client/server entrypoints, `fabric.mod.json`, README, and config defaults.
- Use `io.netty.handler.codec.socksx.v5` for SOCKS5 method negotiation, username/password auth, and command decoding/encoding.
- Use Netty `ServerBootstrap`, `Bootstrap`, TCP channels, UDP datagram channels, pooled `ByteBuf`s, and bounded queues for high-throughput transport.
- Use one Fabric custom payload channel to carry compact binary tunnel frames between client and server.
- Implement tunnel frame types for hello, TCP open, BIND open/accepted, UDP association open, TCP data, UDP datagram, window update, close, and error.
- Keep custom protocol code small and testable. Only custom-write pieces that are project-specific or not fully covered by Netty, especially Minecraft payload framing and SOCKS5 UDP FRAG association handling.
- Default client SOCKS listener: `127.0.0.1:1080`.
- Default server egress policy: fully open, per user request. Server startup must log a clear warning when open egress is enabled.

## Performance Defaults
- TCP data chunk size: `16 KiB`.
- Per-stream window: `1 MiB`.
- Global pending bytes: `32 MiB`.
- TCP small-packet coalescing: up to `1 ms` or chunk size, whichever comes first.
- UDP preserves datagram boundaries and never coalesces multiple datagrams into one logical SOCKS datagram.
- Minecraft networking callbacks must not block on socket I/O. They decode and enqueue only.
- Prefer pooled buffers and explicit release paths to avoid allocation pressure and direct memory leaks.

## Test Plan
- Unit tests for SOCKS5 command/auth flow, address encoding, tunnel frame encoding, UDP header handling, and UDP FRAG reassembly.
- Integration tests for CONNECT with TCP echo, BIND two-stage reply, UDP echo/DNS-like traffic, auth failures, connection failures, and control connection shutdown.
- Performance checks for single-stream throughput, multi-stream throughput, small-packet latency, UDP burst behavior, and long-running memory stability.

## Assumptions
- Java target is `21` even if the local machine has a newer JDK installed.
- GSS-API/Kerberos SOCKS5 authentication is out of scope. RFC1929 username/password auth is in scope.
- Tunnel payloads are not additionally encrypted beyond the Minecraft connection.
- Client listener binds loopback by default even though server egress is open.
