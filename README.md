# MCTun

MCTun is a Fabric client/server mod that exposes a local SOCKS5 proxy on the Minecraft client and forwards traffic through the connected Minecraft server.

Traffic path:

```text
SOCKS5-capable application -> Minecraft client -> Minecraft server -> destination
```

## Current Status

Implemented:

- Fabric 1.21.1 project scaffold.
- Netty-based SOCKS5 listener on the client.
- SOCKS5 no-auth and username/password authentication.
- SOCKS5 `CONNECT`.
- SOCKS5 `BIND` two-stage response flow.
- SOCKS5 `UDP ASSOCIATE` with UDP relay.
- SOCKS5 UDP header parsing and FRAG reassembly.
- Minecraft custom payload tunnel frames.
- Netty TCP/UDP outbound on the server.
- Default server egress is open, per project requirement.

Still intentionally simple:

- Flow-control frame types exist, but adaptive backpressure/window accounting is not fully tuned yet.
- UDP queues are functional but do not yet expose detailed drop metrics.
- Native kqueue/epoll selection is not wired yet; the implementation currently uses NIO transport.

## Build

```bash
GRADLE_USER_HOME=/private/tmp/mctun-gradle-home ./gradlew build
```

The project targets Java 21 bytecode using `--release 21`. It can be built on a newer local JDK.

## Configuration

Config files are generated on first run:

- `config/mctun-client.json`
- `config/mctun-server.json`

Client defaults:

- SOCKS5 listener: `127.0.0.1:1080`
- Auth mode: no-auth
- Chunk size: `16 KiB`
- Per-stream window: `1 MiB`
- Global pending target: `32 MiB`

Server defaults:

- `allowAllDestinations=true`
- BIND listener binds an ephemeral port on `0.0.0.0`
- Server logs a warning when open egress is enabled.

## Usage

Run a Fabric server and client with this mod installed on both sides, join the server from the client, then point an application at:

```text
socks5://127.0.0.1:1080
```

Example:

```bash
curl --socks5-hostname 127.0.0.1:1080 http://example.com
```
