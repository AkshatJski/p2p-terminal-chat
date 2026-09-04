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
import com.p2p.chat.util.Ansi;
import java.net.ConnectException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Scanner;

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

            System.out.println(Ansi.bold(Ansi.BRIGHT_CYAN, "****** P2P Chat (ECDH + AES-GCM) ******"));
            System.out.println(Ansi.color(Ansi.CYAN, "[Security] Your device ID: "
                    + CryptoUtil.fingerprint(identity.rawPublicKey(), identity.rawPublicKey())));
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
                printLocalAddresses(config.getPort());
            } else if (choice.startsWith("J")) {
                System.out.print("Host IP (or host:port) [localhost]: ");
                String input = in.nextLine().trim();
                String host;
                int port;
                if (input.contains(":")) {
                    String[] parts = input.split(":", 2);
                    host = parts[0];
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        System.out.println(Ansi.color(Ansi.RED, "[Error] Invalid port in " + input));
                        return;
                    }
                } else {
                    host = input.isEmpty() ? "localhost" : input;
                    port = config.getPort();
                }
                node = new ClientNode(name, host, port, identity, trustGate, prompt);
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
                if (line.trim().equalsIgnoreCase("@exit")) {
                    node.close();
                    break;
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

    private static void printLocalAddresses(int port) {
        try {
            System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "[System] Others can join you at:"));
            boolean found = false;
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String label = ni.getDisplayName();
                    System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  " + addr.getHostAddress()
                            + ":" + port + "  (" + label + ")"));
                    found = true;
                }
            }
            if (!found) {
                System.out.println(Ansi.color(Ansi.BRIGHT_GREEN, "  localhost:" + port + "  (local only)"));
            }
        } catch (SocketException e) {
            System.out.println(Ansi.color(Ansi.DIM, "  (could not enumerate network interfaces)"));
        }
    }
}
