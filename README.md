# P2P Terminal Chat

A peer-to-peer, end-to-end encrypted chat that runs in your **terminal**. Two or
more people connect directly — no server, no accounts, no cloud. Works over
WiFi/LAN, the internet (Tailscale / port forwarding / tunnel), and even with no
WiFi at all (multi-hop mesh).

```
┌─────────┐        TCP        ┌─────────┐
│  Alice  │◄══════════════════►│   Bob   │
└─────────┘  ECDH + AES-GCM   └─────────┘
     └── mesh mode: relays hop-by-hop through peers ──► Carol
```

## Features

- **End-to-end encrypted** — X25519 (ECDH) key exchange, AES-256-GCM per message, HKDF-SHA256 key derivation. All cryptography is from the JDK standard library.
- **Group rooms** — hosts relay `@MSG` to every member; `@join`, `@list`, `@users`.
- **Rooms across networks** — `@link <host> [port]` bridges hosts; the same room name becomes one shared room (`alice@A6B5F2AC`).
- **File sharing** — `@send <path>` sends any file/media by its path to everyone in the room (saved to `./downloads`). Live receive progress, `@cancel <file>` to abort a transfer, and collision-proof names (`avatar (1).png`) when the same name arrives twice.
- **Terminal games** — play Tic-Tac-Toe (`@ttt`), Word Chain (`@chain`), Hangman (`@hang`), or Name That (`@guess`) with anyone in your room, right from the chat line.
- **Moderation** — host `@kick`, `@ban`, `@unban`.
- **Message history** — `@history [n]` replays recent in-room messages.
- **Auto-reconnect** — exponential backoff (1s → 30s cap), rejoins your last room.
- **Multi-hop mesh** — flooding with TTL + dedup; chat works over TCP links when there's no WiFi.
- **Trust-on-first-use (TOFU)** — fingerprints verified out-of-band, saved, checked on every reconnect; a changed key aborts the connection.
- **Single-file distribution** — one runnable shaded jar; copy it to other machines.

## Quick start

Prerequisites: **JDK 26** and **Maven 3.9+** (or just open it in IntelliJ IDEA).

```bash
mvn clean package
```

This produces `target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar` — a single,
runnable jar with everything bundled.

### Chat on the same WiFi/LAN

**Host (Alice):**
```bash
java -jar target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar
```
Enter your name, choose **H** (host). It listens on TCP port **8080**.

**Peer (Bob):**
```bash
java -jar target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar
```
Enter your name, choose **J** (join), type Alice's IP (e.g. `192.168.1.42`).

**Both:** on first contact each side displays a fingerprint
(e.g. `74EF3635-02D500FE`) — compare it out-of-band, then type `yes`.
Now `@join general` on both sides and start typing.

### Across the internet (recommended: Tailscale)

Install Tailscale on both machines (`winget install tailscale.tailscale` /
`brew install tailscale` / see [tailscale.com](https://tailscale.com)), run
`tailscale up` on each, then join using the host's Tailscale IP (`tailscale ip -4`).
No port forwarding or firewall holes. Other options: port forward 8080, or a
tunnel like `ngrok tcp 8080`.

### Mesh mode (no WiFi)

```bash
java -jar target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar com.p2p.chat.mesh.MeshMain tcp:<peer-ip>:8081
```

## Commands

| Command | What it does |
|---|---|
| `@join <room>` / `@leave` | create/join or leave a room |
| `@list` / `@users` | list rooms / users in the current room |
| `@history [n]` | show recent messages in the current room |
| `@send <file>` | share a file with the room (progress shown) |
| `@cancel <file>` | abort an incoming file transfer |
| `@link <host> [port]` | bridge rooms with another host |
| `@ttt [move]` | play Tic-Tac-Toe in the room (`start`, `join`, `1 1`…`3 3`, `reset`) |
| `@chain [word]` | play Word Chain (`start`, any word, `score`, `quit`) |
| `@hang [letter]` | play Hangman (`start <word>`, any letter) |
| `@guess [guess]` | play Name That (`start <movie|song|game>`, `hint`, `quit`) |
| `@kick` / `@ban` / `@unban` | host moderation |
| `@help` / `@exit` | help / disconnect |
| `<text>` | send a message to the current room |

## Configuration

Ports, file locations, download folder, and the trust server are all tunable
via command-line flags or a config file (`config.properties`).
See [CONFIGURATION.md](CONFIGURATION.md).

## Tests

A per-feature smoke harness exercises all 22 features end-to-end (crypto
identity, rooms, relay, colors, typing, files + progress + cancel, trust,
history, moderation, auto-reconnect, bridging, mesh flooding, and all four
room games):

```bash
javac -cp target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar -d tool-out tool/FeatureSmokeTest.java
java  -cp "tool-out;target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar" FeatureSmokeTest
```

Exit code 0 = all tests passed.

## What's next (roadmap & ideas)

- Sending files/media over mesh rooms
- Persistent message history across restarts
- Browser web client (removed from the core; revisit only if an encrypted
  end-to-end web session is feasible, e.g. keys fetched over a verified path)
- Mobile/PWA wrapper
- Promote the smoke harness to a proper JUnit suite