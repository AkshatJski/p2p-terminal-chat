<div align="center">

# P2P Terminal Chat

**Peer-to-peer, end-to-end encrypted chat that runs right in your terminal.**

No servers. No accounts. No cloud. Just you and your friends over a direct,
encrypted connection — on your WiFi, across the internet, or even through a
chain of peers with **no internet at all**.

![Java](https://img.shields.io/badge/Java-26-red?logo=openjdk)
![Crypto](https://img.shields.io/badge/E2EE-X25519%20%2B%20AES--256--GCM-blue)
![Build](https://img.shields.io/badge/build-Maven-orange)
![Docker](https://img.shields.io/badge/Docker-ready-lightblue)
![CLI](https://img.shields.io/badge/platform-Terminal-black)

```
┌─────────┐        TCP        ┌─────────┐
│  Alice  │◄══════════════════►│   Bob   │
└─────────┘  ECDH + AES-GCM   └─────────┘
     └── mesh mode: relays hop-by-hop through peers ──► Carol
```

</div>

---

## Table of contents

- [Try it in 60 seconds](#try-it-in-60-seconds)
- [Features](#features)
- [Available downloads](#available-downloads)
- [Prerequisites](#prerequisites)
- [Install & run](#install--run)
- [Your first chat: a step-by-step walkthrough](#your-first-chat-a-step-by-step-walkthrough)
- [Connecting over the internet](#connecting-over-the-internet)
- [Mesh mode (no internet needed)](#mesh-mode-no-internet-needed)
- [Command reference](#command-reference)
- [Security model](#security-model)
- [Configuration](#configuration)
- [Development](#development)
- [Project structure](#project-structure)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [FAQ / troubleshooting](#faq--troubleshooting)

---

## Try it in 60 seconds

1. Download the latest jar from **[GitHub Releases](../../releases/latest)**
   (it's called `p2p-chat.jar`).
2. Run it on two machines on the same network:
   ```bash
   java -jar p2p-chat.jar
   ```
3. On the **first** machine pick `H` (host). On the **second** pick `J` (join)
   and press **Enter** — it scans the network and lists the hosts it finds, so
   you just type a number (no need to know the IP).
4. On both machines type `@join general`, then just start typing.

> Requires **JDK 21+** to run. See [Prerequisites](#prerequisites).

---

## Features

| | |
|---|---|
| 🔍 **Zero-config LAN discovery** | Hosts broadcast their presence over UDP multicast (`239.255.77.7:8082`); joiners press Enter at the prompt to scan and pick a host by number — no IP hunting. Disable with `discovery.enabled=false`. |
| 🔒 **End-to-end encrypted** | X25519 key exchange, AES-256-GCM per message, HKDF-SHA256 key derivation — all from the JDK standard library, zero third-party crypto. |
| 👥 **Group rooms** | A host relays messages to every member. `@join`, `@list`, `@users`. |
| 🌍 **Bridge rooms across networks** | `@link <host> [port]` merges two hosts' rooms into one shared room. |
| 📁 **File sharing** | `@send <path>` broadcasts any file to the room, saved to `./downloads`. Live progress, `@cancel <file>` to abort, and collision-proof filenames (`avatar (1).png`). |
| 🎮 **Terminal games** | Tic-Tac-Toe (`@ttt`), Word Chain (`@chain`), Hangman (`@hang`), Name That (`@guess`) — with **live keyless hints** pulled from iTunes/Apple charts, falling back to offline packs. |
| 🛡️ **Moderation** | Host can `@kick`, `@ban`, `@unban`. |
| 🕘 **Message history** | `@history [n]` replays recent in-room messages. |
| 🔁 **Auto-reconnect** | Exponential backoff (1s → 30s cap), rejoins your last room. |
| 🕸️ **Multi-hop mesh** | Flooding with TTL + dedup. Chat works over TCP links even with no WiFi. |
| 🤝 **Trust on first use (TOFU)** | Fingerprints verified out-of-band and checked on every reconnect; a changed key aborts the connection. |
| 📦 **Single-file distribution** | One runnable shaded jar — copy it to any machine. |

---

## Available downloads

| Way to get it | Best for | Requirements |
|---|---|---|
| [**Prebuilt jar**](../../releases/latest) | Anyone who just wants to chat | JDK 21+ |
| **Docker image** | No Java install wanted | Docker |
| **Build from source** | Contributors, curious devs | JDK 26, git |
| **Setup script** | One-command automation | JDK 26 |

---

## Prerequisites

- **To run the prebuilt jar:** JDK 21 or newer (any vendor).
- **To build from source:** JDK 26 and git.
  - Windows: `winget install EclipseAdoptium.Temurin.26.JDK`
  - macOS: `brew install openjdk@26`
  - Linux: `sudo apt install openjdk-26-jdk`
  - Or from [Adoptium](https://adoptium.net)
- **No Maven needed** — the repo bundles the Maven wrapper (`.mvnw`).

Verify your Java before starting:

```bash
java -version
```

---

## Install & run

Pick **one** of the following paths. They all end with the same chat.

### Option A — Prebuilt jar (fastest)

Download `p2p-chat.jar` from [GitHub Releases](../../releases/latest):

```bash
java -jar p2p-chat.jar
```

### Option B — Docker

```bash
docker build -t p2p-chat .
docker run -it p2p-chat
```

### Option C — Clone and build with the Maven wrapper

```bash
git clone https://github.com/AkshatJski/p2p-terminal-chat.git
cd p2p-terminal-chat
./mvnw package -q -DskipTests          # Windows: mvnw.cmd package -q -DskipTests
java -jar target/java-p2p-terminal-chat-1.1.0.jar
```

### Option D — One-command setup script

```bash
# macOS / Linux
curl -sO https://raw.githubusercontent.com/AkshatJski/p2p-terminal-chat/main/setup.sh
bash setup.sh

# Windows (PowerShell)
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/AkshatJski/p2p-terminal-chat/main/setup.bat" -OutFile setup.bat
.\setup.bat
```

`setup.sh` / `setup.bat` check your Java version, build with the wrapper, and
launch the app. Pass `--build` to build without running.

---

## Your first chat: a step-by-step walkthrough

This example assumes both machines are on the same WiFi/LAN.

**Step 1 — Start the host (Alice's machine)**

```bash
java -jar p2p-chat.jar
```

You'll see:

```
****** P2P Chat (ECDH + AES-GCM) ******
[Security] Your device ID: 74EF3635-02D500FE
Your name: Alice
Do you want to (H)ost a room or (J)oin a host? H
[System] Listening on TCP port 8080
[System] Others can join you at:
  192.168.1.42:8080  (Wi-Fi)
  Invite a peer with:  JOIN 8080 at an address above  (their TOFU check should show a device ID of 74EF3635-02D500FE)
[System] Broadcasting presence on 239.255.77.7:8082 — peers can press Enter at the join prompt to find you.
```

Alice's machine is now listening on port **8080** and announcing itself on the
LAN so Bob can find it without being told the IP.

**Step 2 — Join from the peer (Bob's machine)**

```bash
java -jar p2p-chat.jar
```

Bob just presses **Enter** when asked for a host:

```
Your name: Bob
Do you want to (H)ost a room or (J)oin a host? J
Join a host — type its IP or hostname, or press Enter to scan this network.
Host (or Enter to scan):
[Discovery] Scanning for hosts on 239.255.77.7:8082 ...
  [1] Alice  (74EF3635-02D500FE)  192.168.1.42:8080
1
[System] Joining Alice at 192.168.1.42:8080 ...
```

If scanning finds nothing (firewall, AP isolation, different VLAN), just type
Alice's IP — or `192.168.1.42:9090` if she runs a custom port.

**Step 3 — Verify each other's fingerprint**

On first contact both sides display a fingerprint, e.g.:

```
[Security] Peer fingerprint: A6B5F2AC-8D41C0FF
Trust this peer? (yes/no): ___
```

Compare the fingerprints **out-of-band** (read them out loud, text them, etc.)
and type `yes` on both sides. The fingerprint is now stored and will be
re-verified on every reconnect.

**Step 4 — Chat**

Type `@join general` on both machines. Now any text you type is broadcast to
the room:

```
@join general
Alice: [room general] joined
You: hey bob, welcome!
```

---

## Connecting over the internet

On the local network you just used the host's LAN IP. Over the internet the
host needs a reachable address or tunnel. **Recommended ways:**

### Tailscale (easiest, recommended)

Install Tailscale on both machines and run `tailscale up` on each:

```bash
# Windows
winget install tailscale.tailscale

# macOS
brew install tailscale
```

Then join using the host's Tailscale IP (`tailscale ip -4`), **or** its MagicDNS
name (`<hostname>.<tailnet>.ts.net`) — the app tells you when it detects a
Tailscale interface. No port forwarding, no firewall holes.

> Note: LAN discovery uses multicast, which Tailscale does **not** relay across
> your tailnet. Over Tailscale, join by IP hostname as above instead of
> pressing Enter to scan.

### Port forwarding

Forward TCP port **8080** on your router to the host machine, then Bob joins
with your public IP.

### Tunnels

```bash
ngrok tcp 8080
```

Use the forwarded address ngrok gives you — it forwards straight to the chat
server.

---

## Mesh mode (no internet needed)

When there's no WiFi or internet, stations can still chat by hopping through
intermediate peers over TCP links (for example, over hotspot/Bluetooth-tethered
laptops or long-distance radio links). The mesh floods messages with a TTL.

```bash
java -jar p2p-chat.jar com.p2p.chat.mesh.MeshMain tcp:<peer-ip>:8081
```

Each node can `@join`/`@link` rooms as usual; intermediate nodes transparently
relay messages as long as the TTL allows.

---

## Command reference

| Command | What it does |
|---|---|
| `@join <room>` / `@leave` | Create/join or leave a room |
| `@list` / `@users` | List rooms / users in the current room |
| `@history [n]` | Show the last `n` messages in the current room |
| `@send <file>` | Share a file with the room (progress shown) |
| `@cancel <file>` | Abort an incoming file transfer |
| `@link <host> [port]` | Bridge rooms with another host |
| `@ttt [move]` | Tic-Tac-Toe: `start`, `join`, `1 1`…`3 3`, `reset` |
| `@chain [word]` | Word Chain: `start`, any word, `score`, `quit` |
| `@hang [letter]` | Hangman: `start <word>`, any letter |
| `@guess [guess]` | Name That: `start` <movie\|song\|game>, `hint`, `quit` |
| `@kick` / `@ban` / `@unban` | Host moderation |
| `@help` / `@exit` | Show help / disconnect |
| `<text>` | Send a message to the current room |

---

## Security model

- **Keys** are X25519 (ECDH) keypairs, persisted in `~/.p2p-chat/identity.key`.
- **Every message** is encrypted with AES-256-GCM using a key derived per
  session via HKDF-SHA256.
- **Trust-on-first-use**: you see both fingerprints out-of-band and approve
  them once. After that, each reconnect verifies the stored fingerprint; if it
  ever changes, the connection is aborted (protects against MITM/impersonation).
- **All crypto comes from the JDK** — no third-party cryptographic libraries.

> The transport frame max is 1 MiB; don't use this for huge binary streaming
> yet (files are fine up to that per-frame limit).

---

## Configuration

Everything is tunable via command-line flags or a `config.properties` file.
The app resolves settings with this precedence:

```
command-line arguments  >  config file  >  built-in defaults
```

Common flags:

| Option | Purpose |
|---|---|
| `--port <n>` / `-p` | Chat host/join port (default `8080`) |
| `--mesh-port <n>` / `-m` | Mesh listen port (default `8081`) |
| `--identity <path>` | Identity key file |
| `--trust <path>` | Trusted-fingerprint store |
| `--config <path>` | Config file to load |

Example:

```bash
java -jar p2p-chat.jar --port 9090
```

Full documentation of every key (downloads folder, mesh TTL, reconnect
behavior, hint sources, trust server, …): **[CONFIGURATION.md](CONFIGURATION.md)**.

---

## Development

### Build

```bash
./mvnw clean package          # Windows: mvnw.cmd clean package
```

This produces a single shaded jar at
`target/java-p2p-terminal-chat-1.1.0.jar`.

### Run from source

```bash
./mvnw package -q -DskipTests && java -jar target/java-p2p-terminal-chat-1.1.0.jar
```

### Tests

Unit tests (JUnit 5):

```bash
./mvnw test
```

Full feature smoke harness (covers all 23 features — crypto identity, rooms,
relay, files + progress + cancel, trust, history, moderation, auto-reconnect,
bridging, mesh flooding, all four games, dynamic hints + fallback):

```bash
javac -cp target/java-p2p-terminal-chat-1.1.0.jar -d tool-out tool/FeatureSmokeTest.java
java  -cp "tool-out;target/java-p2p-terminal-chat-1.1.0.jar" FeatureSmokeTest
```

Exit code `0` means everything passed.

---

## Project structure

```
src/main/java/com/p2p/chat/
├── ClientMain.java          # terminal entry point (host / join)
├── core/                    # Node, HostNode, ClientNode, rooms, files, trust, history
├── crypto/                  # Identity, SecureChannel, CryptoUtil, TrustStore
├── protocol/                # wire protocol framing
├── transport/               # socket & loopback transports
├── mesh/                    # MeshMain, MeshNode, MeshLink (multi-hop)
├── game/                    # GameEngine + TicTacToe, WordChain, Hangman, NameThat
│   └── guess/               # HintSource, ItunesHintSource (live keyless hints)
├── config/                  # Config (CLI flags, config file, defaults)
├── util/                    # Ansi terminal helpers
```

---

## Contributing

1. Fork the repo and clone it locally:
   ```bash
   git clone https://github.com/<your-username>/p2p-terminal-chat.git
   cd p2p-terminal-chat
   ```
2. Create a branch:
   ```bash
   git checkout -b feature/my-awesome-thing
   ```
3. Make your changes, then verify with the smoke harness and `./mvnw test`.
4. Push and open a pull request:
   ```bash
   git add .
   git commit -m "feat: my awesome thing"
   git push origin feature/my-awesome-thing
   ```
5. To keep in sync with this repo, add it as an upstream:
   ```bash
   git remote add upstream https://github.com/AkshatJski/p2p-terminal-chat.git
   ```

Ideas to work on are listed in [Roadmap](#roadmap). If you find a bug, open an
issue with the steps to reproduce and the terminal output (minus secrets).

---

## Roadmap

- Sending files/media over mesh rooms
- Persistent message history across restarts
- Browser web client (only if an encrypted end-to-end web session is feasible)
- Mobile/PWA wrapper
- Promote the smoke harness to a proper JUnit suite

---

## FAQ / troubleshooting

**"Invalid choice. Run again and pick H or J."**
You typed something other than `H` or `J` at the connection prompt — relaunch
and type exactly `H` or `J`.

**The peer can't connect to my host.**
First, check both machines can ping each other. Then verify you're on the same
LAN/subnet. On Windows, allow Java through the firewall for private networks.
Over the internet, use Tailscale or port forwarding (see
[above](#connecting-over-the-internet)).

**Pressing Enter to scan finds no hosts.**
Multicast isn't forwarded between VLANs, by access-point client isolation, or
by some firewalls. Join by typing the host's IP instead (it still works), or
on Windows allow Java through the firewall for your network type. Both sides
must use the same `discovery.port` (default `8082`) and the same `port` for the
chat itself.

**The fingerprint changed and the app won't connect.**
The host's identity key or `trusted.txt` was replaced/deleted. Confirm with the
peer out-of-band before clearing the stored trust file
(`~/.p2p-chat/trusted.txt`) or `--trust` path.

**Where are received files saved?**
`./downloads` in the working directory (configurable via `download.dir`).

**Do I need Maven installed?**
No — the Maven wrapper (`mvnw` / `mvnw.cmd`) downloads Maven for you.

**Why does GitHub Releases say "Tag or commit does not exist on server"?**
You're looking at the repo before the tag pushed — refresh after a minute or
use the direct link to the latest release tag above.