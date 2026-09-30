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
    private static final int DB_VERSION=3;
    private static final long RETENTION_DAYS=30;
    private static final long ROLLUP_DAYS=400;
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
        createScalarTable(db);createRollupTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<2)createScalarTable(db);if(oldVersion<3){createRollupTables(db);backfillRollups(db);}}
    private void createScalarTable(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS scalar_samples (series_key TEXT NOT NULL, minute_ms INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY(series_key,minute_ms))");db.execSQL("CREATE INDEX IF NOT EXISTS scalar_samples_time ON scalar_samples(minute_ms)");}
    private void createRollupTables(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS energy_daily (site_id TEXT NOT NULL, day_ms INTEGER NOT NULL, pv_sum REAL NOT NULL DEFAULT 0,pv_count INTEGER NOT NULL DEFAULT 0,home_sum REAL NOT NULL DEFAULT 0,home_count INTEGER NOT NULL DEFAULT 0,battery_sum REAL NOT NULL DEFAULT 0,battery_count INTEGER NOT NULL DEFAULT 0,grid_sum REAL NOT NULL DEFAULT 0,grid_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(site_id,day_ms))");db.execSQL("CREATE TABLE IF NOT EXISTS scalar_daily (series_key TEXT NOT NULL,day_ms INTEGER NOT NULL,value_sum REAL NOT NULL DEFAULT 0,value_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(series_key,day_ms))");}

    synchronized void insert(String siteId,long timestamp,double pv,double home,double battery,double grid){
        if(siteId==null||siteId.isEmpty()||timestamp<=0||(!Double.isFinite(pv)&&!Double.isFinite(home)&&!Double.isFinite(battery)&&!Double.isFinite(grid)))return;
        SQLiteDatabase db=getWritableDatabase();long day=dayStart(timestamp);String[] cols={"pv_w","home_w","battery_w","grid_w"};double[] vals={pv,home,battery,grid};db.beginTransaction();try{double[] old=new double[4];boolean exists=false;try(Cursor c=db.query("energy_samples",cols,"site_id=? AND timestamp_ms=?",new String[]{siteId,Long.toString(timestamp)},null,null,null)){if(c.moveToFirst()){exists=true;for(int i=0;i<4;i++)old[i]=c.isNull(i)?Double.NaN:c.getDouble(i);}}ContentValues row=new ContentValues();row.put("site_id",siteId);row.put("timestamp_ms",timestamp);for(int i=0;i<4;i++)putFinite(row,cols[i],vals[i]);db.insertWithOnConflict("energy_samples",null,row,SQLiteDatabase.CONFLICT_REPLACE);for(int i=0;i<4;i++)adjustEnergyDaily(db,siteId,day,i,finite(vals[i])-finite(old[i]),(Double.isFinite(vals[i])?1:0)-(exists&&Double.isFinite(old[i])?1:0));prune(db);db.setTransactionSuccessful();}finally{db.endTransaction();}
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

    synchronized void insertScalar(String seriesKey,long timestamp,double value){
        if(seriesKey==null||seriesKey.isEmpty()||timestamp<=0||!Double.isFinite(value))return;long minuteMs=(timestamp/60000L)*60000L;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{double old=Double.NaN;boolean exists=false;try(Cursor c=db.query("scalar_samples",new String[]{"value"},"series_key=? AND minute_ms=?",new String[]{seriesKey,Long.toString(minuteMs)},null,null,null)){if(c.moveToFirst()){old=c.getDouble(0);exists=true;}}ContentValues row=new ContentValues();row.put("series_key",seriesKey);row.put("minute_ms",minuteMs);row.put("value",value);db.insertWithOnConflict("scalar_samples",null,row,SQLiteDatabase.CONFLICT_REPLACE);adjustScalarDaily(db,seriesKey,dayStart(minuteMs),value-finite(old),exists?0:1);prune(db);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    synchronized TodayData readPeriod(String siteId,String range){return readEnergyPeriod(siteId,range);}
    synchronized TodayData readScalarPeriod(String seriesKey,String range){return readScalarPeriodInternal(seriesKey,range);}
    private TodayData readEnergyPeriod(String siteId,String range){if("Tag".equals(range))return readToday(siteId);int slots="Monat".equals(range)?Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH):12;ArrayList<float[]> out=empty(slots);int count=0,latest=-1;Calendar now=Calendar.getInstance();long start;Calendar from=(Calendar)now.clone();if("Monat".equals(range)){from.set(Calendar.DAY_OF_MONTH,1);}else{from.set(Calendar.MONTH,Calendar.JANUARY);from.set(Calendar.DAY_OF_MONTH,1);}from.set(Calendar.HOUR_OF_DAY,0);from.set(Calendar.MINUTE,0);from.set(Calendar.SECOND,0);from.set(Calendar.MILLISECOND,0);start=from.getTimeInMillis();try(Cursor c=getReadableDatabase().query("energy_daily",new String[]{"day_ms","pv_sum","pv_count","home_sum","home_count","battery_sum","battery_count","grid_sum","grid_count"},"site_id=? AND day_ms>=?",new String[]{siteId,Long.toString(start)},null,null,"day_ms ASC")){double[][] sums=new double[slots][4];int[][] counts=new int[slots][4];while(c.moveToNext()){Calendar d=Calendar.getInstance();d.setTimeInMillis(c.getLong(0));int i="Monat".equals(range)?d.get(Calendar.DAY_OF_MONTH)-1:d.get(Calendar.MONTH);if(i<0||i>=slots)continue;for(int j=0;j<4;j++){sums[i][j]+=c.getDouble(1+j*2);counts[i][j]+=c.getInt(2+j*2);}count++;latest=Math.max(latest,i);}for(int i=0;i<slots;i++)for(int j=0;j<4;j++)if(counts[i][j]>0)out.get(i)[j]=(float)(sums[i][j]/counts[i][j]);}return new TodayData(out,count,latest);}
    private TodayData readScalarPeriodInternal(String key,String range){if("Tag".equals(range))return readTodayScalar(key);int slots="Monat".equals(range)?Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH):12;ArrayList<float[]> out=empty(slots);int count=0,latest=-1;Calendar now=Calendar.getInstance();Calendar from=(Calendar)now.clone();if("Monat".equals(range))from.set(Calendar.DAY_OF_MONTH,1);else{from.set(Calendar.MONTH,Calendar.JANUARY);from.set(Calendar.DAY_OF_MONTH,1);}from.set(Calendar.HOUR_OF_DAY,0);from.set(Calendar.MINUTE,0);from.set(Calendar.SECOND,0);from.set(Calendar.MILLISECOND,0);try(Cursor c=getReadableDatabase().query("scalar_daily",new String[]{"day_ms","value_sum","value_count"},"series_key=? AND day_ms>=?",new String[]{key,Long.toString(from.getTimeInMillis())},null,null,"day_ms ASC")){double[] sums=new double[slots];int[] counts=new int[slots];while(c.moveToNext()){Calendar d=Calendar.getInstance();d.setTimeInMillis(c.getLong(0));int i="Monat".equals(range)?d.get(Calendar.DAY_OF_MONTH)-1:d.get(Calendar.MONTH);if(i>=0&&i<slots){sums[i]+=c.getDouble(1);counts[i]+=c.getInt(2);count++;latest=Math.max(latest,i);}}for(int i=0;i<slots;i++)if(counts[i]>0)out.set(i,new float[]{(float)(sums[i]/counts[i]),Float.NaN,Float.NaN,Float.NaN});}return new TodayData(out,count,latest);}
    private static ArrayList<float[]> empty(int n){ArrayList<float[]> a=new ArrayList<>(n);for(int i=0;i<n;i++)a.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});return a;}
    private static long dayStart(long ms){Calendar c=Calendar.getInstance();c.setTimeInMillis(ms);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private static double finite(double d){return Double.isFinite(d)?d:0;}
    private static void adjustEnergyDaily(SQLiteDatabase db,String site,long day,int i,double ds,int dc){String[] sums={"pv_sum","home_sum","battery_sum","grid_sum"},counts={"pv_count","home_count","battery_count","grid_count"};ContentValues cv=new ContentValues();cv.put("site_id",site);cv.put("day_ms",day);db.insertWithOnConflict("energy_daily",null,cv,SQLiteDatabase.CONFLICT_IGNORE);db.execSQL("UPDATE energy_daily SET "+sums[i]+"="+sums[i]+"+?, "+counts[i]+"=MAX(0,"+counts[i]+"+?) WHERE site_id=? AND day_ms=?",new Object[]{ds,dc,site,day});}
    private static void adjustScalarDaily(SQLiteDatabase db,String key,long day,double ds,int dc){ContentValues cv=new ContentValues();cv.put("series_key",key);cv.put("day_ms",day);db.insertWithOnConflict("scalar_daily",null,cv,SQLiteDatabase.CONFLICT_IGNORE);db.execSQL("UPDATE scalar_daily SET value_sum=value_sum+?, value_count=MAX(0,value_count+?) WHERE series_key=? AND day_ms=?",new Object[]{ds,dc,key,day});}
    private void prune(SQLiteDatabase db){long now=System.currentTimeMillis();db.delete("energy_samples","timestamp_ms<?",new String[]{Long.toString(now-RETENTION_DAYS*86400000L)});db.delete("scalar_samples","minute_ms<?",new String[]{Long.toString(now-RETENTION_DAYS*86400000L)});db.delete("energy_daily","day_ms<?",new String[]{Long.toString(now-ROLLUP_DAYS*86400000L)});db.delete("scalar_daily","day_ms<?",new String[]{Long.toString(now-ROLLUP_DAYS*86400000L)});}
    private void backfillRollups(SQLiteDatabase db){try(Cursor c=db.query("energy_samples",new String[]{"site_id","timestamp_ms","pv_w","home_w","battery_w","grid_w"},null,null,null,null,null)){while(c.moveToNext()){String site=c.getString(0);long day=dayStart(c.getLong(1));for(int i=0;i<4;i++)if(!c.isNull(i+2))adjustEnergyDaily(db,site,day,i,c.getDouble(i+2),1);}}try(Cursor c=db.query("scalar_samples",new String[]{"series_key","minute_ms","value"},null,null,null,null,null)){while(c.moveToNext())adjustScalarDaily(db,c.getString(0),dayStart(c.getLong(1)),c.getDouble(2),1);}}

    synchronized TodayData readTodayScalar(String seriesKey){
        ArrayList<float[]> points=new ArrayList<>(1440);for(int i=0;i<1440;i++)points.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);long startMs=start.getTimeInMillis();Calendar nextDay=(Calendar)start.clone();nextDay.add(Calendar.DAY_OF_MONTH,1);int count=0,latestMinute=-1;SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.query("scalar_samples",new String[]{"minute_ms","value"},"series_key=? AND minute_ms>=? AND minute_ms<?",new String[]{seriesKey,Long.toString(startMs),Long.toString(nextDay.getTimeInMillis())},null,null,"minute_ms ASC")){while(c.moveToNext()){Calendar at=Calendar.getInstance();at.setTimeInMillis(c.getLong(0));int minute=at.get(Calendar.HOUR_OF_DAY)*60+at.get(Calendar.MINUTE);if(minute<0||minute>=1440)continue;points.set(minute,new float[]{(float)c.getDouble(1),Float.NaN,Float.NaN,Float.NaN});count++;latestMinute=Math.max(latestMinute,minute);}}
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
        long timestamp=System.currentTimeMillis();if(mqttAt>timestamp)timestamp=mqttAt;EnergyHistoryStore store=get(context);store.insert(siteId,timestamp,pv,home,battery,grid);double soc=number(bank,"total_battery_power");boolean totalSoc=Double.isFinite(soc);if(!totalSoc){JSONArray batteries=bank.optJSONArray("solarbank_list");if(batteries!=null&&batteries.length()>0){JSONObject first=batteries.optJSONObject(0);if(first!=null)soc=number(first,"battery_soc");}}if(Double.isFinite(soc)){String siteType=context.getSharedPreferences("cloud_session",Context.MODE_PRIVATE).getString("site_type_"+siteId,"").toLowerCase(Locale.ROOT);if(totalSoc&&!siteType.contains("pps")&&soc>=0&&soc<=1)soc*=100;else if(!totalSoc&&soc>0&&soc<1)soc*=100;if(soc>=0&&soc<=100)store.insertScalar("battery:"+siteId,timestamp,soc);}
    }

    static void recordMyStrom(Context context,String ip,double watts){if(ip==null||ip.trim().isEmpty()||!Double.isFinite(watts))return;get(context).insertScalar("mystrom:"+ip.trim(),System.currentTimeMillis(),watts);}

    private static void putFinite(ContentValues row,String key,double value){if(Double.isFinite(value))row.put(key,value);else row.putNull(key);}
    private static double number(JSONObject object,String...keys){for(String key:keys){Object raw=object.opt(key);if(raw instanceof Number)return ((Number)raw).doubleValue();if(raw!=null&&raw!=JSONObject.NULL)try{double value=Double.parseDouble(String.valueOf(raw));if(Double.isFinite(value))return value;}catch(Exception ignored){}}return Double.NaN;}
    private static String first(JSONObject object,String...keys){for(String key:keys){String value=object.optString(key,"");if(!value.isEmpty()&&!value.equals("null"))return value;}return "";}
}
