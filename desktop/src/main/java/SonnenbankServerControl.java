import org.json.JSONObject;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Small Linux desktop controller that stays available while the web server is stopped. */
public final class SonnenbankServerControl {
    private static final String URL_ROOT="http://127.0.0.1:8765/";
    private final Path serverLauncher;
    private final JFrame frame=new JFrame("Sonnenbank Webserver");
    private final JLabel state=new JLabel("Prüfe Server …");
    private final JTextArea log=new JTextArea(7,42);
    private final JButton start=new JButton("Server starten");
    private final JButton stop=new JButton("Server stoppen");
    private Process process;

    private SonnenbankServerControl(Path launcher){serverLauncher=launcher;}
    public static void main(String[] args){
        if(args.length==0){System.err.println("Pfad zum Sonnenbank-Webserver fehlt.");return;}
        SwingUtilities.invokeLater(()->new SonnenbankServerControl(Path.of(args[0])).show());
    }
    private void show(){
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);frame.setSize(540,350);frame.setLocationRelativeTo(null);
        JPanel root=new JPanel(new BorderLayout(10,10));root.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        JLabel title=new JLabel("Sonnenbank Webserver");title.setFont(title.getFont().deriveFont(Font.BOLD,19f));
        JLabel address=new JLabel("Dashboard: "+URL_ROOT);JPanel top=new JPanel(new BorderLayout(3,6));top.add(title,BorderLayout.NORTH);top.add(state,BorderLayout.CENTER);top.add(address,BorderLayout.SOUTH);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));buttons.add(start);buttons.add(stop);JButton open=new JButton("Dashboard öffnen");buttons.add(open);
        log.setEditable(false);log.setLineWrap(true);log.setWrapStyleWord(true);log.setText("Hier erscheinen Start- und Stoppmeldungen.\n");
        root.add(top,BorderLayout.NORTH);root.add(new JScrollPane(log),BorderLayout.CENTER);root.add(buttons,BorderLayout.SOUTH);frame.setContentPane(root);
        start.addActionListener(e->startServer());stop.addActionListener(e->stopServer());open.addActionListener(e->openDashboard());
        Timer timer=new Timer(1000,e->refreshState());timer.start();frame.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){timer.stop();}});
        refreshState();frame.setVisible(true);
    }
    private void startServer(){
        if(isServerRunning()){append("Der Server läuft bereits.");openDashboard();refreshState();return;}
        start.setEnabled(false);try{process=new ProcessBuilder(serverLauncher.toAbsolutePath().toString()).redirectErrorStream(true).start();Process launched=process;append("Serverstart angefordert.");Thread reader=new Thread(()->{try(InputStream in=launched.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){String line=new String(b,0,n,StandardCharsets.UTF_8);SwingUtilities.invokeLater(()->append(line.trim()));}}catch(Exception ignored){}});reader.setDaemon(true);reader.start();}
        catch(Exception e){append("Start fehlgeschlagen: "+e.getMessage());}
        refreshState();
    }
    private void stopServer(){
        Thread stopper=new Thread(()->{
            boolean stopped=false;
            try{String html=read(URL_ROOT);String token=tokenFrom(html);if(!token.isBlank()){HttpURLConnection c=(HttpURLConnection)new URL(URL_ROOT+"api/shutdown").openConnection();c.setRequestMethod("POST");c.setConnectTimeout(1200);c.setReadTimeout(1200);c.setDoOutput(true);c.setRequestProperty("Origin","http://127.0.0.1:8765");c.setRequestProperty("X-Sonnenbank-Token",token);c.setRequestProperty("Content-Type","application/json");c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));stopped=c.getResponseCode()==202;c.disconnect();}}
            catch(Exception ignored){}
            if(!stopped){for(ProcessHandle candidate:ProcessHandle.allProcesses().toList()){String command=candidate.info().commandLine().orElse("");if(command.contains("SonnenbankWebServer")){candidate.destroy();try{if(!candidate.onExit().get(3,java.util.concurrent.TimeUnit.SECONDS).isAlive())stopped=true;else stopped=candidate.destroyForcibly();}catch(Exception e){stopped=candidate.destroyForcibly();}}}}
            Process owned=process;if(owned!=null&&owned.isAlive()){owned.destroy();try{if(!owned.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))owned.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();owned.destroyForcibly();}stopped=true;}
            boolean ok=stopped;SwingUtilities.invokeLater(()->{append(ok?"Server wurde gestoppt.":"Es läuft kein Sonnenbank-Webserver.");refreshState();});
        },"sonnenbank-stop");stopper.setDaemon(true);stopper.start();
    }
    private boolean isServerRunning(){try{String response=read(URL_ROOT+"api/state");new JSONObject(response).getBoolean("authenticated");return true;}catch(Exception e){return false;}}
    private static String read(String address)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(800);c.setReadTimeout(1000);try(InputStream in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}}
    private static String tokenFrom(String html){String marker="window.SONNENBANK_CSRF='";int at=html.indexOf(marker);if(at<0)return "";at+=marker.length();int end=html.indexOf('\'',at);return end<0?"":html.substring(at,end);}
    private void openDashboard(){try{java.awt.Desktop.getDesktop().browse(URI.create(URL_ROOT));}catch(Exception e){append("Browser konnte nicht geöffnet werden. Adresse: "+URL_ROOT);}}
    private void refreshState(){boolean running=isServerRunning();state.setText(running?"● Server läuft":"○ Server ist gestoppt");start.setEnabled(!running);stop.setEnabled(running||process!=null&&process.isAlive());}
    private void append(String message){if(message==null||message.isBlank())return;log.append(message+"\n");log.setCaretPosition(log.getDocument().getLength());}
}
