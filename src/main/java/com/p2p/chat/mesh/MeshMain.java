package com.p2p.chat.mesh;

import com.p2p.chat.config.Config;
import com.p2p.chat.core.Prompt;
import com.p2p.chat.core.TrustGate;
import com.p2p.chat.crypto.Identity;
import com.p2p.chat.crypto.TrustStore;
import java.util.Scanner;

/**
 * Terminal entry point for the mesh mode - chat that keeps working without
 * WiFi (Bluetooth RFCOMM, or just a mesh of TCP links). Links can be added
 * after startup by running this with a peer spec argument, or interactively.
 *
 * <p>Usage:
 * <pre>
 *   java -cp app.jar com.p2p.chat.mesh.MeshMain                     // interactive
 *   java -cp app.jar com.p2p.chat.mesh.MeshMain tcp:1.2.3.4:8081    // dial on start
 *   java -cp app.jar com.p2p.chat.mesh.MeshMain serial:/dev/rfcomm0 // bluetooth
 *   java -cp app.jar com.p2p.chat.mesh.MeshMain --port 9090 tcp:1.2.3.4:9090
 * </pre>
 */
public class MeshMain {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        try {
            Config config = Config.load(args);
            System.out.println("[Config] Using " + config.getConfigFile()
                    + " | mesh port " + config.getMeshPort());
            Identity identity = Identity.loadOrCreate(config.getIdentityFile());
            TrustGate trustGate = new TrustGate(new TrustStore(config.getTrustFile()));
            Prompt prompt = message -> {
                System.out.print(message);
                String answer = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "no";
                return answer.equals("y") || answer.equals("yes");
            };

            System.out.println("****** P2P Mesh Chat (works without WiFi) ******");
            System.out.print("Your name: ");
            String name = in.nextLine().trim();
            MeshNode node = new MeshNode(name.isEmpty() ? "anon" : name, identity, trustGate);

            System.out.print("Listen on port " + config.getMeshPort() + "? (y/n) [y]: ");
            if (!in.hasNextLine() || !in.nextLine().trim().toLowerCase().startsWith("n")) {
                node.listen(config.getMeshPort());
            }

            // Dial links given on the command line first (options are stripped).
            for (String spec : Config.positional(args)) {
                dial(node, spec, prompt);
            }

            // Then let the user add links interactively.
            while (true) {
                System.out.print("Add a peer link? [tcp:host:port | serial:port | skip]: ");
                if (!in.hasNextLine()) {
                    break;
                }
                String spec = in.nextLine().trim();
                if (spec.isEmpty() || spec.equalsIgnoreCase("skip") || spec.equalsIgnoreCase("none")) {
                    break;
                }
                if (!dial(node, spec, prompt)) {
                    System.out.println("[Mesh] Failed to add: " + spec);
                }
            }

            System.out.println("[Mesh] Type @help for commands. Say '@exit' to quit.");
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
            System.out.println("[Mesh] Bye.");
        }
    }

    private static boolean dial(MeshNode node, String spec, Prompt prompt) {
        try {
            String s = spec.trim();
            if (s.startsWith("serial:")) {
                return node.connectSerial(s.substring("serial:".length()), prompt);
            }
            if (s.startsWith("tcp:")) {
                s = s.substring("tcp:".length());
            }
            if (s.startsWith("mesh://")) {
                s = s.substring("mesh://".length());
            }
            int colon = s.lastIndexOf(':');
            if (colon <= 0) {
                System.out.println("[Mesh] Bad link spec (expected tcp:host:port): " + spec);
                return false;
            }
            String host = s.substring(0, colon);
            int port = Integer.parseInt(s.substring(colon + 1));
            return node.connectTo(host, port, prompt);
        } catch (Exception e) {
            System.out.println("[Mesh] Could not dial " + spec + ": " + e.getMessage());
            return false;
        }
    }
}
