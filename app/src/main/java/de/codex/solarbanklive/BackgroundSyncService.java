package de.codex.solarbanklive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started foreground service that keeps polling the selected SOLIX site. */
public final class BackgroundSyncService extends Service {
    private static final String CHANNEL="solarbank_background_sync";
    private static final int NOTIFICATION_ID=4102;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String siteId="";
    private AnkerCloudClient client;
    private AnkerMqttLiveClient mqttClient;
    private boolean stopped=false,requestRunning=false;
    private long refreshInterval=60_000;
    private final Runnable poll=new Runnable(){@Override public void run(){fetch();}};

    @Override public void onCreate(){super.onCreate();createChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&intent.getBooleanExtra("stop",false)){stopMonitoring();return START_NOT_STICKY;}
        android.content.SharedPreferences prefs=getSharedPreferences("cloud_session",MODE_PRIVATE);
        String requested=intent==null?"":intent.getStringExtra("site_id");
        if(requested!=null&&!requested.isEmpty())siteId=requested;
        if(siteId.isEmpty())siteId=prefs.getString("site_id","");
        refreshInterval=prefs.getInt("refresh_interval",60_000);
        String token=prefs.getString("auth_token","");String userToken=prefs.getString("user_token","");
        if(siteId.isEmpty()||token.isEmpty()||userToken.isEmpty()){stopSelf();return START_NOT_STICKY;}
        client=new AnkerCloudClient(token,userToken);stopped=false;
        startForeground(NOTIFICATION_ID,notification("Anker SOLIX · Aktualisierung: "+intervalLabel()));
        startMqtt();
        handler.removeCallbacks(poll);handler.post(poll);
        return START_STICKY;
    }

    private void startMqtt(){
        if(mqttClient!=null){mqttClient.stop();mqttClient=null;}
        io.execute(()->{
            try{
                JSONObject info=client.getMqttInfo();
                org.json.JSONArray devices=client.getUserDevices();
                org.json.JSONArray bound=client.getBoundDevices();
                java.util.HashSet<String> serials=new java.util.HashSet<>();for(int i=0;i<devices.length();i++){org.json.JSONObject d=devices.optJSONObject(i);if(d!=null)serials.add(d.optString("device_sn",d.optString("sn","")));}
                for(int i=0;i<bound.length();i++){org.json.JSONObject d=bound.optJSONObject(i);if(d==null)continue;String sn=d.optString("device_sn",d.optString("sn",""));if(!serials.contains(sn)){devices.put(d);serials.add(sn);}}
                final org.json.JSONArray liveDevices=devices;
                getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putString("mqtt_devices",liveDevices.toString()).apply();
                mqttClient=new AnkerMqttLiveClient(this,info,liveDevices,text->{handler.post(()->{if(!stopped)updateNotification(text);});});
                mqttClient.start();
            }catch(Exception e){handler.post(()->{if(!stopped)updateNotification("Cloud verbunden · Echtzeitkanal nicht verfügbar");});}
        });
    }

    private void fetch(){
        if(stopped||requestRunning||client==null)return;
        requestRunning=true;
        io.execute(()->{
            JSONObject scene=null;String error=null;
            try{scene=client.getSceneInfo(siteId);}catch(Exception e){error=e.getMessage();}
            final JSONObject result=scene;final String failure=error;
            handler.post(()->{
                requestRunning=false;if(stopped)return;
                if(result!=null){
                    getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putString("last_scene",result.toString()).putString("site_id",siteId).apply();
                    JSONObject bank=result.optJSONObject("solarbank_info");if(bank==null)bank=new JSONObject();
                    String pv=value(bank,"total_photovoltaic_power","total_pv_input_power");
                    updateNotification(pv.isEmpty()?"SOLIX-Daten im Hintergrund aktualisiert":"PV aktuell: "+formatPower(pv)+" · weiter aktiv");
                }else updateNotification(failure==null||failure.isEmpty()?"Warte auf Anker SOLIX · erneuter Versuch in einer Minute":"Cloud derzeit nicht erreichbar · erneuter Versuch in einer Minute");
                handler.postDelayed(poll,refreshInterval);
            });
        });
    }

    private static String value(JSONObject obj,String... keys){for(String key:keys){String v=obj.optString(key,"");if(!v.isEmpty()&&!v.equals("null"))return v;}return "";}
    private String intervalLabel(){if(refreshInterval<=30_000)return "Live (30 s)";if(refreshInterval<=60_000)return "jede Minute";return "alle "+(refreshInterval/60_000)+" Minuten";}
    private static String formatPower(String raw){try{return String.format(Locale.GERMANY,"%.0f W",Double.parseDouble(raw));}catch(Exception e){return raw+" W";}}
    private void stopMonitoring(){stopped=true;handler.removeCallbacks(poll);if(mqttClient!=null)mqttClient.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Solarbank Hintergrunddaten",NotificationManager.IMPORTANCE_LOW);c.setDescription("Zeigt an, wenn Solarbank Live im Hintergrund aktualisiert.");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){
        Class<?> landing=BuildConfig.SIMPLE_OVERVIEW?SimpleOverviewActivity.class:MainActivity.class;Intent open=new Intent(this,landing);PendingIntent openPending=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,BackgroundSyncService.class).putExtra("stop",true);PendingIntent stopPending=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Solarbank Live läuft").setContentText(text).setContentIntent(openPending).setOngoing(true).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_media_pause,"Beenden",stopPending);
        return b.build();
    }
    private void updateNotification(String text){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(text));}
    @Override public void onTaskRemoved(Intent rootIntent){super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){stopped=true;handler.removeCallbacks(poll);if(mqttClient!=null)mqttClient.stop();io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
