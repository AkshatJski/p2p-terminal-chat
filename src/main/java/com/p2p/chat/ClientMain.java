package com.p2p.chat;

import com.p2p.chat.config.Config;
import com.p2p.chat.core.ClientNode;
import com.p2p.chat.core.HostNode;
import com.p2p.chat.core.Node;
import com.p2p.chat.core.Prompt;
import com.p2p.chat.core.TrustGate;
import com.p2p.chat.crypto.CryptoUtil;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.TrustStore;
import com.p2p.chat.discovery.Discovery;
import com.p2p.chat.discovery.DiscoveryAnnouncer;
import com.p2p.chat.discovery.DiscoveryRecord;
import com.p2p.chat.discovery.DiscoveryScanner;
import com.p2p.chat.discovery.JoinPicker;
import com.p2p.chat.discovery.TailscaleStatus;
import com.p2p.chat.util.Ansi;
import java.net.ConnectException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Terminal entry point. Ask for a name, choose to host or join, then drive the
 * chat with simple commands.
 *
 * <p>Usage: {@code java -jar app.jar [--port N] [--config &lt;file&gt;]
 * [--identity &lt;file&gt;] [--trust &lt;file&gt;]}. See {@code CONFIGURATION.md}
 * for all options and the defaults.
 */
public class ClientMain {
    public static void main(String[] args) {
        Ansi.detectAndEnable();
        Scanner in = new Scanner(System.in);
        Node node = null;
        try {
            Config config = Config.load(args);
            System.out.println(Ansi.color(Ansi.DIM, "[Config] Using " + config.getConfigFile()
                    + " | chat port " + config.getPort()));
            Identity identity = Identity.loadOrCreate(config.getIdentityFile());
            TrustGate trustGate = new TrustGate(new TrustStore(config.getTrustFile()));
            Prompt prompt = message -> {
                System.out.print(Ansi.color(Ansi.YELLOW, message));
                String answer = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "no";
                return answer.equals("y") || answer.equals("yes");
            };

            String deviceId = CryptoUtil.fingerprint(identity.rawPublicKey(), identity.rawPublicKey());
            System.out.println(Ansi.bold(Ansi.BRIGHT_CYAN, "****** P2P Chat (ECDH + AES-GCM) ******"));
            System.out.println(Ansi.color(Ansi.CYAN, "[Security] Your device ID: " + deviceId));
            System.out.print("Your name: ");
            String name = in.nextLine().trim();
            if (name.isEmpty()) {
                name = "anon";
            }

            System.out.println("Do you want to (H)ost a room or (J)oin a host?");
            String choice = in.nextLine().trim().toUpperCase();
            if (choice.startsWith("H")) {
                node = new HostNode(name, config.getPort(), identity, trustGate, prompt);
                node.start();
                printLocalAddresses(config, deviceId);
                startHostDiscovery(config, name, deviceId);
            } else if (choice.startsWith("J")) {
                String[] target = askJoinTarget(in, config);
                if (target == null) {
                    return;
                }
                node = new ClientNode(name, target[0], Integer.parseInt(target[1]), identity, trustGate, prompt);
                node.start();
            } else {
                System.out.println(Ansi.color(Ansi.RED, "[System] Invalid choice. Run again and pick H or J."));
                return;
            }

            System.out.println(Ansi.color(Ansi.DIM, "[System] Type @help for the command list. Say '@exit' to quit."));
            while (node.isRunning()) {
                System.out.print("> ");
                if (!in.hasNextLine()) {
                    break;
                }
                String line = in.nextLine();
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("@exit")) {
                    node.close();
                    break;
                }
                if (trimmed.equalsIgnoreCase("@net")) {
                    if (node instanceof HostNode) {
                        printLocalAddresses(config, deviceId);
                    } else {
                        System.out.println(Ansi.color(Ansi.DIM,
                                "[System] @net is a host command — the JOIN lines (LAN IP and MagicDNS) are shown by the room host, not the joiner."));
                    }
                    continue;
                }
                node.handleUserInput(line);
            }
        } catch (ConnectException e) {
            System.err.println(Ansi.color(Ansi.RED, "[Error] Could not connect — is the host running and reachable?"));
            System.err.println(Ansi.color(Ansi.DIM, "       " + e.getMessage()));
        } catch (BindException e) {
            System.err.println(Ansi.color(Ansi.RED, "[Error] Port already in use — try a different port with --port <n>"));
        } catch (Exception e) {
            System.err.println(Ansi.color(Ansi.RED, "[Error] " + e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "unknown error")));
        } finally {
            if (node != null) {
                node.close();
            }
            System.out.println(Ansi.color(Ansi.DIM, "[System] Chat closed."));
            in.close();
        }
    }

    private static void printLocalAddresses(Config config, String deviceId) {
        int port = config.getPort();
        try {
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] Others can join you at:"));
            boolean found = false;
            boolean onTailscale = false;
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                if (isTailscaleNetwork(ni)) {
                    onTailscale = true;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String label = ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
                    String ip = addr.getHostAddress();
                    if (addr instanceof java.net.Inet4Address) {
                        String note = isTailscaleAddress(addr)
                                ? " (Tailscale IP — reachable from any tailnet device)" : "";
                        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  JOIN " + ip + ":" + port
                                + note + "  [" + label + "]"));
                    } else {
                        System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  " + ip + ":" + port + "  (" + label + ")"));
                    }
                    found = true;
                }
            }
            if (config.isTailscaleDiscoveryEnabled()) {
                TailscaleStatus.Status st = TailscaleStatus.read(config.getTailscaleBin());
                if (st != null && st.self() != null && !st.self().dnsName().isEmpty()) {
                    System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  JOIN " + st.self().dnsName() + ":" + port
                            + "  (MagicDNS — works from any tailnet device)"));
                    found = true;
                    onTailscale = true;
                }
            }
            if (found) {
                System.out.println(Ansi.color(Ansi.DIM,
                        "  Invite a peer with a JOIN line above; their TOFU check should show a device ID of " + deviceId));
            } else {
                System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  localhost:" + port + "  (local only)"));
            }
            if (!onTailscale) {
                System.out.println(Ansi.color(Ansi.DIM, "[Tailscale] Not on a tailnet? Install Tailscale "
                        + "(https://tailscale.com/download) and the JOIN lines also work across the internet."));
            }
        } catch (SocketException e) {
            System.out.println(Ansi.color(Ansi.DIM, "  (could not enumerate network interfaces)"));
        }
    }

    private static boolean isTailscaleNetwork(NetworkInterface ni) {
        String label = ((ni.getDisplayName() == null ? "" : ni.getDisplayName())
                + "|" + (ni.getName() == null ? "" : ni.getName())).toLowerCase();
        if (label.contains("tailscale")) {
            return true;
        }
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            if (isTailscaleAddress(addrs.nextElement())) {
                return true;
            }
        }
        return false;
    }

    /** True for Tailscale's 100.64.0.0/10 CGNAT range. */
    private static boolean isTailscaleAddress(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b != null && b.length == 4 && (b[0] & 0xFF) == 100
                && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
    }

    private static void startHostDiscovery(Config config, String name, String deviceId) {
        if (!config.isDiscoveryEnabled()) {
            return;
        }
        DiscoveryAnnouncer announcer = new DiscoveryAnnouncer(name, deviceId, config.getPort(),
                config.getDiscoveryPort(), config.getDiscoveryIntervalMs(),
                config.getDiscoveryInterface(),
                msg -> System.out.println(Ansi.color(Ansi.DIM, "[Discovery] " + msg)));
        announcer.start();
        System.out.println(Ansi.color(Ansi.DIM, "[System] Broadcasting presence on " + Discovery.GROUP
                + ":" + config.getDiscoveryPort() + " — peers can press Enter at the join prompt to find you."));
    }

    private static String[] askJoinTarget(Scanner in, Config config) {
        while (true) {
            String tailHint = config.isTailscaleDiscoveryEnabled()
                    ? " (Enter also lists peers from your Tailscale tailnet)" : "";
            System.out.println(Ansi.color(Ansi.DIM,
                    "Join a host — type its IP or hostname, or press Enter to scan this network" + tailHint + "."));
            System.out.print("Host (or Enter to scan): ");
            String input = in.nextLine().trim();
            if (!input.isEmpty()) {
                String[] t = validTarget(input, config.getPort());
                if (t != null) {
                    return t;
                }
                System.out.println(Ansi.color(Ansi.RED, "[Error] Invalid host or port: " + input));
                continue;
            }
            if (!config.isDiscoveryEnabled() && !config.isTailscaleDiscoveryEnabled()) {
                System.out.println(Ansi.color(Ansi.YELLOW,
                        "[Discovery] LAN discovery and Tailscale are disabled — please type the host IP or hostname."));
                continue;
            }

            List<DiscoveryRecord> lan = List.of();
            DiscoveryScanner.ScanResult lanScan = null;
            if (config.isDiscoveryEnabled()) {
                System.out.println(Ansi.color(Ansi.DIM, "[Discovery] Scanning for hosts on " + Discovery.GROUP
                        + ":" + config.getDiscoveryPort() + " ..."));
                lanScan = DiscoveryScanner.scanResult(Discovery.GROUP, config.getDiscoveryPort(),
                        (int) config.getDiscoveryScanMs(), config.getDiscoveryInterface());
                lan = lanScan.hosts();
            }

            boolean tailAvailable = false;
            List<TailscaleStatus.Peer> online = List.of();
            List<TailscaleStatus.Peer> tailPeers = List.of();
            if (config.isTailscaleDiscoveryEnabled()) {
                TailscaleStatus.Status st = TailscaleStatus.read(config.getTailscaleBin());
                tailAvailable = st != null;
                online = st == null ? List.of() : st.peers();
                if (!online.isEmpty()) {
                    System.out.println(Ansi.color(Ansi.DIM, "[Tailscale] " + online.size()
                            + " online tailnet host(s) — checking which run the chat app..."));
                    tailPeers = reachableTailPeers(online, config.getPort());
                }
            }

            List<JoinPicker.Option> options = JoinPicker.options(lan, tailPeers, config.getPort());
            if (options.isEmpty()) {
                printScanDiagnostics(config, lanScan, tailAvailable, online, tailPeers);
                continue;
            }
            for (int i = 0; i < options.size(); i++) {
                System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  [" + (i + 1) + "] " + options.get(i).label()));
            }
            String pick = in.nextLine().trim();
            if (!pick.isEmpty()) {
                try {
                    int idx = Integer.parseInt(pick);
                    if (idx >= 1 && idx <= options.size()) {
                        JoinPicker.Option o = options.get(idx - 1);
                        System.out.println(Ansi.color(Ansi.DIM, "[System] Joining " + o.host() + ":" + o.port() + " ..."));
                        return new String[]{o.host(), String.valueOf(o.port())};
                    }
                } catch (NumberFormatException ignored) {
                }
                String[] ok = validTarget(pick, config.getPort());
                if (ok != null) {
                    return ok;
                }
            }
            System.out.println(Ansi.color(Ansi.RED, "[Error] Invalid choice. Try again."));
        }
    }

    /** Explains an empty scan so the user knows whether to check firewall/multicast or just nothing is up. */
    private static void printScanDiagnostics(Config config, DiscoveryScanner.ScanResult lanScan,
                                             boolean tailAvailable, List<TailscaleStatus.Peer> online,
                                             List<TailscaleStatus.Peer> reachable) {
        if (lanScan != null) {
            if (lanScan.multicastUsed()) {
                System.out.println(Ansi.color(Ansi.YELLOW, "[Discovery] No LAN hosts found on this network."));
            } else {
                System.out.println(Ansi.color(Ansi.YELLOW,
                        "[Discovery] No multicast route detected (AP isolation, firewall, or no compatible interface) "
                                + "— LAN hosts aren't visible. Type an IP manually or join over Tailscale."));
            }
        }
        if (config.isTailscaleDiscoveryEnabled()) {
            if (!tailAvailable) {
                System.out.println(Ansi.color(Ansi.DIM,
                        "[Tailscale] CLI not found/running — no tailnet peers listed. Start Tailscale and press Enter again."));
            } else if (online.isEmpty()) {
                System.out.println(Ansi.color(Ansi.DIM, "[Tailscale] No online hosts on your tailnet right now."));
            } else if (reachable.isEmpty()) {
                System.out.println(Ansi.color(Ansi.RED, "[Tailscale] " + online.size()
                        + " online host(s) found, but none answered on the chat port — maybe their firewall blocks it."));
            }
        }
    }

    /** Probes tailnet peers on the chat port (1s, in parallel) so the picker only shows live hosts. */
    private static List<TailscaleStatus.Peer> reachableTailPeers(List<TailscaleStatus.Peer> peers, int chatPort) {
        List<TailscaleStatus.Peer> alive = new ArrayList<>();
        if (peers.isEmpty()) {
            return alive;
        }
        List<java.util.concurrent.Future<TailscaleStatus.Peer>> futures;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = new ArrayList<>();
            for (TailscaleStatus.Peer p : peers) {
                futures.add(exec.submit(() -> reachable(p, chatPort) ? p : null));
            }
            for (java.util.concurrent.Future<TailscaleStatus.Peer> f : futures) {
                try {
                    TailscaleStatus.Peer p = f.get();
                    if (p != null) {
                        alive.add(p);
                    }
                } catch (Exception ignored) {
                    // probe failed mid-flight; just skip that peer
                }
            }
        }
        return alive;
    }

    private static boolean reachable(TailscaleStatus.Peer p, int chatPort) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p.ipv4(), chatPort), 1_000);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String[] validTarget(String input, int defaultPort) {
        if (input.contains(":")) {
            String[] parts = input.split(":", 2);
            try {
                return new String[]{parts[0], String.valueOf(Integer.parseInt(parts[1]))};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new String[]{input, String.valueOf(defaultPort)};
    }
}
