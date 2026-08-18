# P2P Terminal Chat — Project Summary & Technical Guide

A peer-to-peer, end-to-end-encrypted chat that runs in your **terminal**. Two (or
more) people connect directly to each other — no server, no accounts, no
cloud. It works over WiFi/LAN, across the internet (with port forwarding or a
tunnel), and even over **Bluetooth / serial links** when no network is
available.

```
┌─────────┐   TCP or BT RFCOMM   ┌─────────┐
│  Alice  │◄════════════════════►│   Bob   │
└─────────┘  ECDH + AES-GCM      └─────────┘
     │                                   
     └── mesh mode: relays hop-by-hop through peers ──► Carol
```

This document explains *everything*: how to use it, how it works, the
cryptography, the protocols, the tools and libraries, and what to build next.

---

## Table of contents

1. [Quick start — how to use it now](#1-quick-start--how-to-use-it-now)
2. [Current capabilities](#2-current-capabilities)
3. [The big picture — architecture](#3-the-big-picture--architecture)
4. [Cryptography: ECDH, AES-GCM, HKDF](#4-cryptography-ecdh-aes-gcm-hkdf)
5. [The encrypted handshake](#5-the-encrypted-handshake)
6. [Message framing](#6-message-framing)
7. [The application protocol (rooms)](#7-the-application-protocol-rooms)
8. [The mesh protocol (no-network mode)](#8-the-mesh-protocol-no-network-mode)
9. [Trust & fingerprint verification (TOFU)](#9-trust--fingerprint-verification-tofu)
10. [Transports: TCP, serial/Bluetooth, loopback](#10-transports-tcp-serialbluetooth-loopback)
11. [Tools & libraries used](#11-tools--libraries-used)
12. [Security model — what is and isn't protected](#12-security-model--what-is-and-isnt-protected)
13. [Project layout](#13-project-layout)
14. [Known issues & cleanup](#14-known-issues--cleanup)
15. [Roadmap — what's next](#15-roadmap--whats-next)

---

## 1. Quick start — how to use it now

> All tunables (ports, file locations, timeouts, mesh TTL) are configurable via
> command-line flags or a config file — see [`CONFIGURATION.md`](CONFIGURATION.md).
> The chat port defaults to `8080`, the mesh port to `8081`.

### Prerequisites
- **JDK 26** (the build targets Java 26 — check with `java -version`)
- **Maven 3.9+** (`mvn -version`), or just open the project in IntelliJ IDEA
  (IDEA bundles Maven)

If you only have an older JDK, lower `<maven.compiler.source/target>` in
`pom.xml` to e.g. `21` — the code itself doesn't need 26-specific features.

### Build
```bash
mvn clean package
```
This produces `target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar` — a single,
**standalone, runnable jar** (all dependencies bundled, main class set). Copy
this one file to other machines; they only need a JDK to run it.

### Scenario A — chat with a friend on the same Wi-Fi/LAN
**On the host's machine (Alice):**
```bash
java -jar target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar
```
- Enter your name.
- Choose `H` (host). The app listens on TCP port **8080**.
- Note: allow port 8080 through the firewall (Windows will usually ask).

**On the other machine (Bob):**
```bash
java -jar target/java-p2p-terminal-chat-1.0-SNAPSHOT.jar
```
- Enter your name, choose `J` (join), type Alice's IP (e.g. `192.168.1.42`).
  Find it with `ipconfig` (Windows) or `ip addr` (Linux).

**Both of you:**
- On **first contact** each side shows a **fingerprint** like
  `74EF3635-02D500FE`. Compare it out-of-band (call, show screen, QR). Type
  `yes` to trust. It's saved and verified automatically afterwards.
- Type `@join general` on both sides to meet in a room.
- Just type messages — they appear as `[Room general] name: message`.
- `@send <file>` shares a document with everyone in the room (saved to
  `./downloads`). `@list`, `@users`, `@leave`, `@help`, `@exit` do what they say.

### Scenario A2 — many people, same room, on different networks
Each network runs its own host, then the hosts are **bridged** so the same room
name becomes one shared room:
```bash
# Network 1 (Alice, host on her LAN)         Network 2 (Bob, host on his LAN)
java -jar app.jar                            java -jar app.jar
> H > @join general                          > H > @join general
> @link <Bob's reachable address>            # (Alice dials Bob's host)
> yes                                        # compare fingerprint, trust
```
Now a message Alice sends in `general` appears to Bob's members as
`alice@A6B5F2AC: ...` (host label = host's fingerprint). Any client on either
LAN can join their local host and reach the whole bridged room. Links can chain
(A↔B↔C) and floods can't loop. To reach the other host across the internet,
give Alice a reachable address via Scenario B.

### Scenario B — meet across the internet
Any one of these gives you a reachable address for the host:
1. **Tailscale / ZeroTier** (easiest) — both devices join a virtual LAN, host
   gives you its Tailscale IP, join with that IP.
2. **Port forwarding** — forward TCP 8080 on the host's router to the host
   machine, join using the host's public IP.
3. **Tunnel** — run `ngrok tcp 8080` (or playit.gg) on the host and join using
   the address it prints.

### Scenario C — mesh mode (works without WiFi / over Bluetooth)
The mesh connects any number of nodes and routes messages hop by hop, over TCP
**or** serial (Bluetooth RFCOMM):
```bash
# Node 1 — listen + dial Node 2
java -jar app.jar com.p2p.chat.mesh.MeshMain tcp:192.168.1.20:8081

# Node 2 — listen + dial Node 1
java -jar app.jar com.p2p.chat.mesh.MeshMain tcp:192.168.1.10:8081

# Bluetooth/serial instead of TCP (after OS-level RFCOMM setup):
java -jar app.jar com.p2p.chat.mesh.MeshMain serial:/dev/rfcomm0
```
Use `@join <room>` to chat, `@links` to see neighbors. If Alice cannot reach
Carol directly, a message still arrives via Bob (multi-hop).

---

## 2. Current capabilities

| Capability | How |
|---|---|
| One-to-one chat | Host + Join, or two mesh nodes |
| Group rooms (multi-user) | Host relays `@MSG` to everyone in the room; rooms live in memory on the host |
| Room admin | `@list`, `@users`, `@join`, `@leave` |
| Rooms across networks | `@link <host> [port]` bridges two hosts — same room name = one shared room |
| File & document sharing | `@send <file>` in a room; everyone in the room (and linked hosts) receives it into `./downloads` |
| End-to-end encryption | ECDH (X25519) + AES-256-GCM per connection |
| Forward secrecy | fresh ephemeral ECDH keys on every session |
| Man-in-the-middle detection | fingerprint + trust-on-first-use, aborts on mismatch |
| Offline / no-WiFi | mesh over Bluetooth RFCOMM (serial) or LAN |
| Multi-hop routing | flooding mesh with TTL + dedup |
| Single-file distribution | runnable shaded jar |

**What it does *not* yet do:** a mobile app, automatic NAT traversal /
rendezvous server, message history/persistence, group mesh auth, or files over
the mesh (mesh rooms are "current room per node"; host rooms are centralized
but bridgeable).

---

## 3. The big picture — architecture

The design has three layers, kept strictly separate:

```
┌───────────────────────────────────────────────────────────────┐
│  Application layer   core/  protocol/                         │
│  rooms, usernames, relay, CLI, mesh routing                   │
├───────────────────────────────────────────────────────────────┤
│  Security layer      crypto/                                  │
│  ECDH handshake, AES-GCM framing, fingerprints, trust store   │
├───────────────────────────────────────────────────────────────┤
│  Transport layer     transport/                               │
│  TCP socket · serial/Bluetooth · loopback (tests)             │
└───────────────────────────────────────────────────────────────┘
```

- **`crypto/SecureChannel`** owns a `Transport`, performs the handshake, then
  exposes `send(String)` / `receive()`. It does not know about rooms or chat.
- **`core/HostNode` / `core/ClientNode`** use a `SecureChannel` and speak the
  application protocol. The host *is* a relay; clients only ever talk to their
  host.
- **`mesh/MeshNode`** holds *many* `SecureChannel`s (one per neighbor) and
  routes messages between them.
- Because security sits **above** transport, swapping TCP for Bluetooth changes
  nothing else.

Key classes:

| Class | Responsibility |
|---|---|
| `ClientMain` | CLI entry: name → Host/Join → REPL |
| `HostNode` | `ServerSocket`, username registry, room table, relay, host-to-host bridging |
| `ClientNode` | dial one host, track current room, render messages, receive files |
| `Participant` | a room member (remote = channel, host console = prints) |
| `FileReceiver` | saves `@FILE_*` transfers into the download folder |
| `Node` | shared base: reader thread, send, close |
| `SecureChannel` | handshake + encrypted framed IO over any `Transport` |
| `CryptoUtil` | X25519, HKDF, AES-GCM, fingerprint |
| `Identity` | persistent keypair at `~/.p2p-chat/identity.key` |
| `Config` | every tunable (ports, paths, timeouts, download dir) |
| `TrustStore` / `TrustGate` | TOFU fingerprint verification |
| `Protocol` | command constants, field separator, display helpers |
| `MeshNode` / `MeshMain` | flooding mesh + its CLI |
| `Transport` + impls | socket / serial / loopback |

---

## 4. Cryptography: ECDH, AES-GCM, HKDF

All cryptography uses the JDK's built-in `java.security` / `javax.crypto` — no
third-party crypto libraries.

### X25519 (ECDH) — the key exchange
Elliptic-Curve Diffie-Hellman on Curve25519. Both sides pick a private scalar
`a` / `b` (random 32 bytes) and publish a public point `A = a·G`, `B = b·G`.
Both can compute the **same** shared secret:

```
a·B = a·b·G = b·a·G = b·A
```

An eavesdropper sees only `A` and `B`; recovering `a` (the discrete-log
problem) is considered computationally infeasible. X25519 is used instead of
classic (finite-field) DH because it's faster and its implementations are
harder to misuse (no weak small-order pitfalls). Public keys are the 32-byte
u-coordinate — we transmit them base64url-encoded, no X.509 wrapper.

### HKDF-SHA256 — turning the shared secret into a key
The raw DH output is a single 32-byte secret. We run it through **HKDF** (HMAC
Key Derivation Function):

1. *Extract:* `PRK = HMAC-SHA256(salt=0, IKM=sharedSecret)` — concentrates
   entropy.
2. *Expand:* repeatedly `HMAC-SHA256(PRK, info ‖ counter)` to produce the
   requested length.

This gives a clean 256-bit AES key with `info="p2p-chat-v1"` as domain
separation.

### AES-256-GCM — message encryption
Once both sides share a key, every message is encrypted with
**AES in GCM mode** (Galois/Counter Mode):

- Authenticated encryption: a 128-bit **tag** over the ciphertext proves the
  message wasn't tampered with and came from the holder of the key.
- A fresh random 12-byte **nonce** is generated per message (GCM is unsafe if a
  nonce repeats under the same key).
- Output layout: `[12-byte nonce][ciphertext + 16-byte tag]`.

A wrong key or any bit-flip fails decryption (throws `AEADBadTagException`).

> **Why this suite?** X25519 + AES-GCM + HKDF-SHA256 is the same modern stack
> used by TLS 1.3 and Signal-style protocols — strong defaults, no legacy
> primitives.

---

## 5. The encrypted handshake

The handshake is **symmetric** — the exact same code runs on both ends, so
there is no "client" or "server" role to get wrong. It runs in plaintext
(line-based) before switching to encrypted frames.

```
     A                                        B
     ─                                        ─
 1.  K:<ephemeral pubkey>  ────────────────►  K:<ephemeral pubkey>
     I:<identity pubkey>   ────────────────►  I:<identity pubkey>
 2.  ◄────────────────────  K:<ephemeral pubkey>
     ◄────────────────────  I:<identity pubkey>
 3.  session key = HKDF( ECDH(ephA, ephB) )
     fingerprint   = SHA256(sorted(idA, idB))
 4.  C:<enc(myToken)>      ────────────────►
     ◄────────────────────  C:<enc(peerToken)>
 5.  R:<enc(peerToken)>    ────────────────►  (echo the challenge)
     ◄────────────────────  R:<...> == myToken?  → keys verified
```

- **`K:`** — a *fresh ephemeral* X25519 key per connection. Because it's never
  reused, a compromised old key can't decrypt old traffic (**forward secrecy**).
- **`I:`** — the *persistent identity* key. It's the basis of the fingerprint,
  so the code is stable across reconnects (required for TOFU).
- **Challenge/response (`C:`/`R:`)** — each side encrypts a random 16-byte token
  with the derived key and requires the peer to echo it back. If either side
  derived a different key (e.g. a bit-flip or an attacker), verification fails
  *before* any real data is sent.
- **Anti-deadlock rule:** every side *writes* its lines before it *reads*,
  so the protocol can't interlock.
- A 10-second read timeout protects against a peer that never speaks.

---

## 6. Message framing

After the handshake, the channel switches to length-prefixed encrypted frames:

```
┌──────────────┬────────────┬─────────────────────────────┐
│ 4-byte length │ 12-byte     │ AES-256-GCM ciphertext      │
│ (big-endian)  │ nonce       │ + 16-byte auth tag          │
└──────────────┴────────────┴─────────────────────────────┘
```

- The 4-byte big-endian length tells the receiver exactly how many bytes to
  read next (`readFully`).
- Frames are capped at 1 MiB to prevent memory-exhaustion attacks.
- The payload is the UTF-8 encoding of a protocol string (see next section).

---

## 7. The application protocol (rooms)

Inside the encrypted channel, everything is a UTF-8 string of the form
`TYPE␀arg␀arg␀…` where `␀` (`U+0000`) is the field separator and the *last*
field is never split — so chat text may contain anything.

| Direction | Command | Meaning |
|---|---|---|
| Client → Host | `@NAME␀alice` | announce username (must be first) |
| Client → Host | `@JOIN␀general` | create or join a room |
| Client → Host | `@LEAVE` | leave the current room |
| Client → Host | `@LIST` | list rooms |
| Client → Host | `@USERS` | list users in current room |
| Client → Host | `@MSG␀general␀hi!` | send to a room |
| Client → Host | `@FILE_START␀<id>␀general␀report.pdf␀<bytes>␀<chunks>␀bob␀<host>` | announce a file |
| Client → Host | `@FILE_CHUNK␀<id>␀general␀<n>␀<base64>` | one file chunk |
| Client → Host | `@FILE_ABORT␀<id>␀general␀<reason>` | cancel a transfer |
| Host → Client | `@FROM␀general␀bob␀<host>␀hi!` | a room message (`host` empty for local senders) |
| Host → Client | `@ROOMS␀general(2),lounge(1)` | room list with sizes |
| Host → Client | `@ROOM_USERS␀general␀alice,bob` | user list |
| Host → Client | `@ERR␀…` | error |
| Host → Client | `@SYS␀…` | system notice |
| Host ↔ Host | `@BRIDGE␀general,lounge` | link handshake: rooms I host |
| Host ↔ Host | `@BRIDGE_MSG␀<rid>␀<inner line>` | relayed message with loop-prevention id |

The **host is the relay**: when it receives `@MSG` it re-broadcasts `@FROM` to
every other member of that room. Rooms are held in memory as
`Map<roomName, List<Participant>>` on the host. The host's own console is a
`Participant` whose "channel" is the terminal, so relaying to the operator uses
the same code path as relaying to a peer.

**Files** are split into 48 KB chunks, base64-encoded into `@FILE_CHUNK` frames
(well under the 1 MiB frame cap) and relayed exactly like messages; the
receiving side assembles them in `./downloads`. `@FILE_ABORT` cancels and
deletes the partial file.

**Host-to-host bridging** (`@link <host> [port]`) makes the same room name
span networks. Two linked hosts exchange `@BRIDGE` room lists; a message sent
in a room on one host is wrapped as `@BRIDGE_MSG␀rid␀…` and sent to every
linked host that hosts that room. Every hop forwards the same rid, and a
per-host seen-set drops duplicates, so chains of hosts cannot loop. Senders on
remote hosts are rendered as `user@hostLabel` (the first 8 hex digits of the
host's identity fingerprint).

---

## 8. The mesh protocol (no-network mode)

A mesh is the opposite of the star topology: every node holds several links and
messages travel **hop by hop**. This is what keeps chat working when there is
no WiFi — via Bluetooth/serial or short-range links.

Wire format (encrypted per link):
```
MESH␀<messageId>␀<ttl>␀<originUser>␀<room>␀<text>
```

Routing is **flooding with two safeguards**:

1. **Message ID dedup** — a node that has already seen a `messageId` drops the
   duplicate, so a packet can't loop forever.
2. **TTL (time-to-live)** — each forward decrements the counter; at 0 it stops,
   bounding the blast radius to ~8 hops.

On receive, a node:
```
if seen(msgId): drop
mark seen
if room == my current room: render to screen
if ttl > 1: forward to every neighbor except the sender
```

Rooms are broadcast filters: the whole mesh carries the packet, but only nodes
currently in that room display it; the rest forward it silently.

---

## 9. Trust & fingerprint verification (TOFU)

ECDH alone is vulnerable to a **man-in-the-middle** (MITM): an attacker between
Alice and Bob can do DH with each of them separately and read everything. The
defence is verifying *who* you're key-exchanging with.

- **Fingerprint** — `SHA-256` of the two identity public keys, sorted
  canonically, first 8 bytes rendered as `XXXXXXXX-XXXXXXXX`. Because it mixes
  *both* keys, **both sides compute the same code** — verified in tests.
- **Trust-on-first-use (TOFU)** — the first time you connect to an endpoint,
  the app shows its fingerprint and asks you to confirm. It's saved in
  `~/.p2p-chat/trusted.txt` as `endpoint fingerprint`.
- On every later connection the fingerprint is compared:
  - matches → "verified", connect;
  - unknown → prompt to trust;
  - **changed → connection aborted** (suspected MITM).

This is the same model SSH uses. The fingerprint binds the *connection*; users
confirm it out-of-band (call, QR, screen).

---

## 10. Transports: TCP, serial/Bluetooth, loopback

`Transport` is the single seam between the network and the security layer:

```java
public interface Transport extends Closeable {
    InputStream input();
    OutputStream output();
    String id();                 // used as the trust-store key
    default void setReadTimeout(int ms) {}
}
```

| Implementation | Use |
|---|---|
| `SocketTransport` | TCP/IP — LAN and WAN |
| `SerialTransport` | Bluetooth RFCOMM exposed as a COM/serial port (via **jSerialComm**) |
| `LoopbackTransport` | in-memory chunk pipes for tests / single-machine demos |

`SecureChannel` takes any `Transport`, so a Bluetooth RFCOMM link gets the exact
same ECDH + AES-GCM protection as a TCP one. For Bluetooth classic, the OS
exposes the radio as a serial port:
- Linux: `sudo rfcomm listen /dev/rfcomm0 1` (server) or
  `sudo rfcomm connect /dev/rfcomm0 <MAC> 1` (client), then open the device.
- Windows: pair the device and use its Bluetooth COM port.

---

## 11. Tools & libraries used

| Tool / library | Version | Purpose |
|---|---|---|
| **Java (JDK)** | 26 | language + all crypto (`java.security`, `javax.crypto`) |
| **Maven** | 3.9+ | build & dependency management |
| **maven-shade-plugin** | 3.6.0 | bundle deps + set `Main-Class` → one runnable jar |
| **jSerialComm** | 2.11.0 | cross-platform serial ports (Bluetooth RFCOMM) |
| **Git** | — | version control |

Everything cryptographic is from the JDK standard library — the only external
dependency in the whole project is `jSerialComm` for the serial/Bluetooth
transport.

---

## 12. Security model — what is and isn't protected

**Protected**
- Confidentiality & integrity of all traffic (AES-256-GCM).
- Forward secrecy (ephemeral ECDH per session).
- MITM detection on repeat connections (TOFU fingerprint check).
- Tamper-evident frames (GCM auth tag).

**Not protected (by design, for now)**
- **First-contact MITM** — TOFU can only detect a MITM that happens *after*
  you've verified once. A determined attacker present at the very first
  connection is indistinguishable. (Mitigation: verify the fingerprint
  out-of-band.)
- **Identity theft** — usernames are not cryptographically bound; anyone can
  claim `@NAME alice`. The fingerprint identifies the *connection*, not the
  username.
- **Anonymity** — IPs are visible to your peers (and a TURN/relay would see
  them too).
- **Metadata** — message sizes and timing leak; the protocol does not pad.
- **Denial of service** — no rate limiting or auth on the host accept path.
- **Trust store keys by endpoint** — for inbound host connections the key is
  the peer's IP; changing IPs mean a new (prompted) trust record.

---

## 13. Project layout

```
pom.xml                      build: Java 26, shade, jSerialComm
lib/jserialcomm-2.11.0.jar   vendored for local dev (Maven fetches it too)
config.properties            commented sample config (auto-loaded if present)
CONFIGURATION.md             every option + how to change it
src/main/java/com/p2p/chat/
├── ClientMain.java          entry point (host/join)
├── config/                  configuration
│   └── Config.java          CLI args > config file > defaults
├── crypto/                  security layer
│   ├── CryptoUtil.java      X25519 · HKDF · AES-GCM · fingerprint
│   ├── SecureChannel.java   handshake + encrypted frames
│   ├── Identity.java        persistent keypair (~/.p2p-chat/identity.key)
│   └── TrustStore.java      TOFU store (~/.p2p-chat/trusted.txt)
├── core/                    application layer
│   ├── Node.java            shared reader-thread base
│   ├── HostNode.java        rooms, relay, @link bridging, username registry
│   ├── ClientNode.java      joins a host, chat UI, @send files
│   ├── Participant.java     one room member
│   ├── FileReceiver.java    saves @FILE_* transfers into ./downloads
│   ├── TrustGate.java       verification prompts
│   └── Prompt.java          yes/no interaction
├── protocol/
│   └── Protocol.java        command constants + helpers
├── mesh/
│   ├── MeshNode.java        flooding mesh
│   ├── MeshMain.java        mesh CLI
│   └── MeshLink.java        one encrypted hop
└── transport/
    ├── Transport.java       the transport seam
    ├── SocketTransport.java TCP
    ├── SerialTransport.java Bluetooth/serial
    └── LoopbackTransport.java in-memory (tests)
```

---

## 14. Known issues & cleanup

- **Stale `.class` files in git** — the initial commit accidentally committed
  compiled classes under `src/main/java` (`ClientMain.class`,
  `ConnectionManager.class`, `NetworkListener.class`). `ConnectionManager` and
  `NetworkListener` were removed and replaced; the leftover binaries should be
  `git rm --cached`'d and gitignored.
- **Host-rooms are centralized** — the star/room model and the mesh model are
  separate modes; they are not yet merged (hosts bridge *each other*, but mesh
  nodes don't talk to hosts).
- **No tests committed** — the end-to-end checks (fingerprint symmetry,
  mesh hop relay, trust-mismatch rejection, host bridging, file transfer) were
  run as throwaway smoke tests; consider promoting them to a proper JUnit suite.
- **Partial downloads** — if a sender disconnects mid-transfer, the `.part`
  file stays in the download folder; there is no resume or timeout yet.

---

## 15. Roadmap — what's next

1. **Rendezvous / signaling server** — removes the "type the IP and open ports"
   barrier for internet chat (STUN/TURN-style NAT traversal or a simple broker);
   pairs naturally with `@link` bridging.
2. **QR-code join** — encode the host address + fingerprint as a QR so trust
   verification and connecting become one scan.
3. **Mobile companion app (or WebSocket web client)** — messaging happens on
   phones; the `Transport` + protocol layers port almost unchanged.
4. **Files over the mesh, message history/persistence, and per-user
   cryptographic identity** so usernames can't be spoofed.
