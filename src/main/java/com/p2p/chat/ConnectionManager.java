package com.p2p.chat;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
public class ConnectionManager {
    private final Socket socket;
    private final PrintWriter writer;
    private final BufferedReader reader;
    public ConnectionManager(Socket socket) throws IOException {
        this.socket = socket;
        this.reader= new BufferedReader(new InputStreamReader(socket.getInputStream()));
        /*Getting the input from the other person Through Input stream reader*/
        this.writer = new PrintWriter(socket.getOutputStream(), true);
    }

    public void sendMessage(String message) throws IOException {
        writer.println(message);
    }

    public String receiveMessage() throws IOException {
        return reader.readLine();
    }

    public void close() throws IOException {
        try{
            socket.close();
        }
        catch(Exception e){
            System.err.println("An Error occured while closing the socket!! : "+e);
        }
    }
}
