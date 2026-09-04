# P2P Terminal Chat - Configuration Guide

Everything the app lets you tune is centralized in one class,
`com.p2p.chat.config.Config`, and resolved with the precedence:

```
command-line arguments  >  config file  >  built-in defaults
```

There are two ways to change a setting:

1. **Command line** (wins over the config file) - pass an option when launching.
2. **Config file** - a simple `key=value` properties file; the shipped sample is
   `config.properties` in the project root, every line commented.

## 1. Command-line options

Works on both entry points (`ClientMain` / the packaged jar, and `MeshMain`).

| Option              | Sets          | Example                                   |
|---------------------|---------------|-------------------------------------------|
| `--config <path>`   | which config file to load | `java -jar app.jar --config my.conf` |
| `--port <n>` / `-p` | chat host/join port | `java -jar app.jar --port 9090` |
| `--mesh-port <n>` / `-m` | mesh Listen port | `... mesh.MeshMain --mesh-port 9091` |
| `--identity <path>` | identity key file | `java -jar app.jar --identity ./my.key` |
| `--trust <path>`    | trusted-fingerprint store | `java -jar app.jar --trust ./trusted.txt` |

`MeshMain` treats anything that is not an option as a peer link spec, so you
can mix them:

```
java -cp app.jar com.p2p.chat.mesh.MeshMain --port 9091 tcp:1.2.3.4:9091
```

## 2. Config file

The app loads the config file in this order (first match wins):

1. the file passed with `--config <path>`,
2. `./config.properties` (working directory / next to the jar),
3. `~/.p2p-chat/config.properties` (per-user default location).

If none exists, defaults are used and the app prints
`[Config] No config file found; using defaults`.

## 3. All options

| Key                  | Default                              | Meaning |
|----------------------|--------------------------------------|---------|
| `port`               | `8080`                               | TCP port the chat **Host** listens on and **Join** connects to |
| `mesh.port`          | `8081`                               | TCP port the mesh **Listen** mode binds to |
| `identity.file`      | `${user.home}/.p2p-chat/identity.key` | Persistent X25519 identity keypair |
| `trust.file`         | `${user.home}/.p2p-chat/trusted.txt` | TOFU store of verified peer fingerprints |
| `download.dir`       | `downloads` (working dir)            | Folder where files received in a room are saved |
| `frame.max.size`     | `1048576` (1 MiB)                    | Max accepted encrypted frame size in bytes |
| `handshake.timeout.ms` | `10000`                            | Crypto handshake timeout in ms |
| `mesh.ttl`           | `8`                                  | Mesh flooding time-to-live (max hops) |
| `reconnect.enabled`  | `true`                               | Auto-reconnect on connection loss |
| `reconnect.max`      | `12`                                 | Max reconnect attempts before giving up |
| `reconnect.base.ms`  | `1000`                               | Base delay between reconnect attempts (ms) |
| `reconnect.max.ms`   | `30000`                              | Maximum delay cap for exponential backoff (ms) |
| `trust.server.enabled` | `true`                             | Enable HTTP trust verification server |
| `trust.server.port`  | `0` (auto)                           | Port for the HTTP trust server (0 = pick free port) |

Path values may use `${user.home}` for a home-relative path. Range-checks
(e.g. port 1-65535) fall back to the default with a warning.

## 4. Examples

Change the chat port for one run (no config file needed):

```
java -jar app.jar --port 9090
```

Pin the mesh port and dial a peer in one command:

```
java -cp app.jar com.p2p.chat.mesh.MeshMain --mesh-port 9091 tcp:10.0.0.5:9091
```

Make it permanent with a config file in the working directory:

```properties
port=9090
mesh.port=9091
mesh.ttl=16
```

Both sides must use the same port: if the host runs `--port 9090`, every
client must join port 9090 too.
