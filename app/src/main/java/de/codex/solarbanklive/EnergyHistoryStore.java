package de.codex.solarbanklive;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/** Local, timestamped energy-flow history retained across app and process restarts. */
final class EnergyHistoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME="solarbank_energy_history.db";
    private static final int DB_VERSION=1;
    private static final long RETENTION_DAYS=30;
    private static volatile EnergyHistoryStore instance;

    static final class TodayData {
        final ArrayList<float[]> points;
        final int count;
        final int latestMinute;
        TodayData(ArrayList<float[]> points,int count,int latestMinute){this.points=points;this.count=count;this.latestMinute=latestMinute;}
    }

    static EnergyHistoryStore get(Context context){
        if(instance==null)synchronized(EnergyHistoryStore.class){if(instance==null)instance=new EnergyHistoryStore(context.getApplicationContext());}
        return instance;
    }

    private EnergyHistoryStore(Context context){super(context,DB_NAME,null,DB_VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE energy_samples (site_id TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, pv_w REAL, home_w REAL, battery_w REAL, grid_w REAL, PRIMARY KEY(site_id,timestamp_ms))");
        db.execSQL("CREATE INDEX energy_samples_time ON energy_samples(timestamp_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}

    synchronized void insert(String siteId,long timestamp,double pv,double home,double battery,double grid){
        if(siteId==null||siteId.isEmpty()||timestamp<=0||(!Double.isFinite(pv)&&!Double.isFinite(home)&&!Double.isFinite(battery)&&!Double.isFinite(grid)))return;
        SQLiteDatabase db=getWritableDatabase();ContentValues row=new ContentValues();row.put("site_id",siteId);row.put("timestamp_ms",timestamp);putFinite(row,"pv_w",pv);putFinite(row,"home_w",home);putFinite(row,"battery_w",battery);putFinite(row,"grid_w",grid);db.insertWithOnConflict("energy_samples",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        long cutoff=System.currentTimeMillis()-RETENTION_DAYS*24L*60L*60L*1000L;db.delete("energy_samples","timestamp_ms<?",new String[]{Long.toString(cutoff)});
    }

    synchronized TodayData readToday(String siteId){
        ArrayList<float[]> points=new ArrayList<>(1440);for(int i=0;i<1440;i++)points.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});
        Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);long startMs=start.getTimeInMillis();Calendar nextDay=(Calendar)start.clone();nextDay.add(Calendar.DAY_OF_MONTH,1);int count=0,latestMinute=-1;SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.query("energy_samples",new String[]{"timestamp_ms","pv_w","home_w","battery_w","grid_w"},"site_id=? AND timestamp_ms>=? AND timestamp_ms<?",new String[]{siteId,Long.toString(startMs),Long.toString(nextDay.getTimeInMillis())},null,null,"timestamp_ms ASC")){
            while(c.moveToNext()){
                Calendar at=Calendar.getInstance();at.setTimeInMillis(c.getLong(0));int minute=at.get(Calendar.HOUR_OF_DAY)*60+at.get(Calendar.MINUTE);if(minute<0||minute>=1440)continue;float[] values=new float[4];for(int i=0;i<4;i++)values[i]=c.isNull(i+1)?Float.NaN:(float)c.getDouble(i+1);points.set(minute,values);count++;latestMinute=Math.max(latestMinute,minute);
            }
        }
        return new TodayData(points,count,latestMinute);
    }

    /** Store a scene from the foreground service so chart samples accrue while the UI is away. */
    static void recordScene(Context context,String siteId,JSONObject scene){
        if(scene==null)return;JSONObject bank=scene.optJSONObject("solarbank_info");if(bank==null)bank=new JSONObject();JSONObject gridInfo=scene.optJSONObject("grid_info");if(gridInfo==null)gridInfo=new JSONObject();SharedPreferences prefs=context.getSharedPreferences("cloud_session",Context.MODE_PRIVATE);
        double pv=number(bank,"total_photovoltaic_power","total_pv_input_power"),home=number(scene,"home_load_power");if(!Double.isFinite(home))home=number(bank,"to_home_load","total_home_load_power");double battery=number(bank,"total_battery_power_signed");if(!Double.isFinite(battery))battery=number(bank,"total_charging_power");if(!Double.isFinite(battery)){JSONArray batteries=bank.optJSONArray("solarbank_list");if(batteries!=null&&batteries.length()>0){JSONObject first=batteries.optJSONObject(0);if(first!=null)battery=number(first,"charging_power");}}
        double gridImport=number(gridInfo,"grid_to_home_power"),gridExport=number(gridInfo,"photovoltaic_to_grid_power");double grid=Double.isFinite(gridImport)||Double.isFinite(gridExport)?(Double.isFinite(gridImport)?gridImport:0)-(Double.isFinite(gridExport)?gridExport:0):Double.NaN;
        long mqttAt=0;try{JSONArray devices=new JSONArray(prefs.getString("mqtt_devices","[]"));for(int i=0;i<devices.length();i++){JSONObject device=devices.optJSONObject(i);if(device==null)continue;String pn=first(device,"device_pn","product_code","device_model");if(!"AE103".equalsIgnoreCase(pn))continue;String sn=first(device,"device_sn","sn");if(sn.isEmpty())continue;JSONObject live=new JSONObject(prefs.getString("mqtt_device_"+sn,"{}"));if(live.length()==0)continue;mqttAt=Math.max(mqttAt,live.optLong("received_at",0));double v=number(live,"photovoltaic_power");if(Double.isFinite(v))pv=v;v=number(live,"battery_power_signed");if(Double.isFinite(v))battery=v;v=number(live,"home_demand");if(Double.isFinite(v))home=v;v=number(live,"grid_power_signed");if(Double.isFinite(v))grid=v;break;}}catch(Exception ignored){}
        // Timestamp the actual background poll so it creates a durable sample even when
        // the cloud payload repeats an older device-side updated_time value.
        long timestamp=System.currentTimeMillis();if(mqttAt>timestamp)timestamp=mqttAt;get(context).insert(siteId,timestamp,pv,home,battery,grid);
    }

    private static void putFinite(ContentValues row,String key,double value){if(Double.isFinite(value))row.put(key,value);else row.putNull(key);}
    private static double number(JSONObject object,String...keys){for(String key:keys){Object raw=object.opt(key);if(raw instanceof Number)return ((Number)raw).doubleValue();if(raw!=null&&raw!=JSONObject.NULL)try{double value=Double.parseDouble(String.valueOf(raw));if(Double.isFinite(value))return value;}catch(Exception ignored){}}return Double.NaN;}
    private static String first(JSONObject object,String...keys){for(String key:keys){String value=object.optString(key,"");if(!value.isEmpty()&&!value.equals("null"))return value;}return "";}
}
