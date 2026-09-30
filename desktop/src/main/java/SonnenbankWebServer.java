import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** Local-only bridge from the browser dashboard to Anker cloud, MQTT and myStrom. */
public final class SonnenbankWebServer {
    private static final int PORT=8765;
    private static final String TOKEN_KEY="web_auth_token", USER_KEY="web_user_token";
    private final DesktopPreferences prefs=new DesktopPreferences();
    private final Preferences local=Preferences.userRoot().node("/de/codex/sonnenbank-web");
    private final String csrf=UUID.randomUUID().toString();
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    private ExecutorService httpExecutor;
    private final ArrayList<JSONObject> samples=new ArrayList<>();
    private AnkerCloudClient cloud;
    private AnkerMqttLiveClient mqtt;
    private JSONArray sites=new JSONArray(),devices=new JSONArray();
    private JSONObject scene=new JSONObject();
    private JSONObject latestCloudValues=new JSONObject();
    private JSONObject bluettiReading=new JSONObject();
    private volatile double myStromPower=Double.NaN;
    private long lastMyStromSampleAt=0;
    private long lastBluettiSampleAt=0;
    private String siteId="",nickname="";
    private volatile String status="Anker SOLIX noch nicht verbunden";
    private long lastUpdate=0;
    private final Path historyFile=Path.of(System.getProperty("user.home"),".sonnenbank","web-history.jsonl");

    public static void main(String[] args)throws Exception{new SonnenbankWebServer().start();}
    private SonnenbankWebServer()throws Exception{loadHistory();try{bluettiReading=new JSONObject(prefs.getString("web_bluetti_latest","{}"));}catch(Exception ignored){bluettiReading=new JSONObject();}}
    private void start()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),32);
        server.createContext("/api/",this::api);server.createContext("/",this::staticFile);httpExecutor=Executors.newFixedThreadPool(6);server.setExecutor(httpExecutor);server.start();
        restoreSession();scheduler.scheduleWithFixedDelay(this::pollCloud,1,30,TimeUnit.SECONDS);scheduler.scheduleAtFixedRate(this::pollMyStrom,0,1,TimeUnit.SECONDS);
        String address="http://127.0.0.1:"+PORT+"/";System.out.println("Sonnenbank Web läuft lokal: "+address);
        try{new ProcessBuilder("xdg-open",address).start();}catch(Exception ignored){}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(mqtt!=null)mqtt.stop();scheduler.shutdownNow();server.stop(0);httpExecutor.shutdownNow();}));
    }

    private void restoreSession(){String a=prefs.getString(TOKEN_KEY,""),u=prefs.getString(USER_KEY,"");if(a.isEmpty()||u.isEmpty())return;
        try{AnkerCloudClient c=new AnkerCloudClient(a,u);String nick=prefs.getString("nickname","Sonnenbank");AnkerCloudClient.Result result=c.getSites(nick);acceptSession(c,result,nick);}
        catch(Exception ex){prefs.remove(TOKEN_KEY);prefs.remove(USER_KEY);status="Bitte erneut bei Anker SOLIX anmelden";}}

    private synchronized void acceptSession(AnkerCloudClient c,AnkerCloudClient.Result result,String nick){
        if(mqtt!=null)mqtt.stop();cloud=c;sites=result.sites;nickname=nick;siteId=local.get("site_id",firstSiteId(sites));
        if(indexOfSite(sites,siteId)<0)siteId=firstSiteId(sites);
        try{JSONArray a=c.getUserDevices(),b=c.getBoundDevices();devices=merge(a,b);}catch(Exception ignored){devices=new JSONArray();}
        status="Angemeldet · Anker SOLIX Cloud";try{JSONObject info=c.getMqttInfo();mqtt=new AnkerMqttLiveClient(prefs,info,devices,s->status=s);mqtt.start();status="Anker verbunden · MQTT verbindet …";}catch(Exception e){mqtt=null;status="Anker verbunden · MQTT nicht verfügbar";}
        prefs.putString(TOKEN_KEY,c.authToken());prefs.putString(USER_KEY,c.userToken());prefs.putString("nickname",nick);local.put("site_id",siteId);scheduler.execute(this::pollCloud);
    }

    private void api(HttpExchange x)throws java.io.IOException{
        try{x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.equals("/api/live")&&method.equals("GET")){sendJson(x,200,liveSnapshot());return;}
            if(path.equals("/api/state")&&method.equals("GET")){sendJson(x,200,snapshot(x.getRequestURI().getQuery()));return;}
            if(!csrf.equals(x.getRequestHeaders().getFirst("X-Sonnenbank-Token"))||!("http://127.0.0.1:"+PORT).equals(x.getRequestHeaders().getFirst("Origin"))){sendJson(x,403,new JSONObject().put("error","Ungültige lokale Anfrage."));return;}
            JSONObject body=readJson(x);
            if(path.equals("/api/shutdown")&&method.equals("POST")){sendJson(x,202,new JSONObject().put("ok",true));Thread stopper=new Thread(()->{try{Thread.sleep(250);}catch(InterruptedException ignored){}System.exit(0);},"sonnenbank-shutdown");stopper.setDaemon(true);stopper.start();return;}
            if(path.equals("/api/login")&&method.equals("POST")){String email=body.optString("email").trim(),password=body.optString("password");if(email.isBlank()||password.isBlank()){sendJson(x,400,new JSONObject().put("error","Bitte E-Mail und Passwort eingeben."));return;}try{AnkerCloudClient c=new AnkerCloudClient();AnkerCloudClient.Result r=c.loginAndGetSites(email,password);acceptSession(c,r,r.nickname);sendJson(x,200,snapshot(null));}catch(Exception e){sendJson(x,401,new JSONObject().put("error",safe(e)));}return;}
            if(path.equals("/api/select")&&method.equals("POST")){String id=body.optString("siteId");synchronized(this){if(indexOfSite(sites,id)<0){sendJson(x,404,new JSONObject().put("error","System nicht gefunden."));return;}siteId=id;latestCloudValues=new JSONObject();local.put("site_id",id);}scheduler.execute(this::pollCloud);sendJson(x,200,liveSnapshot());return;}
            if(path.equals("/api/mystrom")&&method.equals("POST")){String ip=body.optString("ip").trim();if(!privateIPv4(ip)){sendJson(x,400,new JSONObject().put("error","Bitte die lokale IP-Adresse der myStrom-Steckdose prüfen."));return;}local.put("mystrom_ip",ip);scheduler.execute(this::pollMyStrom);sendJson(x,200,new JSONObject().put("ok",true));return;}
            if(path.equals("/api/bluetti")&&method.equals("POST")){JSONObject clean=new JSONObject();for(String key:new String[]{"batteryPercent","dcInput","acInput","acOutput","dcOutput","batteryVoltage","acVoltage","acCurrent","dcVoltage","dcCurrent","generatedKwh","packCount","packMax","packPercent","updatedAt"})if(body.has(key)&&!body.isNull(key)){Object v=body.opt(key);if(v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))clean.put(key,v);}synchronized(this){bluettiReading=clean;prefs.putString("web_bluetti_latest",clean.toString());long now=System.currentTimeMillis();if(now-lastBluettiSampleAt>=30000){JSONObject sample=new JSONObject().put("t",now).put("values",new JSONObject().put("bluettiSoc",clean.opt("batteryPercent")).put("bluettiDcInputW",clean.opt("dcInput")).put("bluettiAcInputW",clean.opt("acInput")).put("bluettiAcOutputW",clean.opt("acOutput")).put("bluettiDcOutputW",clean.opt("dcOutput")));appendSample(sample);lastBluettiSampleAt=now;}}sendJson(x,200,new JSONObject().put("ok",true));return;}
            if(path.equals("/api/logout")&&method.equals("POST")){synchronized(this){if(mqtt!=null)mqtt.stop();mqtt=null;cloud=null;devices=new JSONArray();sites=new JSONArray();siteId="";scene=new JSONObject();latestCloudValues=new JSONObject();status="Abgemeldet";prefs.remove(TOKEN_KEY);prefs.remove(USER_KEY);}sendJson(x,200,new JSONObject().put("ok",true));return;}
            if(path.equals("/api/refresh")&&method.equals("POST")){scheduler.execute(this::pollCloud);sendJson(x,202,new JSONObject().put("ok",true));return;}
            sendJson(x,404,new JSONObject().put("error","Unbekannte Anfrage."));
        }catch(Exception e){sendJson(x,500,new JSONObject().put("error",safe(e)));}finally{x.close();}}

    private void staticFile(HttpExchange x)throws java.io.IOException{
        String path=x.getRequestURI().getPath();if(path.equals("/"))path="/index.html";
        if(path.contains("..")||!(path.equals("/index.html")||path.equals("/assets/solar_hero_night.png"))){x.sendResponseHeaders(404,-1);x.close();return;}
        try(InputStream in=SonnenbankWebServer.class.getResourceAsStream(path.substring(1))){if(in==null){x.sendResponseHeaders(404,-1);return;}byte[] data=in.readAllBytes();String type=path.endsWith(".png")?"image/png":"text/html; charset=utf-8";if(path.equals("/index.html")){String html=new String(data,StandardCharsets.UTF_8).replace("</head>","<script>window.SONNENBANK_CSRF='"+csrf+"';</script></head>");data=html.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");}x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);}finally{x.close();}}

    private void pollCloud(){AnkerCloudClient c;String id;synchronized(this){c=cloud;id=siteId;}if(c==null||id.isEmpty())return;
        try{JSONObject data=c.getSceneInfo(id);JSONObject bank=data.optJSONObject("solarbank_info");if(bank==null){bank=new JSONObject();data.put("solarbank_info",bank);}JSONObject grid=data.optJSONObject("grid_info");if(grid==null){grid=new JSONObject();data.put("grid_info",grid);}JSONObject mqttBank=findMqtt("AE103"),meter=findMqtt("AE1X0");if(mqttBank!=null){copyNumber(mqttBank,bank,"photovoltaic_power","total_photovoltaic_power");copyNumber(mqttBank,bank,"battery_power_signed","total_battery_power_signed");if(mqttBank.has("home_demand"))data.put("home_load_power",mqttBank.optDouble("home_demand"));}
            if(meter!=null){for(int i=1;i<=3;i++){copyNumber(meter,grid,"grid_power_signed_l"+i,"grid_power_signed_l"+i);copyNumber(meter,grid,"voltage_l"+i,"voltage_l"+i);copyNumber(meter,grid,"current_l"+i,"current_l"+i);}copyNumber(meter,grid,"grid_power_signed","grid_power_signed");}
            double pv=num(bank,"total_photovoltaic_power","total_pv_input_power");double pvInputs=0;boolean hasPvInput=false;for(int i=1;i<=4;i++){double w=num(mqttBank==null?new JSONObject():mqttBank,"pv_"+i+"_power");if(!Double.isFinite(w))w=findNumeric(data,"solar_power_"+i,"pv_"+i+"_power","pv"+i+"_power","pv_power_"+i,"photovoltaic_power_"+i,"pv_input_power_"+i);if(Double.isFinite(w)&&w>=0&&w<=5000){pvInputs+=w;hasPvInput=true;}}if(hasPvInput)pv=pvInputs;double home=num(data,"home_load_power","to_home_load"),battery=num(bank,"total_battery_power_signed","total_charging_power"),soc=num(bank,"battery_soc","total_battery_power");if(soc<0||soc>100){JSONArray bl=bank.optJSONArray("solarbank_list");if(bl!=null&&bl.length()>0){JSONObject first=bl.optJSONObject(0);if(first!=null)soc=num(first,"battery_soc");}}if(soc>=0&&soc<=1)soc*=100;
            double signed=num(grid,"grid_power_signed"),imp=num(grid,"grid_to_home_power"),exp=num(grid,"photovoltaic_to_grid_power");if(Double.isFinite(imp)||Double.isFinite(exp))signed=(Double.isFinite(imp)?imp:0)-(Double.isFinite(exp)?exp:0);
            JSONObject values=new JSONObject().put("pvW",finite(pv)).put("homeW",finite(home)).put("batteryW",finite(battery)).put("gridW",finite(signed)).put("gridInW",finite(imp)).put("gridOutW",finite(exp)).put("soc",finite(soc)).put("capacityWh",18112d);for(int i=1;i<=4;i++){String n=String.valueOf(i);double channelW=num(mqttBank==null?new JSONObject():mqttBank,"pv_"+n+"_power");if(!Double.isFinite(channelW))channelW=findNumeric(data,"solar_power_"+n,"pv_"+n+"_power","pv"+n+"_power","pv_power_"+n,"photovoltaic_power_"+n,"pv_input_power_"+n);double channelA=num(mqttBank==null?new JSONObject():mqttBank,"pv_"+n+"_current","pv"+n+"_current");if(!Double.isFinite(channelA))channelA=findNumeric(data,"solar_current_"+n,"pv_"+n+"_current","pv"+n+"_current","pv_current_"+n,"photovoltaic_current_"+n,"pv_input_current_"+n);values.put("pv"+i+"W",finite(channelW)).put("pv"+i+"A",finite(channelA));}for(int i=1;i<=3;i++){values.put("l"+i+"W",finite(num(grid,"grid_power_signed_l"+i)));values.put("v"+i,finite(num(grid,"voltage_l"+i)));values.put("a"+i,finite(num(grid,"current_l"+i)));}double total=0;boolean hasPhase=false;for(int i=1;i<=3;i++){double phase=num(grid,"grid_power_signed_l"+i);if(Double.isFinite(phase)){total+=phase;hasPhase=true;}}values.put("totalW",finite(hasPhase?total:signed));
            JSONArray deviceData=deviceSnapshot();long now=System.currentTimeMillis();JSONObject sample=new JSONObject().put("t",now).put("values",values);for(int i=0;i<deviceData.length();i++){JSONObject d=deviceData.getJSONObject(i);String sn=d.optString("serial");if(isPlug(d)&&!d.isNull("powerW")){double w=d.optDouble("powerW",Double.NaN);if(Double.isFinite(w))sample.getJSONObject("values").put("plug:"+sn,w);}}
            sample.put("siteId",id);synchronized(this){scene=data;latestCloudValues=values;lastUpdate=now;status=mqtt==null?"Anker verbunden · Cloudwerte":"Verbunden · Cloud + MQTT";appendSample(sample);}
        }catch(Exception e){synchronized(this){status="Cloud-Abfrage fehlgeschlagen · "+safe(e);}}}

    private void pollMyStrom(){String ip=local.get("mystrom_ip","");if(ip.isBlank())return;try{HttpURLConnection c=(HttpURLConnection)new URL("http://"+ip+"/report").openConnection();c.setConnectTimeout(900);c.setReadTimeout(900);try{if(c.getResponseCode()!=200)return;JSONObject report=new JSONObject(new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8));double watts=num(report,"power");if(!Double.isFinite(watts))return;long now=System.currentTimeMillis();myStromPower=watts;synchronized(this){if(now-lastMyStromSampleAt>=10000){JSONObject values=new JSONObject().put("mystromW",watts);appendSample(new JSONObject().put("t",now).put("values",values));lastMyStromSampleAt=now;}}}finally{c.disconnect();}}catch(Exception ignored){}}

    private synchronized JSONObject liveSnapshot(){
        JSONObject current=new JSONObject(latestCloudValues.toString());
        String[] keys={"pvW","homeW","batteryW","gridW","gridInW","gridOutW","soc","capacityWh","pv1W","pv1A","pv2W","pv2A","pv3W","pv3A","pv4W","pv4A","l1W","l2W","l3W","totalW","v1","v2","v3","a1","a2","a3","mystromW"};
        for(String key:keys)if(!current.has(key))current.put(key,JSONObject.NULL);
        mergeCurrentMqtt(current);if(Double.isFinite(myStromPower))current.put("mystromW",myStromPower);
        JSONArray sys=new JSONArray();for(int i=0;i<sites.length();i++){JSONObject s=sites.optJSONObject(i);if(s!=null)sys.put(new JSONObject().put("id",first(s,"site_id","id")).put("name",firstOr(s,"Solarbank-System "+(i+1),"site_name","name","home_name")));}
        JSONArray plugValues=new JSONArray();for(int i=0;i<devices.length();i++){JSONObject d=deviceAt(i);if(isPlug(d))plugValues.put(new JSONObject().put("serial",d.optString("serial")).put("name",d.optString("name")).put("powerW",d.opt("powerW")).put("voltageV",d.opt("voltageV")).put("currentA",d.opt("currentA")).put("energyKWh",d.opt("energyKWh")).put("history",new JSONArray()));}
        JSONObject liveBank=findMqtt("AE103");String homeSource=liveBank!=null&&liveBank.has("home_demand")?"MQTT":"Anker Cloud";return new JSONObject().put("buildVersion","1.11").put("authenticated",cloud!=null).put("status",status).put("homeSource",homeSource).put("nickname",nickname).put("siteId",siteId).put("sites",sys).put("values",current).put("devices",deviceSnapshot()).put("plugs",plugValues).put("bluetti",bluettiReading).put("updatedAt",lastUpdate).put("mystromIp",local.get("mystrom_ip","")).put("mqttReceivedAt",prefs.getString("mqtt_last_received",""));
    }

    private synchronized JSONObject snapshot(String query){String range="Tag";if(query!=null)for(String item:query.split("&")){String[] p=item.split("=",2);if(p.length==2&&p[0].equals("range"))range=p[1];}long cutoff=range.equalsIgnoreCase("Jahr")?System.currentTimeMillis()-365L*86400000:range.equalsIgnoreCase("Monat")?System.currentTimeMillis()-30L*86400000:LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();JSONArray filtered=new JSONArray();for(JSONObject s:samples)if(s.optLong("t")>=cutoff&&(siteId.equals(s.optString("siteId"))||isOtherSample(s.optJSONObject("values"))))filtered.put(s);
        String[] currentKeys={"pvW","homeW","batteryW","gridW","gridInW","gridOutW","soc","capacityWh","pv1W","pv1A","pv2W","pv2A","pv3W","pv3A","pv4W","pv4A","l1W","l2W","l3W","totalW","v1","v2","v3","a1","a2","a3","mystromW"};JSONObject latest=new JSONObject();for(int i=samples.size()-1;i>=0;i--){JSONObject sample=samples.get(i),v=sample.optJSONObject("values");if(v==null||(!siteId.equals(sample.optString("siteId"))&&!v.has("mystromW")))continue;for(String key:currentKeys)if(!latest.has(key)&&v.has(key)&&!v.isNull(key))latest.put(key,v.get(key));if(latest.length()==currentKeys.length)break;}JSONObject current=new JSONObject();for(String key:currentKeys)current.put(key,latest.has(key)?latest.get(key):JSONObject.NULL);mergeCurrentMqtt(current);
        JSONArray sys=new JSONArray();for(int i=0;i<sites.length();i++){JSONObject s=sites.optJSONObject(i);if(s!=null)sys.put(new JSONObject().put("id",first(s,"site_id","id")).put("name",firstOr(s,"Solarbank-System "+(i+1),"site_name","name","home_name")));}
        JSONObject hist=new JSONObject();for(String key:new String[]{"pvW","homeW","batteryW","gridW","soc","l1W","l2W","l3W","totalW","v1","v2","v3","mystromW","bluettiSoc","bluettiDcInputW","bluettiAcInputW","bluettiAcOutputW","bluettiDcOutputW"})hist.put(key,series(filtered,key));JSONArray plugs=new JSONArray();for(int i=0;i<deviceDataLength();i++){JSONObject d=deviceAt(i);if(isPlug(d)){String sn=d.optString("serial");JSONObject entry=new JSONObject().put("serial",sn).put("name",d.optString("name")).put("powerW",d.opt("powerW")).put("voltageV",d.opt("voltageV")).put("currentA",d.opt("currentA")).put("energyKWh",d.opt("energyKWh")).put("history",series(filtered,"plug:"+sn));plugs.put(entry);}}
        return new JSONObject().put("authenticated",cloud!=null).put("status",status).put("nickname",nickname).put("siteId",siteId).put("sites",sys).put("values",current).put("devices",deviceSnapshot()).put("plugs",plugs).put("bluetti",bluettiReading).put("history",hist).put("updatedAt",lastUpdate).put("mystromIp",local.get("mystrom_ip","")).put("mqttReceivedAt",prefs.getString("mqtt_last_received",""));}
    private static boolean isOtherSample(JSONObject values){if(values==null)return false;return values.has("mystromW")||values.has("bluettiSoc")||values.has("bluettiDcInputW");}

    private void mergeCurrentMqtt(JSONObject current){JSONObject bank=scene.optJSONObject("solarbank_info"),grid=scene.optJSONObject("grid_info");if(bank==null)bank=new JSONObject();if(grid==null)grid=new JSONObject();JSONObject liveBank=findMqtt("AE103"),liveMeter=findMqtt("AE1X0");putFinite(current,"pvW",liveBank==null?Double.NaN:num(liveBank,"photovoltaic_power"));putFinite(current,"batteryW",liveBank==null?Double.NaN:num(liveBank,"battery_power_signed"));putFinite(current,"homeW",liveBank==null?Double.NaN:num(liveBank,"home_demand"));double pvInputs=0;boolean hasPvInput=false;for(int i=1;i<=4;i++){double w=liveBank==null?Double.NaN:num(liveBank,"pv_"+i+"_power");if(!Double.isFinite(w))w=num(current,"pv"+i+"W");if(Double.isFinite(w)&&w>=0&&w<=5000){pvInputs+=w;hasPvInput=true;}putFinite(current,"pv"+i+"W",w);}if(hasPvInput)putFinite(current,"pvW",pvInputs);if(liveMeter!=null){for(int i=1;i<=3;i++){putFinite(current,"l"+i+"W",num(liveMeter,"grid_power_signed_l"+i));putFinite(current,"v"+i,num(liveMeter,"voltage_l"+i));putFinite(current,"a"+i,num(liveMeter,"current_l"+i));}putFinite(current,"gridW",num(liveMeter,"grid_power_signed"));}double phase=0;boolean hasPhase=false;for(int i=1;i<=3;i++){double w=current.optDouble("l"+i+"W",Double.NaN);if(Double.isFinite(w)){phase+=w;hasPhase=true;}}if(hasPhase)putFinite(current,"totalW",phase);else if(liveMeter!=null)putFinite(current,"totalW",num(liveMeter,"grid_power_signed"));if(Double.isFinite(num(current,"mystromW"))==false){/* latest local sample remains the fallback */}}
    private static void putFinite(JSONObject target,String key,double value){if(Double.isFinite(value))target.put(key,value);}
    private static double findNumeric(Object value,String...names){if(value instanceof JSONObject){JSONObject o=(JSONObject)value;for(String name:names){String wanted=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");var keys=o.keys();while(keys.hasNext()){String key=keys.next();if(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","").equals(wanted)){double n=num(o,key);if(Double.isFinite(n))return n;}}}var keys=o.keys();while(keys.hasNext()){double n=findNumeric(o.opt(keys.next()),names);if(Double.isFinite(n))return n;}}else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){double n=findNumeric(a.opt(i),names);if(Double.isFinite(n))return n;}}return Double.NaN;}

    private JSONArray series(JSONArray list,String key){ArrayList<JSONObject> found=new ArrayList<>();for(int i=0;i<list.length();i++){JSONObject s=list.optJSONObject(i),v=s==null?null:s.optJSONObject("values");if(v!=null&&v.has(key)&&!v.isNull(key))found.add(new JSONObject().put("t",s.optLong("t")).put("v",v.optDouble(key)));}JSONArray out=new JSONArray();int step=Math.max(1,(int)Math.ceil(found.size()/500d));for(int i=0;i<found.size();i+=step)out.put(found.get(i));if(!found.isEmpty()&&(found.size()-1)%step!=0)out.put(found.get(found.size()-1));return out;}
    private JSONArray deviceSnapshot(){JSONArray out=new JSONArray();for(int i=0;i<devices.length();i++){JSONObject d=devices.optJSONObject(i);if(d==null)continue;String sn=first(d,"device_sn","sn");JSONObject live=readMqtt(sn),readings=new JSONObject();var keys=live.keys();while(keys.hasNext()){String key=keys.next();Object value=live.opt(key);if(value instanceof Number&&!key.equals("received_at")&&!key.equals("mqtt_schema_version"))readings.put(key,value);}out.put(new JSONObject().put("serial",sn).put("name",firstOr(d,"Gerät","alias_name","device_name","name","device_pn","product_code")).put("model",first(d,"device_pn","product_code","device_model")).put("isPlug",isPlug(d)).put("powerW",live.has("power")?live.optDouble("power"):JSONObject.NULL).put("voltageV",live.has("voltage")?live.optDouble("voltage"):JSONObject.NULL).put("currentA",live.has("current")?live.optDouble("current"):JSONObject.NULL).put("energyKWh",live.has("output_energy")?live.optDouble("output_energy"):JSONObject.NULL).put("readings",readings).put("updatedAt",live.optLong("received_at")));}return out;}
    private int deviceDataLength(){return devices.length();}private JSONObject deviceAt(int i){JSONArray a=deviceSnapshot();return a.optJSONObject(i)==null?new JSONObject():a.optJSONObject(i);}
    private JSONObject readMqtt(String sn){try{JSONObject o=new JSONObject(prefs.getString("mqtt_device_"+sn,"{}"));long at=o.optLong("received_at",0);return at>0&&System.currentTimeMillis()-at>180000?new JSONObject():o;}catch(Exception e){return new JSONObject();}}
    private JSONObject findMqtt(String pn){for(int i=0;i<devices.length();i++){JSONObject d=devices.optJSONObject(i);if(d!=null&&pn.equalsIgnoreCase(first(d,"device_pn","product_code","device_model")))return readMqtt(first(d,"device_sn","sn"));}return null;}
    private static JSONArray merge(JSONArray a,JSONArray b){LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();for(JSONArray src:new JSONArray[]{a,b})for(int i=0;i<src.length();i++){JSONObject d=src.optJSONObject(i);if(d!=null)all.put(first(d,"device_sn","sn",String.valueOf(i)),d);}JSONArray out=new JSONArray();for(JSONObject d:all.values())out.put(d);return out;}
    private static boolean isPlug(JSONObject d){return d.optBoolean("isPlug",false)||(" "+d.optString("model")).toUpperCase(Locale.ROOT).contains("A17X8")||(" "+d.optString("model")+" "+d.optString("name")).toUpperCase(Locale.ROOT).matches(".*SMART PLUG.*GEN ?2.*");}
    private static String firstSiteId(JSONArray a){JSONObject s=a.optJSONObject(0);return s==null?"":first(s,"site_id","id");}private static int indexOfSite(JSONArray a,String id){for(int i=0;i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&id.equals(first(s,"site_id","id")))return i;}return -1;}
    private static String first(JSONObject o,String...keys){for(String k:keys){String v=o.optString(k,"");if(!v.isBlank()&&!v.equals("null"))return v;}return "";}private static String firstOr(JSONObject o,String fallback,String...keys){String v=first(o,keys);return v.isEmpty()?fallback:v;}
    private static double num(JSONObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.isNull(k)){Object v=o.opt(k);if(v instanceof Number)return ((Number)v).doubleValue();try{return Double.parseDouble(String.valueOf(v));}catch(Exception ignored){}}return Double.NaN;}private static Object finite(double d){return Double.isFinite(d)?d:JSONObject.NULL;}
    private static void copyNumber(JSONObject from,JSONObject to,String a,String b){if(from.has(a)&&!from.isNull(a))to.put(b,from.optDouble(a));}
    private static String safe(Exception e){String s=e.getMessage();return s==null||s.isBlank()?"Verbindungsfehler":s;}
    private static boolean privateIPv4(String ip){String[] p=ip.split("\\.",-1);if(p.length!=4)return false;int[] n=new int[4];try{for(int i=0;i<4;i++){n[i]=Integer.parseInt(p[i]);if(n[i]<0||n[i]>255)return false;}}catch(Exception e){return false;}return n[0]==10||(n[0]==192&&n[1]==168)||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==169&&n[1]==254);}
    private static JSONObject readJson(HttpExchange x)throws Exception{try(InputStream in=x.getRequestBody()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>32768)throw new IllegalArgumentException("Anfrage zu groß.");}return new JSONObject(out.toString(StandardCharsets.UTF_8));}}
    private static void sendJson(HttpExchange x,int code,JSONObject value)throws java.io.IOException{byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,body.length);x.getResponseBody().write(body);}
    private static final class HistoryBucket{final long hour;final String site;final Map<String,Double>sums=new HashMap<>();final Map<String,Long>counts=new HashMap<>();HistoryBucket(long hour,String site){this.hour=hour;this.site=site;}}
    private synchronized void appendSample(JSONObject sample){samples.add(sample);try{Files.createDirectories(historyFile.getParent());Files.writeString(historyFile,sample+"\n",StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);if(samples.size()>45000||Files.size(historyFile)>40L*1024*1024)compactHistory();}catch(Exception ignored){}}
    private synchronized void compactHistory(){long cutoff=System.currentTimeMillis()-48L*60*60*1000,hourMs=60L*60*1000;Map<String,HistoryBucket>buckets=new HashMap<>();ArrayList<JSONObject>recent=new ArrayList<>();for(JSONObject sample:samples){long t=sample.optLong("t");JSONObject values=sample.optJSONObject("values");if(t>=cutoff||values==null){recent.add(sample);continue;}String site=sample.optString("siteId"),bucketHour=String.valueOf(t/hourMs),bucketKey=site+"|"+bucketHour;HistoryBucket b=buckets.computeIfAbsent(bucketKey,k->new HistoryBucket((t/hourMs)*hourMs,site));JSONObject oldCounts=sample.optJSONObject("counts");var keys=values.keys();while(keys.hasNext()){String key=keys.next();Object raw=values.opt(key);if(!(raw instanceof Number))continue;long weight=oldCounts==null?1:oldCounts.optLong(key,1);b.sums.merge(key,((Number)raw).doubleValue()*weight,Double::sum);b.counts.merge(key,weight,Long::sum);}}
        ArrayList<JSONObject>compressed=new ArrayList<>();for(HistoryBucket b:buckets.values()){JSONObject values=new JSONObject(),counts=new JSONObject();for(String key:b.sums.keySet()){long n=b.counts.getOrDefault(key,1L);values.put(key,b.sums.get(key)/Math.max(1,n));counts.put(key,n);}JSONObject sample=new JSONObject().put("t",b.hour+hourMs/2).put("values",values).put("counts",counts);if(!b.site.isEmpty())sample.put("siteId",b.site);compressed.add(sample);}compressed.addAll(recent);compressed.sort(java.util.Comparator.comparingLong(o->o.optLong("t")));samples.clear();samples.addAll(compressed);rewriteHistory();}
    private void rewriteHistory(){try{Files.createDirectories(historyFile.getParent());StringBuilder b=new StringBuilder();for(JSONObject s:samples)b.append(s).append('\n');Files.writeString(historyFile,b,StandardCharsets.UTF_8);}catch(Exception ignored){}}
    private void loadHistory(){try{if(!Files.exists(historyFile))return;var lines=Files.readAllLines(historyFile,StandardCharsets.UTF_8);int start=Math.max(0,lines.size()-60000);for(int i=start;i<lines.size();i++)try{samples.add(new JSONObject(lines.get(i)));}catch(Exception ignored){}if(samples.size()>45000)compactHistory();}catch(Exception ignored){}}
}
