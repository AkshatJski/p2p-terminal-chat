package com.p2p.chat;

public class NetworkListener implements Runnable {
    private final ConnectionManager connectionManager;
    public boolean running=true;
    public NetworkListener(ConnectionManager connectionManager){
        this.connectionManager=connectionManager;
    }
    @Override
    public void run(){
        try{
            while(running){
                String incoming=connectionManager.receiveMessage();
                if(incoming==null){
                    System.out.println("\n[System] Peer Disconnected.");
                    break;
                }
                //Print the incoming message
                System.out.println("\n[Peer]: "+incoming);
                System.out.print("[You]: "); //Keeping the input prompt visible
            }
        }
        catch(Exception e){
            if(running){
                System.err.println("\n[Error] Connection Lost: " + e);
            }
        }
    }
    public void stop(){
        this.running=false;
    }
}
