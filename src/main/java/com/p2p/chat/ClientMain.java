package com.p2p.chat;

import com.p2p.chat.config.Config;
import com.p2p.chat.core.ClientNode;
import com.p2p.chat.core.HostNode;
import com.p2p.chat.core.Node;
import com.p2p.chat.core.Prompt;
import com.p2p.chat.core.TrustGate;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.TrustStore;
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
        Scanner in = new Scanner(System.in);
        Node node = null;
        try {
            Config config = Config.load(args);
            System.out.println("[Config] Using " + config.getConfigFile()
                    + " | chat port " + config.getPort());
            Identity identity = Identity.loadOrCreate(config.getIdentityFile());
            TrustGate trustGate = new TrustGate(new TrustStore(config.getTrustFile()));
            Prompt prompt = message -> {
                System.out.print(message);
                String answer = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "no";
                return answer.equals("y") || answer.equals("yes");
            };

            System.out.println("****** P2P Chat (ECDH + AES-GCM) ******");
            System.out.println("[Security] Your device ID: "
                    + com.p2p.chat.crypto.CryptoUtil.fingerprint(identity.rawPublicKey(), identity.rawPublicKey()));
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
            } else if (choice.startsWith("J")) {
                System.out.print("Host IP [localhost for testing]: ");
                String ip = in.nextLine().trim();
                node = new ClientNode(name, ip, config.getPort(), identity, trustGate, prompt);
                node.start();
            } else {
                System.out.println("[System] Invalid choice. Run again and pick H or J.");
                return;
            }

            System.out.println("[System] Type @help for the command list. Say '@exit' to quit.");
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
        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
        } finally {
            if (node != null) {
                node.close();
            }
            System.out.println("[System] Chat closed.");
            in.close();
        }
    }
}
