package com.p2p.chat;

import java.util.Scanner;
import java.net.ServerSocket;
import java.net.Socket;
public class ClientMain {
    private static final int PORT=8080;

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        Socket socket = null;
        System.out.println("****** P2P Chat Client ******");
        System.out.println("Do you want to (H)ost or (J)oin a chat");
        String choice = in.nextLine().trim().toUpperCase();

        try {
            if (choice.equals("H")) {
                System.out.println("[System] Waiting for Peer to connect on the " + PORT + " ...");
                try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                    socket = serverSocket.accept();
                }
                System.out.println("[System] peer connected raw origin: " + socket.getRemoteSocketAddress());
            } else if (choice.equals("J")) {
                System.out.println("[System] Enter the Host's IP address [use Localhost for testing]: ");
                String targetIP = in.nextLine().trim();
                System.out.println("[System] Trying to connect to: " + targetIP + ":" + PORT + " ...");
                socket = new Socket(targetIP, PORT);
                System.out.println("[System] connection Successfull! ");
            } else {
                System.out.println("[System] Invalid choice");
                return;
            }
            ConnectionManager connectionManager = new ConnectionManager(socket);
            /*Wrapping our socket in our Manager*/

            //Spinning the background thread to listen for incoming network traffics
            NetworkListener networkListener = new NetworkListener(connectionManager);
            Thread threadListner = new Thread(networkListener);
            threadListner.start();
            System.out.println("[System] Chat Client Started. Type you're message below. Type /exit to quit: ");
            while (true) {
                System.out.print("[You]>>: ");
                String message = in.nextLine();
                if (message.equalsIgnoreCase("exit")) {
                    networkListener.stop();
                    connectionManager.close();
                    break;
                }
                if (!message.isBlank()) {
                    connectionManager.sendMessage(message);
                }
            }
        }
        catch(Exception e){
            System.out.println("[System] Exception occured: " + e.getMessage());
        }
        finally{
            System.out.println("[System] Chat Client Closed");
            in.close();
        }
    }
}
