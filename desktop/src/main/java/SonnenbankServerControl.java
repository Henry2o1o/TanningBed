import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Browser-based desktop controller, independent of Java's Wayland/X11 GUI support. */
public final class SonnenbankServerControl {
    private static final String CONTROL_URL="http://127.0.0.1:8764/";
    private static final String DASHBOARD_URL="http://127.0.0.1:8765/";
    private final Path serverLauncher;
    private final String csrf=UUID.randomUUID().toString();
    private volatile Process managedServer;
    private HttpServer server;
    private ExecutorService executor;

    private SonnenbankServerControl(Path launcher){serverLauncher=launcher;}
    public static void main(String[] args)throws Exception{
        if(args.length==0){System.err.println("Pfad zum Sonnenbank-Webserver fehlt.");return;}
        SonnenbankServerControl app=new SonnenbankServerControl(Path.of(args[0]));
        try{app.start();}catch(BindException e){openUrl(CONTROL_URL);System.out.println("Das Steuerfeld läuft bereits: "+CONTROL_URL);}
    }
    private void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8764),16);
        server.createContext("/api/",this::api);server.createContext("/",this::page);executor=Executors.newFixedThreadPool(3);server.setExecutor(executor);server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(managedServer!=null&&managedServer.isAlive())managedServer.destroy();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}));
        System.out.println("Sonnenbank-Steuerfeld: "+CONTROL_URL);openUrl(CONTROL_URL);
    }
    private void page(HttpExchange x)throws java.io.IOException{
        if(!"GET".equals(x.getRequestMethod())||!"/".equals(x.getRequestURI().getPath())){send(x,404,"text/plain; charset=utf-8","Nicht gefunden.");return;}
        String html="""
            <!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Sonnenbank Webserver</title><style>
            *{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 20% 0,#173d5a,#071321 55%,#030914);color:#eff8ff;font:16px system-ui,sans-serif;min-height:100vh;display:grid;place-items:center;padding:20px}.card{width:min(640px,100%);padding:28px;border:1px solid #244a6a;border-radius:24px;background:#071522eF;box-shadow:0 20px 80px #0009}.eyebrow{color:#52eac0;font-size:13px;letter-spacing:.12em;text-transform:uppercase}.title{font-size:clamp(26px,6vw,38px);margin:8px 0 4px}.sub{color:#a4bad0;margin:0 0 24px}.state{display:flex;align-items:center;gap:10px;padding:16px;border-radius:16px;background:#0b2031;border:1px solid #183953;margin-bottom:18px}.dot{width:12px;height:12px;border-radius:50%;background:#71849a}.dot.on{background:#27e6ad;box-shadow:0 0 16px #27e6ad}.actions{display:flex;gap:10px;flex-wrap:wrap}.btn{border:1px solid #275176;background:#10273a;color:#e9f8ff;border-radius:13px;padding:12px 17px;font:600 15px system-ui;cursor:pointer}.btn:hover{border-color:#27c8ff;box-shadow:0 0 16px #159ee555}.btn.primary{background:linear-gradient(120deg,#008ac7,#00ad9e);border-color:#13cbb7}.btn.stop{border-color:#604454;color:#ffb6bf}.btn:disabled{opacity:.42;cursor:not-allowed;box-shadow:none}.note{color:#8fa8bd;font-size:13px;margin-top:20px;line-height:1.5}#message{min-height:24px;color:#65e7c5;margin-top:12px}</style>
            <main class="card"><div class="eyebrow">Solarbank · PC-Steuerung</div><h1 class="title">Sonnenbank Webserver</h1><p class="sub">Steuere den lokalen Webserver und öffne dein Live-Dashboard.</p>
            <div class="state"><span class="dot" id="dot"></span><strong id="state">Status wird geprüft …</strong></div>
            <div class="actions"><button class="btn primary" id="start">Server starten</button><button class="btn stop" id="stop">Server stoppen</button><button class="btn" id="open">Dashboard öffnen</button></div>
            <div id="message" role="status"></div><p class="note">Steuerfeld: 127.0.0.1:8764 · Dashboard: 127.0.0.1:8765. Beide Adressen sind nur auf diesem PC erreichbar.</p></main>
            <script>const token='__TOKEN__',root='http://127.0.0.1:8765/';const dot=document.getElementById('dot'),state=document.getElementById('state'),start=document.getElementById('start'),stop=document.getElementById('stop'),message=document.getElementById('message');let busy=false;
            async function status(){try{const s=await fetch('/api/status',{cache:'no-store'}).then(r=>r.json());dot.classList.toggle('on',s.running);state.textContent=s.running?'Server läuft':'Server ist gestoppt';start.disabled=busy||s.running;stop.disabled=busy||!s.running}catch{state.textContent='Steuerdienst nicht erreichbar'}}
            async function action(name){busy=true;message.textContent=name==='start'?'Serverstart läuft …':'Server wird gestoppt …';await status();try{const r=await fetch('/api/'+name,{method:'POST',headers:{'X-Sonnenbank-Control':token}});if(!r.ok)throw new Error('Aktion fehlgeschlagen');message.textContent=name==='start'?'Server gestartet.':'Server gestoppt.'}catch(e){message.textContent=e.message}finally{busy=false;setTimeout(status,300)}}
            start.onclick=()=>action('start');stop.onclick=()=>action('stop');document.getElementById('open').onclick=()=>window.open(root,'_blank');status();setInterval(status,1000);</script></html>
            """.replace("__TOKEN__",csrf);
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        send(x,200,"text/html; charset=utf-8",html);
    }
    private void api(HttpExchange x)throws java.io.IOException{
        x.getResponseHeaders().set("Cache-Control","no-store");
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        if("GET".equals(method)&&path.equals("/api/status")){sendJson(x,200,new JSONObject().put("running",isDashboardRunning()));return;}
        String origin=x.getRequestHeaders().getFirst("Origin"),host=x.getRequestHeaders().getFirst("Host"),provided=x.getRequestHeaders().getFirst("X-Sonnenbank-Control");
        if(!"POST".equals(method)||!("http://127.0.0.1:8764".equals(origin)||"http://localhost:8764".equals(origin))||!("127.0.0.1:8764".equals(host)||"localhost:8764".equals(host))||!csrf.equals(provided)){sendJson(x,403,new JSONObject().put("error","Ungültige lokale Anfrage."));return;}
        if(path.equals("/api/start")){boolean ok=startDashboard();sendJson(x,ok?202:500,new JSONObject().put("ok",ok).put("error",ok?JSONObject.NULL:"Webserver konnte nicht gestartet werden."));return;}
        if(path.equals("/api/stop")){new Thread(()->stopDashboard(),"sonnenbank-control-stop").start();sendJson(x,202,new JSONObject().put("ok",true));return;}
        sendJson(x,404,new JSONObject().put("error","Nicht gefunden."));
    }
    private synchronized boolean startDashboard(){if(isDashboardRunning())return true;if(managedServer!=null&&managedServer.isAlive())return true;try{managedServer=new ProcessBuilder(serverLauncher.toAbsolutePath().toString()).redirectErrorStream(true).start();Process launched=managedServer;Thread reader=new Thread(()->{try(InputStream in=launched.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)System.out.print(new String(b,0,n,StandardCharsets.UTF_8));}catch(Exception ignored){}});reader.setDaemon(true);reader.start();return true;}catch(Exception e){System.err.println("Webserver-Start fehlgeschlagen: "+e.getMessage());return false;}}
    private void stopDashboard(){
        boolean stopped=false;try{String html=read(DASHBOARD_URL);String token=tokenFrom(html);if(!token.isBlank()){HttpURLConnection c=(HttpURLConnection)new URL(DASHBOARD_URL+"api/shutdown").openConnection();c.setRequestMethod("POST");c.setConnectTimeout(1200);c.setReadTimeout(1200);c.setDoOutput(true);c.setRequestProperty("Origin","http://127.0.0.1:8765");c.setRequestProperty("X-Sonnenbank-Token",token);c.setRequestProperty("Content-Type","application/json");c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));stopped=c.getResponseCode()==202;c.disconnect();}}catch(Exception ignored){}
        if(!stopped)for(ProcessHandle candidate:ProcessHandle.allProcesses().toList()){String command=candidate.info().commandLine().orElse("");if(command.contains("SonnenbankWebServer")){candidate.destroy();try{candidate.onExit().toCompletableFuture().get(3,TimeUnit.SECONDS);stopped=true;}catch(Exception e){stopped=candidate.destroyForcibly();}}}
        Process owned=managedServer;if(owned!=null&&owned.isAlive()){owned.destroy();try{if(!owned.waitFor(3,TimeUnit.SECONDS))owned.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();owned.destroyForcibly();}stopped=true;}
        System.out.println(stopped?"Sonnenbank-Webserver gestoppt.":"Kein laufender Sonnenbank-Webserver gefunden.");
    }
    private boolean isDashboardRunning(){try{JSONObject response=new JSONObject(read(DASHBOARD_URL+"api/state"));return response.has("authenticated");}catch(Exception e){return false;}}
    private static String read(String address)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(800);c.setReadTimeout(1000);try(InputStream in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}}
    private static String tokenFrom(String html){String marker="window.SONNENBANK_CSRF='";int at=html.indexOf(marker);if(at<0)return "";at+=marker.length();int end=html.indexOf('\'',at);return end<0?"":html.substring(at,end);}
    private static void openUrl(String address){try{new ProcessBuilder("xdg-open",address).start();}catch(Exception e){System.out.println("Öffne im Browser: "+address);}}
    private static void sendJson(HttpExchange x,int status,JSONObject value)throws java.io.IOException{send(x,status,"application/json; charset=utf-8",value.toString());}
    private static void send(HttpExchange x,int status,String contentType,String value)throws java.io.IOException{byte[] data=value.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",contentType);x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);x.close();}
}
