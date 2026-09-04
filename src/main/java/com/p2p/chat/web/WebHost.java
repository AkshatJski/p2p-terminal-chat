package com.p2p.chat.web;

import com.p2p.chat.core.HostNode;
import com.p2p.chat.core.Participant;
import com.p2p.chat.protocol.Protocol;
import com.p2p.chat.util.Ansi;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Serves the browser chat client. A WebSocket endpoint carries the same
 * application protocol as the encrypted channels (NAME/JOIN/MSG/...), so a web
 * user is a first-class participant in rooms, history and moderation. A plain
 * HTTP endpoint on the http port returns the single-page chat UI.
 *
 * <p><b>Security note:</b> the browser link is a convenience gateway and is
 * <i>not</i> end-to-end encrypted — it cannot run the X25519/AES handshake.
 * Desktop-to-desktop traffic stays encrypted; only web traffic is plaintext.
 */
public final class WebHost {
    private final HttpServer httpServer;
    private final WebSocketServer wsServer;
    private final HostNode host;
    private final ConcurrentMap<WebSocket, Participant> sessions = new ConcurrentHashMap<>();

    public WebHost(int wsPort, int httpPort, HostNode host) throws IOException {
        this.host = host;
        this.wsServer = new ChatSocketServer(wsPort);
        this.httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", ex -> {
            String page = PAGE.replace("__WS_PORT__", String.valueOf(wsServer.getPort()));
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.setExecutor(null); // default (single) executor; fine for UI pages
    }

    public void start() throws IOException {
        wsServer.start();
        httpServer.start();
        String addr = publicAddress();
        System.out.println(Ansi.color(Ansi.CYAN, "[Web] Chat UI:   http://" + addr + ":" + httpPort() + "/"));
        System.out.println(Ansi.color(Ansi.CYAN, "[Web] Endpoint:   ws://" + addr + ":" + wsPort() + "/ws"));
    }

    public void stop() {
        for (Participant p : sessions.values()) {
            host.removeWebPeer(p);
        }
        sessions.clear();
        try {
            wsServer.stop(100);
        } catch (Exception ignored) {
        }
        httpServer.stop(0);
    }

    public int wsPort() {
        return wsServer.getPort();
    }

    public int httpPort() {
        return httpServer.getAddress().getPort();
    }

    private static String publicAddress() {
        try {
            var nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                var ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "localhost";
    }

    private final class ChatSocketServer extends WebSocketServer {
        ChatSocketServer(int port) {
            super(new InetSocketAddress(port));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // Session is staged until the browser identifies itself with @NAME.
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            Participant participant = sessions.get(conn);
            if (participant == null) {
                if (!message.startsWith(Protocol.NAME + Protocol.SEP)) {
                    conn.send(Protocol.command(Protocol.ERR, "First message must be " + Protocol.NAME + " <username>"));
                    conn.close();
                    return;
                }
                String name = message.substring(Protocol.NAME.length() + 1).trim();
                java.util.function.Consumer<String> sink = line -> {
                    if (conn.isOpen()) {
                        conn.send(line);
                    }
                };
                Participant p = host.registerWebPeer(name, sink, conn::close);
                if (p == null) {
                    conn.send(Protocol.command(Protocol.ERR, "Username unavailable or banned: " + name));
                    conn.close();
                    return;
                }
                sessions.put(conn, p);
                conn.send(Protocol.command(Protocol.SYS, "Connected as " + name + ". Send @join <room> to start."));
                return;
            }
            host.routeWebLine(participant, message);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            Participant p = sessions.remove(conn);
            if (p != null) {
                host.removeWebPeer(p);
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn != null && conn.isOpen()) {
                Participant p = sessions.remove(conn);
                if (p != null) {
                    host.removeWebPeer(p);
                }
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onStart() {
            // Nothing extra to do; the endpoint is listening.
        }
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>P2P Chat</title>
            <style>
            :root{--bg:#0b0e14;--panel:#12171f;--panel2:#161c26;--border:#232b38;--text:#dbe4f0;--dim:#7d8aa0;--accent:#5eead4;--accent2:#38bdf8;--danger:#f87171;}
            *{box-sizing:border-box}
            body{margin:0;font-family:'Segoe UI',system-ui,-apple-system,sans-serif;background:radial-gradient(1200px 600px at 70% -10%,#16233a 0%,rgba(22,35,58,0) 60%),var(--bg);color:var(--text);height:100vh;display:flex;flex-direction:column}
            header{padding:10px 18px;background:var(--panel);border-bottom:1px solid var(--border);display:flex;align-items:center;justify-content:space-between;gap:12px}
            header .title{font-weight:600;letter-spacing:.2px}
            #status{font-size:13px;color:var(--dim);display:flex;align-items:center;gap:6px}
            #status .dot{width:9px;height:9px;border-radius:50%;background:#4b5668}
            #status.ok .dot{background:#34d399;box-shadow:0 0 8px #34d39988}
            #status.err .dot{background:var(--danger)}
            main{flex:1;display:flex;min-height:0}
            #sidebar{width:230px;background:var(--panel);border-right:1px solid var(--border);padding:12px;overflow-y:auto;display:flex;flex-direction:column;gap:14px}
            #sidebar h3{margin:0 0 6px;font-size:11px;text-transform:uppercase;letter-spacing:1px;color:var(--dim)}
            #rooms div,#users div{font-size:13px;padding:3px 8px;border-radius:6px}
            #rooms div:hover,#users div:hover{background:var(--panel2)}
            #rooms .cur{color:var(--accent);font-weight:600}
            #users .me{color:var(--accent2)}
            #chat{flex:1;display:flex;flex-direction:column;min-width:0}
            #messages{flex:1;overflow-y:auto;padding:16px 20px;display:flex;flex-direction:column;gap:4px}
            .msg{padding:6px 10px;border-radius:8px;font-size:14px;line-height:1.45;word-break:break-word;animation:fadein .18s ease}
            .msg:hover{background:rgba(255,255,255,.03)}
            .msg .who{font-weight:600;margin-right:6px}
            .msg .roomtag{color:var(--dim);font-size:11px;margin-right:6px}
            .msg.sys{color:var(--dim);font-style:italic;font-size:13px}
            .msg.err{color:var(--danger)}
            .msg.hist{opacity:.75}
            .msg .time{color:var(--dim);font-size:11px;opacity:.7}
            #typing{height:18px;font-size:12px;color:var(--dim);padding:0 20px}
            #composer{display:flex;gap:8px;padding:12px 16px;border-top:1px solid var(--border);background:var(--panel)}
            #composer input{flex:1;background:var(--panel2);border:1px solid var(--border);color:var(--text);border-radius:8px;padding:10px 14px;font-size:14px;outline:none}
            #composer input:focus{border-color:var(--accent2)}
            #composer button{background:linear-gradient(135deg,var(--accent2),#6366f1);color:#08111f;font-weight:600;border:0;border-radius:8px;padding:10px 18px;cursor:pointer;font-size:14px}
            #composer button:hover{filter:brightness(1.1)}
            #lobby{display:none;flex-direction:column;align-items:center;justify-content:center;gap:14px;flex:1}
            #lobby.show{display:flex}
            #lobby .card{background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:28px 34px;width:min(420px,90vw);text-align:center}
            #lobby h1{margin:0 0 6px;font-size:22px}
            #lobby p{color:var(--dim);margin:0 0 18px;font-size:13px}
            #lobby input{width:100%;background:var(--panel2);border:1px solid var(--border);color:var(--text);border-radius:8px;padding:10px 14px;font-size:15px;outline:none;margin-bottom:12px}
            #lobby input:focus{border-color:var(--accent)}
            #lobby button{width:100%;background:linear-gradient(135deg,var(--accent),#6366f1);color:#08111f;font-weight:600;border:0;border-radius:8px;padding:11px;font-size:15px;cursor:pointer}
            @keyframes fadein{from{opacity:0;transform:translateY(2px)}to{opacity:1;transform:none}}
            </style>
            </head>
            <body>
            <header>
              <div class="title">P2P Chat <span style="color:var(--dim);font-weight:400">- Web client</span></div>
              <div id="status"><span class="dot"></span><span id="statusText">offline</span></div>
            </header>
            <main>
              <div id="lobby" class="show">
                <div class="card">
                  <h1>Join the host</h1>
                  <p>Pick a display name. Desktops chat end-to-end encrypted; this web link is a plaintext gateway.</p>
                  <input id="nick" maxlength="32" placeholder="Display name" autocomplete="off">
                  <button id="connectBtn">Connect</button>
                </div>
              </div>
              <aside id="sidebar" style="display:none">
                <div><h3>Rooms</h3><div id="rooms"></div></div>
                <div><h3>In room</h3><div id="users"></div></div>
              </aside>
              <div id="chat" style="display:none">
                <div id="messages"></div>
                <div id="typing"></div>
                <div id="composer">
                  <input id="input" placeholder="Message... (try @join general, @history, @users)" autocomplete="off">
                  <button id="sendBtn">Send</button>
                </div>
              </div>
            </main>
            <script>
            var N = String.fromCharCode(0);
            var PALETTE = [35, 160, 190, 260, 320, 15, 95, 200];
            function colorFor(u){ if(!u) return '#dbe4f0'; var h=0; for(var i=0;i<u.length;i++){ h=(h*31+u.charCodeAt(i))>>>0; } return 'hsl(' + PALETTE[h%PALETTE.length] + ',70%,66%)'; }
            function $(id){ return document.getElementById(id); }
            var ws=null, me=null, room=null, typing=null;
            function setStatus(ok,text){ var el=$('status'); el.className = ok ? 'ok' : 'err'; $('statusText').textContent = text; }
            function add(html,cls){ var d=document.createElement('div'); d.className = (cls||'msg') + ' fade'; d.innerHTML = html; $('messages').appendChild(d); $('messages').scrollTop = 1e9; }
            function esc(s){ return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
            function time(){ return new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}); }
            function handle(line){
              var f = line.split(N);
              if(f[0] === '@FROM'){
                add('<span class="time">'+time()+'</span><span class="who" style="color:'+colorFor(f[2])+'">'+esc(f[2])+(f[3]?'@'+esc(f[3]):'')+'</span><span class="roomtag">'+esc(f[1])+'</span>'+esc(f[4]));
              } else if(f[0] === '@SYS'){
                add('<span class="time">'+time()+'</span>'+esc(f[1]||''), 'msg sys');
              } else if(f[0] === '@ERR'){
                add('<span class="time">'+time()+'</span>'+esc(f[1]||''), 'msg err');
              } else if(f[0] === '@HIST_ENTRY'){
                add('<span class="time">'+time()+'</span><span class="who" style="color:'+colorFor(f[1])+'">'+esc(f[1])+'</span>'+esc(f[2]), 'msg hist');
              } else if(f[0] === '@HIST_END'){
                add('--- end of history ---', 'msg hist');
              } else if(f[0] === '@ROOM_USERS'){
                $('users').innerHTML='';
                (f[2]||'').split(',').filter(Boolean).forEach(function(u){ var el=document.createElement('div'); el.textContent = u + (u===me ? ' (you)' : ''); if(u===me) el.className='me'; $('users').appendChild(el); });
              } else if(f[0] === '@ROOMS'){
                $('rooms').innerHTML='';
                (f[1]||'').split(',').filter(Boolean).forEach(function(entry){ var r = entry.split('(')[0]; var el=document.createElement('div'); el.textContent = entry; if(r===room) el.className='cur'; $('rooms').appendChild(el); });
              } else if(f[0] === '@TYPING'){
                typing = f[2]; $('typing').textContent = typing + ' is typing...';
              } else if(f[0] === '@TYPING_STOP'){
                typing = null; $('typing').textContent = '';
              }
            }
            function connect(){
              me = $('nick').value.trim(); if(!me) return;
              var proto = (location.protocol === 'https:') ? 'wss://' : 'ws://';
              ws = new WebSocket(proto + location.hostname + ':' + __WS_PORT__ + '/ws');
              ws.onopen = function(){ setStatus(true, 'connected as ' + me); ws.send('@NAME' + N + me); };
              ws.onmessage = function(ev){ handle(ev.data); };
              ws.onclose = function(){ setStatus(false, 'offline'); if(me) setTimeout(connect, 1500); };
              ws.onerror = function(){ setStatus(false, 'error'); };
              $('lobby').classList.remove('show'); $('sidebar').style.display = 'flex'; $('chat').style.display = 'flex';
              $('input').focus();
            }
            function send(){
              var v = $('input').value.trim(); if(!v || !ws || ws.readyState !== 1) return;
              $('input').value = '';
              if(v.charAt(0) === '@'){
                var parts = v.split(/\\s+/); var cmd = parts[0]; var arg = parts.slice(1).join(' ');
                if(cmd === '@join'){ ws.send('@JOIN' + N + arg); }
                else if(cmd === '@leave'){ ws.send('@LEAVE' + N); }
                else if(cmd === '@list'){ ws.send('@LIST' + N); }
                else if(cmd === '@users'){ ws.send('@USERS' + N); }
                else if(cmd === '@history'){ ws.send('@HISTORY' + N + (room||'') + N + (arg || '20')); }
                else { add('host-only or unknown command', 'msg err'); }
              } else {
                if(!room){ add('join a room first (@join <room>)', 'msg err'); return; }
                ws.send('@MSG' + N + room + N + v);
              }
            }
            $('connectBtn').onclick = connect;
            $('nick').addEventListener('keydown', function(e){ if(e.key === 'Enter') connect(); });
            $('sendBtn').onclick = send;
            $('input').addEventListener('keydown', function(e){ if(e.key === 'Enter') send(); });
            var tb = null;
            $('input').addEventListener('input', function(){
              if(!room || !ws || ws.readyState !== 1) return;
              if($('input').value && !tb){ ws.send('@TYPING' + N + room); tb = setTimeout(function(){ ws.send('@TYPING_STOP' + N + room); tb = null; }, 2000); }
            });
            </script>
            </body>
            </html>
            """;
}