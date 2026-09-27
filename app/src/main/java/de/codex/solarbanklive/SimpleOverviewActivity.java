package de.codex.solarbanklive;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A compact, device-grouped live readings companion application. */
public final class SimpleOverviewActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS=92,REQUEST_BLUETOOTH=74;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService localIo=Executors.newSingleThreadExecutor();
    private final ArrayList<String> siteIds=new ArrayList<>(),siteLabels=new ArrayList<>();
    private final LinkedHashMap<String,JSONObject> devices=new LinkedHashMap<>();
    private final Map<String,TextView> deviceValueViews=new HashMap<>();
    private final Map<String,TextView> deviceStatusViews=new HashMap<>();
    private final Map<String,String> siteNames=new HashMap<>();
    private boolean active=true,scrolling=false,loginBusy=false;
    private boolean myStromBusy=false;
    private final Runnable scrollIdle=new Runnable(){@Override public void run(){scrolling=false;}};
    private long lastStructureUpdate=0;
    private String currentSite="";
    private AnkerCloudClient client;
    private AnkerMqttLiveClient mqttClient;
    private BluettiBleClient bluetti;
    private JSONObject myStromReport=new JSONObject();
    private String bluettiSummary="Noch nicht verbunden";
    private int INK=Color.rgb(235,243,250),MUTED=Color.rgb(153,174,194),BG=Color.rgb(7,16,28),CARD=Color.rgb(15,31,48),GREEN=Color.rgb(44,218,153),BLUE=Color.rgb(59,164,255),YELLOW=Color.rgb(255,196,50),BORDER=Color.rgb(38,63,86);
    private LinearLayout root,content;
    private ScrollView scroll;
    private TextView status,updated,systemValues,myStromValues,bluettiValues;
    private Spinner systems;
    private EditText email,password,myStromIp;
    private Button loginButton,bluettiButton;
    private View authCard;
    private final Runnable ticker=new Runnable(){@Override public void run(){if(!active)return;refreshReadings();handler.postDelayed(this,1000);}};
    private final Runnable myStromTicker=new Runnable(){@Override public void run(){if(!active)return;String ip=prefs().getString("mystrom_ip","").trim();if(!ip.isEmpty()&&!myStromBusy){myStromBusy=true;localIo.execute(()->{try{JSONObject result=readMyStrom(ip);runOnUiThread(()->{myStromBusy=false;if(!active)return;myStromReport=result;if(myStromValues!=null)myStromValues.setText(formatReadings(result,true));});}catch(Exception ignored){runOnUiThread(()->myStromBusy=false);} });}handler.postDelayed(this,2000);}};

    @Override public void onCreate(Bundle state){super.onCreate(state);darkMode();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);buildUi();restore();handler.postDelayed(ticker,300);handler.postDelayed(myStromTicker,500);}
    @Override protected void onDestroy(){active=false;handler.removeCallbacks(ticker);handler.removeCallbacks(myStromTicker);if(bluetti!=null)bluetti.disconnect();if(mqttClient!=null)mqttClient.stop();io.shutdownNow();localIo.shutdownNow();super.onDestroy();}
    private android.content.SharedPreferences prefs(){return getSharedPreferences("cloud_session",MODE_PRIVATE);}
    private void darkMode(){getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),BORDER);return d;}
    private TextView label(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void buildUi(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(14),dp(12),dp(14),dp(8));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView icon=label("☀",30,YELLOW,true);header.addView(icon,lp(42,46));LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);brand.addView(label("Sonnenbank Übersicht",20,INK,true));status=label("●  Live-Geräteübersicht",11,GREEN,false);brand.addView(status);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));root.addView(header,lp(-1,52));
        systems=new Spinner(this);systems.setVisibility(View.GONE);LinearLayout.LayoutParams slp=lp(-1,44);slp.topMargin=dp(7);root.addView(systems,slp);
        scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.setBackgroundColor(BG);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(5),0,dp(15));scroll.addView(content);
        updated=label("Warte auf Messwerte …",10,MUTED,false);LinearLayout.LayoutParams ulp=lp(-1,-2);ulp.topMargin=dp(4);content.addView(updated,ulp);
        authCard=section("Anker SOLIX anmelden",content);email=new EditText(this);email.setSingleLine(true);email.setHint("E-Mail-Adresse");authCardChild(email);password=new EditText(this);password.setSingleLine(true);password.setHint("Passwort");password.setInputType(129);authCardChild(password);loginButton=new Button(this);loginButton.setText("Anmelden und Geräte laden");loginButton.setAllCaps(false);loginButton.setTextColor(Color.WHITE);loginButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));authCardChild(loginButton);loginButton.setOnClickListener(v->login());
        systemValues=makeSection("SOLARBANK-SYSTEM",content);
        myStromValues=makeSection("MYSTROM · LOKALE STECKDOSE",content);
        LinearLayout myCard=(LinearLayout)section("MyStrom WLAN-IP",content);myStromIp=new EditText(this);myStromIp.setSingleLine(true);myStromIp.setHint("z. B. 192.168.1.50");myStromIp.setText(prefs().getString("mystrom_ip",""));addCardChild(myCard,myStromIp);Button saveIp=new Button(this);saveIp.setText("IP speichern");saveIp.setAllCaps(false);addCardChild(myCard,saveIp);saveIp.setOnClickListener(v->{String value=myStromIp.getText().toString().trim();prefs().edit().putString("mystrom_ip",value).apply();myStromIpValuesStatus(value);});
        LinearLayout bluettiCard=(LinearLayout)section("BLUETTI AC200 MAX · BLUETOOTH",content);bluettiValues=label(bluettiSummary,13,INK,false);addCardChild(bluettiCard,bluettiValues);bluettiButton=new Button(this);bluettiButton.setText("Bluetti per Bluetooth verbinden");bluettiButton.setAllCaps(false);bluettiButton.setTextColor(Color.WHITE);bluettiButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));addCardChild(bluettiCard,bluettiButton);bluettiButton.setOnClickListener(v->connectBluetti());
        TextView deviceHeader=label("ALLE ANKER-GERÄTE · LIVE",12,MUTED,true);LinearLayout.LayoutParams dhp=lp(-1,-2);dhp.topMargin=dp(17);dhp.bottomMargin=dp(4);content.addView(deviceHeader,dhp);
        scroll.setOnScrollChangeListener((View v,int x,int y,int ox,int oy)->{scrolling=true;handler.removeCallbacks(scrollIdle);handler.postDelayed(scrollIdle,700);});scroll.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)scrolling=true;if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(scrollIdle);handler.postDelayed(scrollIdle,700);}return false;});
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(14),insets.getSystemWindowInsetTop()+dp(8),dp(14),insets.getSystemWindowInsetBottom()+dp(8));return insets;});setContentView(root);
        systems.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(pos>=0&&pos<siteIds.size()&&!siteIds.get(pos).equals(currentSite))loadSite(siteIds.get(pos));}@Override public void onNothingSelected(android.widget.AdapterView<?> p){}});
    }
    private View section(String title,LinearLayout parent){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(11),dp(12),dp(11));card.setBackground(shape(CARD,16));LinearLayout.LayoutParams cp=lp(-1,-2);cp.topMargin=dp(8);parent.addView(card,cp);TextView heading=label(title,12,GREEN,true);card.addView(heading,lp(-1,-2));return card;}
    private TextView makeSection(String title,LinearLayout parent){LinearLayout card=(LinearLayout)section(title,parent);TextView value=label("Noch keine Messwerte",13,INK,false);LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(8);card.addView(value,p);return value;}
    private void authCardChild(View v){if(authCard instanceof LinearLayout){LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(7);((LinearLayout)authCard).addView(v,p);}}
    private void addCardChild(LinearLayout card,View v){LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(7);card.addView(v,p);}
    private void myStromIpValuesStatus(String ip){if(status!=null)status.setText(ip.isEmpty()?"●  MyStrom-IP entfernt":"●  Verbunden · lese MyStrom im lokalen WLAN");}

    private void login(){if(loginBusy)return;String mail=email.getText().toString().trim(),pass=password.getText().toString();if(mail.isEmpty()||pass.isEmpty()){status.setText("E-Mail und Passwort eingeben");return;}loginBusy=true;loginButton.setEnabled(false);status.setText("●  Anmeldung bei Anker SOLIX …");io.execute(()->{try{AnkerCloudClient c=new AnkerCloudClient();AnkerCloudClient.Result r=c.loginAndGetSites(mail,pass);JSONArray a=new JSONArray(),b=new JSONArray();try{a=c.getUserDevices();}catch(Exception ignored){}try{b=c.getBoundDevices();}catch(Exception ignored){}JSONArray ua=a,ub=b;runOnUiThread(()->{if(!active)return;loginBusy=false;loginButton.setEnabled(true);password.setText("");authCard.setVisibility(View.GONE);client=c;prefs().edit().putString("auth_token",c.authToken()).putString("user_token",c.userToken()).putString("nickname",r.nickname).apply();status.setText("●  Angemeldet als "+r.nickname);acceptSites(r.sites,ua,ub);});}catch(Exception e){runOnUiThread(()->{loginBusy=false;loginButton.setEnabled(true);status.setText("Anmeldung fehlgeschlagen · "+safe(e));});}});}
    private void restore(){String token=prefs().getString("auth_token",""),user=prefs().getString("user_token","");if(token.isEmpty()||user.isEmpty())return;authCard.setVisibility(View.GONE);String nickname=prefs().getString("nickname","Anker SOLIX");status.setText("●  Sitzung wird geladen …");client=new AnkerCloudClient(token,user);io.execute(()->{try{AnkerCloudClient.Result r=client.getSites(nickname);JSONArray a=new JSONArray(),b=new JSONArray();try{a=client.getUserDevices();}catch(Exception ignored){}try{b=client.getBoundDevices();}catch(Exception ignored){}JSONArray ua=a,ub=b;runOnUiThread(()->acceptSites(r.sites,ua,ub));}catch(Exception e){runOnUiThread(()->status.setText("Gespeicherte Sitzung · Cloud nicht erreichbar"));}});}
    private void acceptSites(JSONArray sites,JSONArray userDevices,JSONArray boundDevices){siteIds.clear();siteLabels.clear();siteNames.clear();for(int i=0;i<sites.length();i++){JSONObject s=sites.optJSONObject(i);if(s==null)continue;JSONObject info=s.optJSONObject("site_info");String id=s.optString("site_id","");if(id.isEmpty()&&info!=null)id=info.optString("site_id","");if(id.isEmpty())continue;String name=s.optString("site_name","");if(name.isEmpty()&&info!=null)name=info.optString("site_name","");if(name.isEmpty())name="SOLIX-System "+(siteIds.size()+1);siteIds.add(id);siteLabels.add(name);siteNames.put(id,name);}prefs().edit().putString("overview_user_devices",userDevices.toString()).putString("overview_bound_devices",boundDevices.toString()).apply();if(siteIds.isEmpty()){status.setText("Angemeldet · kein SOLIX-System gefunden");return;}systems.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,siteLabels));systems.setVisibility(View.VISIBLE);String selected=prefs().getString("site_id","");int pos=siteIds.indexOf(selected);if(pos<0)pos=0;systems.setSelection(pos);loadSite(siteIds.get(pos));startMonitoring(siteIds.get(pos));}
    private void loadSite(String site){if(client==null||site==null||site.isEmpty())return;currentSite=site;prefs().edit().putString("site_id",site).apply();status.setText("●  Lade "+siteNames.getOrDefault(site,"System")+" …");io.execute(()->{try{JSONObject data=client.getSceneInfo(site);prefs().edit().putString("last_scene",data.toString()).putString("site_id",site).apply();runOnUiThread(()->{status.setText("●  "+siteNames.getOrDefault(site,"System")+" · Live-Kanal aktiv");refreshReadings();});}catch(Exception e){runOnUiThread(()->status.setText("Cloud-Systemdaten gerade nicht erreichbar"));}});}
    private void startMonitoring(String site){if(site==null||site.isEmpty())return;if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);return;}Intent service=new Intent(this,BackgroundSyncService.class).putExtra("site_id",site);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_NOTIFICATIONS){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startMonitoring(currentSite);else status.setText("●  Live bleibt aktiv solange die Übersicht geöffnet ist");}if(requestCode==REQUEST_BLUETOOTH){boolean ok=grantResults.length>0;for(int g:grantResults)ok&=g==PackageManager.PERMISSION_GRANTED;if(ok)connectBluetti();else bluettiSummary="Bluetooth-Freigabe fehlt";}}

    private void refreshReadings(){if(!active)return;android.content.SharedPreferences p=prefs();String raw=p.getString("last_scene","");if(!raw.isEmpty()){try{JSONObject scene=new JSONObject(raw);updateSystem(scene);long newest=latestMqtt(p);updated.setText(newest>0?(System.currentTimeMillis()-newest<=30_000?"MQTT live · ":"MQTT zuletzt · ")+date(newest):"Letzter Cloudstand · "+date(System.currentTimeMillis()));}catch(Exception ignored){}}
        JSONArray list=collectDevices(p,raw);String signature=signature(list);if((lastStructureUpdate==0||!signature.equals(lastSignature))&&!scrolling){renderDeviceCards(list,p,raw);lastSignature=signature;lastStructureUpdate=SystemClock.uptimeMillis();}else if(SystemClock.uptimeMillis()-lastStructureUpdate>15_000&&!scrolling){renderDeviceCards(list,p,raw);lastSignature=signature;lastStructureUpdate=SystemClock.uptimeMillis();}else updateDeviceValues(p);
        if(myStromValues!=null&&myStromReport.length()>0)myStromValues.setText(formatReadings(myStromReport,true));
    }
    private String lastSignature="";
    private void updateSystem(JSONObject scene){try{JSONObject bank=scene.optJSONObject("solarbank_info");if(bank==null)bank=new JSONObject();JSONObject grid=scene.optJSONObject("grid_info");if(grid==null)grid=new JSONObject();JSONArray known=prefs().getString("mqtt_devices","[]").isEmpty()?new JSONArray():new JSONArray(prefs().getString("mqtt_devices","[]"));for(int i=0;i<known.length();i++){JSONObject d=known.optJSONObject(i);if(d==null||!"AE103".equalsIgnoreCase(firstNon(d,"device_pn","product_code","device_model")))continue;String sn=firstNon(d,"device_sn","sn");JSONObject live=new JSONObject(prefs().getString("mqtt_device_"+sn,"{}"));if(live.has("photovoltaic_power"))bank.put("total_photovoltaic_power",live.opt("photovoltaic_power"));if(live.has("battery_power_signed"))bank.put("total_battery_power_signed",live.opt("battery_power_signed"));if(live.has("home_demand"))scene.put("home_load_power",live.opt("home_demand"));if(live.has("grid_power_signed")){double g=live.optDouble("grid_power_signed");grid.put("grid_to_home_power",Math.max(0,g));grid.put("photovoltaic_to_grid_power",Math.max(0,-g));}break;}JSONObject first=bank.optJSONArray("solarbank_list")!=null?bank.optJSONArray("solarbank_list").optJSONObject(0):null;if(first==null)first=new JSONObject();String[] pairs={"Solarleistung|"+firstNon(bank,"total_photovoltaic_power","total_pv_input_power"),"Akku-Ladestand|"+value(bank,"total_battery_power"),"Akkuleistung ±|"+firstNon(bank,"total_battery_power_signed","total_charging_power","total_discharging_power"),"Hausverbrauch|"+firstNon(scene,"home_load_power","total_home_load_power"),"Netzbezug|"+value(grid,"grid_to_home_power"),"Netzeinspeisung|"+value(grid,"photovoltaic_to_grid_power")};StringBuilder b=new StringBuilder();for(String pair:pairs){String[] part=pair.split("\\|",2);if(part.length==2&&!part[1].isEmpty())b.append(part[0]).append("   ").append(readable(part[0],part[1])).append('\n');}systemValues.setText(b.length()==0?"Keine aktuellen Systemmesswerte":b.toString().trim());}catch(Exception ignored){}}
    private JSONArray collectDevices(android.content.SharedPreferences p,String raw){LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();try{merge(new JSONArray(p.getString("mqtt_devices","[]")),all);merge(new JSONArray(p.getString("overview_user_devices","[]")),all);merge(new JSONArray(p.getString("overview_bound_devices","[]")),all);if(!raw.isEmpty())walk(new JSONObject(raw),all);}catch(Exception ignored){}JSONArray out=new JSONArray();for(JSONObject d:all.values())out.put(d);return out;}
    private void merge(JSONArray arr,Map<String,JSONObject> dest){for(int i=0;i<arr.length();i++){JSONObject d=arr.optJSONObject(i);if(d!=null)add(d,dest);}}
    private void walk(Object node,Map<String,JSONObject> dest){if(node instanceof JSONObject){JSONObject o=(JSONObject)node;if(!o.optString("device_sn","").isEmpty())add(o,dest);Iterator<String> i=o.keys();while(i.hasNext()){String k=i.next();Object child=o.opt(k);if(child instanceof JSONObject||child instanceof JSONArray)walk(child,dest);}}else if(node instanceof JSONArray){JSONArray a=(JSONArray)node;for(int i=0;i<a.length();i++)walk(a.opt(i),dest);}}
    private void add(JSONObject src,Map<String,JSONObject> dest){String sn=firstNon(src,"device_sn","sn");if(sn.isEmpty())return;try{JSONObject target=dest.containsKey(sn)?new JSONObject(dest.get(sn).toString()):new JSONObject();Iterator<String> i=src.keys();while(i.hasNext()){String k=i.next();if(!target.has(k)||target.isNull(k))target.put(k,src.opt(k));}dest.put(sn,target);}catch(Exception ignored){}}
    private String signature(JSONArray list){StringBuilder b=new StringBuilder();for(int i=0;i<list.length();i++){JSONObject d=list.optJSONObject(i);if(d!=null)b.append(firstNon(d,"device_sn","sn")).append(':').append(firstNon(d,"device_pn","product_code","device_model")).append(';');}return b.toString();}
    private void renderDeviceCards(JSONArray list,android.content.SharedPreferences p,String scene){
        deviceValueViews.clear();deviceStatusViews.clear();devices.clear();for(int i=0;i<list.length();i++){JSONObject d=list.optJSONObject(i);if(d!=null)devices.put(firstNon(d,"device_sn","sn"),d);}
        for(int i=0;i<content.getChildCount();i++){View child=content.getChildAt(i);if("device_cards_start".equals(child.getTag())){content.removeViews(i,content.getChildCount()-i);break;}}
        View marker=new View(this);marker.setTag("device_cards_start");content.addView(marker,lp(-1,1));LinkedHashMap<String,ArrayList<String>> groups=new LinkedHashMap<>();groups.put("Solarbank & Akkus",new ArrayList<>());groups.put("Smart Meter Gen 2",new ArrayList<>());groups.put("Smart Plug Gen 2",new ArrayList<>());groups.put("Weitere Anker-Geräte",new ArrayList<>());
        for(String sn:devices.keySet()){JSONObject d=devices.get(sn);String model=firstNon(d,"device_pn","product_code","device_model","device_name","alias_name").toUpperCase(Locale.ROOT);String group=model.contains("A17X8")||model.contains("SMART PLUG")?"Smart Plug Gen 2":model.contains("AE103")||model.contains("AE1X")||model.contains("SMART METER")?"Smart Meter Gen 2":model.contains("SOLARBANK")||model.contains("E5000")||model.contains("BP5000")||model.contains("BP2700")?"Solarbank & Akkus":"Weitere Anker-Geräte";groups.get(group).add(sn);}
        for(Map.Entry<String,ArrayList<String>> group:groups.entrySet()){if(group.getValue().isEmpty())continue;TextView h=label(group.getKey().toUpperCase(Locale.ROOT)+" · "+group.getValue().size(),11,MUTED,true);LinearLayout.LayoutParams hp=lp(-1,-2);hp.topMargin=dp(12);hp.bottomMargin=dp(3);content.addView(h,hp);for(String sn:group.getValue())addDeviceCard(devices.get(sn),sn,p);}
        if(devices.isEmpty()){TextView empty=label("Noch keine Geräte in der Cloud-Liste.",13,MUTED,false);content.addView(empty,lp(-1,-2));}
        updateDeviceValues(p);
    }
    private void addDeviceCard(JSONObject d,String sn,android.content.SharedPreferences p){LinearLayout card=(LinearLayout)section(firstNon(d,"alias_name","device_name","name","device_pn","product_code","Anker-Gerät"),content);TextView state=label("● Status wird gelesen",10,MUTED,false);card.addView(state,lp(-1,-2));deviceStatusViews.put(sn,state);TextView values=label("Warte auf Live-Messwerte …",12,INK,false);values.setLineSpacing(dp(2),1f);LinearLayout.LayoutParams vp=lp(-1,-2);vp.topMargin=dp(5);card.addView(values,vp);deviceValueViews.put(sn,values);}
    private void updateDeviceValues(android.content.SharedPreferences p){for(String sn:deviceValueViews.keySet()){JSONObject d=devices.get(sn);if(d==null)continue;JSONObject live=new JSONObject();try{live=new JSONObject(p.getString("mqtt_device_"+sn,"{}"));}catch(Exception ignored){}ArrayList<String> rows=new ArrayList<>();collectMetrics(live,rows,isPlug(d));if(rows.isEmpty())collectMetrics(d,rows,isPlug(d));TextView value=deviceValueViews.get(sn);if(value!=null)value.setText(rows.isEmpty()?"Keine Live-Messwerte geliefert":join(rows));long received=live.optLong("received_at",0);String online=firstNon(d,"wifi_online","status","online").toLowerCase(Locale.ROOT);boolean isOnline=online.equals("1")||online.equals("true")||online.equals("online")||online.equals("connected");String freshness=received<=0?"":(System.currentTimeMillis()-received<=30_000?"Live · ":"Zuletzt · ")+date(received);TextView state=deviceStatusViews.get(sn);if(state!=null)state.setText(!freshness.isEmpty()?"● "+freshness:isOnline?"● Online · warte auf MQTT":"● Noch kein Live-Wert");}}
    private boolean isPlug(JSONObject d){String model=firstNon(d,"device_pn","product_code","device_model","device_name","alias_name").toUpperCase(Locale.ROOT);return model.contains("A17X8")||model.contains("SMART PLUG");}
    private void collectMetrics(JSONObject object,ArrayList<String> out,boolean plug){Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();Object val=object.opt(key);if(val instanceof JSONObject){collectMetrics((JSONObject)val,out,plug);continue;}if(val instanceof JSONArray){JSONArray a=(JSONArray)val;for(int i=0;i<a.length();i++){JSONObject child=a.optJSONObject(i);if(child!=null)collectMetrics(child,out,plug);}continue;}if(val==null||JSONObject.NULL.equals(val))continue;String normalized=key.toLowerCase(Locale.ROOT).replace("_","").replace("-","");if(normalized.contains("receivedat")||normalized.equals("timestamp")||normalized.equals("sn")||normalized.contains("serial")||normalized.contains("deviceid")||normalized.contains("siteid")||normalized.equals("updatedtime"))continue;String metric=metric(key,String.valueOf(val));if(metric!=null&&!containsLabel(out,metric)){out.add(metric);continue;}if(plug&&(normalized.contains("power")||normalized.contains("volt")||normalized.contains("current")||normalized.contains("energy")||normalized.contains("temperature")||normalized.contains("frequency")||normalized.contains("soc")||normalized.contains("percent"))&&val instanceof Number){out.add(prettyKey(key)+"  "+val);}}}
    private String metric(String rawKey,String raw){String k=rawKey.toLowerCase(Locale.ROOT).replace("_","").replace("-","");double n;try{n=Double.parseDouble(raw);}catch(Exception e){if(k.contains("relay")||k.contains("switch")){if(raw.equalsIgnoreCase("true")||raw.equals("1")||raw.equalsIgnoreCase("on"))return "Schaltzustand  Ein";if(raw.equalsIgnoreCase("false")||raw.equals("0")||raw.equalsIgnoreCase("off"))return "Schaltzustand  Aus";}return null;}
        if(k.matches("pv[1-4]power")||k.matches("solarpower[1-4]")){String channel=k.matches("pv[1-4]power")?k.substring(2,3):k.substring(k.length()-1);return "PV "+channel+" Leistung  "+(n<0||n>5000?"Ungültiger Live-Wert":formatPower(n));}
        if(k.equals("totalbatterypower")||k.equals("batterysoc")||k.equals("soc")||k.contains("batterypercent")||k.equals("stateofcharge")){if(n>=0&&n<=1)n*=100;return "Ladestand  "+String.format(Locale.GERMANY,"%.0f %%",n);}
        if(k.contains("voltage")||k.endsWith("volt"))return prettyKey(rawKey)+"  "+String.format(Locale.GERMANY,"%.1f V",n);
        if(k.contains("current")||k.endsWith("amp"))return prettyKey(rawKey)+"  "+String.format(Locale.GERMANY,"%.2f A",n);
        if(k.contains("temperature")||k.equals("temp"))return "Temperatur  "+String.format(Locale.GERMANY,"%.1f °C",n);
        if(k.contains("energy")||k.contains("consumption"))return prettyKey(rawKey)+"  "+String.format(Locale.GERMANY,"%.2f %s",n,k.contains("wh")&&!k.contains("kwh")?"Wh":"kWh");
        if(k.contains("powerfactor"))return "Leistungsfaktor  "+String.format(Locale.GERMANY,"%.2f",n);
        if(k.contains("frequency"))return "Frequenz  "+String.format(Locale.GERMANY,"%.1f Hz",n);
        if(k.contains("percent")||k.contains("percentage"))return prettyKey(rawKey)+"  "+String.format(Locale.GERMANY,"%.0f %%",n);
        if(k.contains("runtime")||k.contains("duration")||k.contains("uptime")||k.contains("remainingtime"))return prettyKey(rawKey)+"  "+String.format(Locale.GERMANY,"%.0f s",n);
        if(k.contains("rssi"))return "Signalstärke  "+String.format(Locale.GERMANY,"%.0f dBm",n);
        if(k.contains("power")||k.contains("watt"))return prettyKey(rawKey)+"  "+formatPower(n);
        return null;
    }
    private boolean containsLabel(ArrayList<String> rows,String value){String label=value.split("  ",2)[0];for(String row:rows)if(row.startsWith(label+"  "))return true;return false;}
    private String formatReadings(JSONObject object,boolean plug){ArrayList<String> rows=new ArrayList<>();collectMetrics(object,rows,plug);return rows.isEmpty()?"Warte auf lokale Messwerte …":join(rows);}
    private String join(ArrayList<String> values){StringBuilder s=new StringBuilder();for(String row:values){if(s.length()>0)s.append('\n');s.append("•  ").append(row);}return s.toString();}
    private String prettyKey(String key){String s=key.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').replace('-',' ').trim();if(s.isEmpty())return "Messwert";return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    private String firstNon(JSONObject o,String... keys){for(String k:keys){String v=o.optString(k,"");if(!v.isEmpty()&&!v.equals("null"))return v;}return "";}
    private String value(JSONObject o,String k){String v=o.optString(k,"");if(v.isEmpty()||v.equals("null"))return "";return readable(k,v);}
    private String firstNon(JSONObject o,String k1,String k2,String k3){return firstNon(o,new String[]{k1,k2,k3});}
    private String firstNon(JSONObject o,String k1,String k2,String k3,String k4){return firstNon(o,new String[]{k1,k2,k3,k4});}
    private String firstNon(JSONObject o,String k1,String k2,String k3,String k4,String k5){return firstNon(o,new String[]{k1,k2,k3,k4,k5});}
    private String firstNon(JSONObject o,String k1,String k2){return firstNon(o,new String[]{k1,k2});}
    private String firstNon(JSONObject o,String k1,String k2,String k3,String k4,String k5,String k6){return firstNon(o,new String[]{k1,k2,k3,k4,k5,k6});}
    private String firstNon(JSONObject o,String k1,String k2,String k3,String k4,String k5,String k6,String k7){return firstNon(o,new String[]{k1,k2,k3,k4,k5,k6,k7});}
    private String readable(String key,String raw){try{double n=Double.parseDouble(raw);String k=key.toLowerCase(Locale.ROOT);if(k.contains("solarleistung")||k.contains("verbrauch")||k.contains("netz")||k.contains("akkuleistung"))return formatPower(n);if(k.contains("akku-ladestand")){if(n>=0&&n<=1)n*=100;return String.format(Locale.GERMANY,"%.0f %%",n);}return String.format(Locale.GERMANY,"%.1f",n);}catch(Exception ignored){return raw;}}
    private String formatPower(double w){return Math.abs(w)>=1000?String.format(Locale.GERMANY,"%.2f kW",w/1000):String.format(Locale.GERMANY,"%.0f W",w);}
    private String date(long millis){return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(new Date(millis));}
    private long latestMqtt(android.content.SharedPreferences p){long newest=0;for(Map.Entry<String,?> e:p.getAll().entrySet())if(e.getKey().startsWith("mqtt_device_")&&e.getValue() instanceof String)try{newest=Math.max(newest,new JSONObject((String)e.getValue()).optLong("received_at",0));}catch(Exception ignored){}return newest;}
    private JSONObject readMyStrom(String ip)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://"+ip+"/report").openConnection();c.setConnectTimeout(2500);c.setReadTimeout(2500);try{if(c.getResponseCode()!=200)throw new java.io.IOException();try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[512];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>8192)throw new java.io.IOException();}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}}
    private void connectBluetti(){if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQUEST_BLUETOOTH);return;}if(Build.VERSION.SDK_INT<31&&Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_BLUETOOTH);return;}if(bluetti!=null)bluetti.disconnect();bluetti=new BluettiBleClient(this,new BluettiBleClient.Listener(){@Override public void onStatus(String text){if(bluettiValues!=null)bluettiValues.setText(text);}@Override public void onReading(BluettiBleClient.Reading d){bluettiSummary="Ladestand  "+(d.batteryPercent<0?"—":d.batteryPercent+" %")+"\nSolar-Eingang  "+(d.dcInput<0?"—":d.dcInput+" W")+"\nAC-Eingang  "+(d.acInput<0?"—":d.acInput+" W")+"\nAC-Ausgang  "+(d.acOutput<0?"—":d.acOutput+" W")+"\nDC-Ausgang  "+(d.dcOutput<0?"—":d.dcOutput+" W")+"\nAkku-Spannung  "+(Double.isFinite(d.batteryVoltage)?String.format(Locale.GERMANY,"%.2f V",d.batteryVoltage):"—")+"\nAC  "+(Double.isFinite(d.acVoltage)?String.format(Locale.GERMANY,"%.0f V",d.acVoltage):"— V")+" · "+(Double.isFinite(d.acCurrent)?String.format(Locale.GERMANY,"%.1f A",d.acCurrent):"— A")+"\nSolar  "+(Double.isFinite(d.dcVoltage)?String.format(Locale.GERMANY,"%.1f V",d.dcVoltage):"— V")+" · "+(Double.isFinite(d.dcCurrent)?String.format(Locale.GERMANY,"%.2f A",d.dcCurrent):"— A")+"\nErzeugt  "+(Double.isFinite(d.generatedKwh)?String.format(Locale.GERMANY,"%.1f kWh",d.generatedKwh):"—")+"\nAkkupacks  "+(d.packCount<0?"—":d.packCount+(d.packMax>0?" / "+d.packMax:""))+"\nAktualisiert  "+date(d.updatedAt);runOnUiThread(()->{if(bluettiValues!=null)bluettiValues.setText(bluettiSummary);});}});bluetti.scanAndConnect();}
    private String safe(Exception e){return e.getMessage()==null?"Unbekannter Fehler":e.getMessage();}
}
