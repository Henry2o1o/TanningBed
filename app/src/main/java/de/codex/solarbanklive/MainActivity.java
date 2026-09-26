package de.codex.solarbanklive;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.ViewGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS=72;
    private static int INK=Color.rgb(24,39,54), MUTED=Color.rgb(101,119,137), GREEN=Color.rgb(0,166,112), BG=Color.rgb(243,247,251), CARD=Color.WHITE, SOFT=Color.rgb(226,245,239), YELLOW=Color.rgb(224,157,0), BLUE=Color.rgb(16,129,218), ORANGE=Color.rgb(232,126,27);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayList<String> siteIds=new ArrayList<>();
    private final Map<String,String> siteNames=new HashMap<>();
    private JSONArray accountDevices=new JSONArray(),boundDevices=new JSONArray();
    private EditText emailField,passwordField;
    private LinearLayout authCard;
    private TextView status,updated;
    private Button loginButton;
    private Button themeButton;
    private Spinner siteSelector;
    private Spinner refreshInterval;
    private LinearLayout devicesArea;
    private TextView peakValue,peakCaption;
    private TextView pvStat,batteryStat,homeStat,gridStat,batteryDetailSoc,batteryCapacityUnderSoc,batteryChargePower,batteryDischargePower,batterySocLabel;
    private EditText myStromIpField;
    private TextView myStromPower,myStromStatus,myStromStat;
    private TextView summarySoc,summaryCharge,summaryDischarge,summaryPv,summaryHome,summaryGridImport,summaryGridExport;
    private LinearLayout myStromRow;
    private Button myStromEdit;
    private TextView chartPointLabel;
    private TextView chartLineWidthLabel;
    private float chartLineWidthDp=1.5f;
    private TextView chartLegendSizeLabel;
    private float chartLegendTextSizeSp=11f;
    private ProgressBar batteryDetailProgress;
    private LiveChart batterySocChart;
    private final ArrayList<float[]> batterySocDayValues=new ArrayList<>();
    private String batterySocChartDay="";
    private TextView mode2d,mode3d;
    private EnergyFlowView flowMap;
    private LiveChart chart;
    private LiveChart myStromChart;
    private final ArrayList<float[]> myStromDayValues=new ArrayList<>();
    private String myStromChartDay="";
    private TextView myStromChartPointLabel;
    private LinearLayout chartPeriods;
    private final Map<String,TextView> chartPeriodButtons=new LinkedHashMap<>();
    private TextView chartTitle, chartStatus;
    private final Map<String,ArrayList<float[]>> periodCharts=new HashMap<>();
    private String selectedChartPeriod="Tag";
    private String chartDeviceSn="";
    private long lastHistoryRequestAt=0;
    private final ArrayList<float[]> history=new ArrayList<>();
    private final ArrayList<float[]> energyFlowDayValues=new ArrayList<>();
    private String energyFlowChartDay="";
    private final ArrayList<SparklineView> liveMetricLines=new ArrayList<>();
    private double peakPv=Double.NaN;
    private String sceneTime="";
    private long lastChartSampleAt=0;
    private JSONObject sceneGridInfo=new JSONObject();
    private AnkerCloudClient cloudClient;
    private String loadingSiteId="",loadedSiteId="";
    private boolean active=true,settingSystems=false,houseView3D=false,darkTheme=false;
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService localIo=Executors.newSingleThreadExecutor();
    private boolean myStromRequestRunning=false;
    private final Runnable myStromPoll=new Runnable(){@Override public void run(){if(!active)return;String ip=getSharedPreferences("cloud_session",MODE_PRIVATE).getString("mystrom_ip","").trim();if(ip.isEmpty()){uiHandler.postDelayed(this,1000);return;}if(!myStromRequestRunning){myStromRequestRunning=true;localIo.execute(()->{try{JSONObject report=readMyStromReport(ip);double watts=report.optDouble("power",Double.NaN);boolean relay=report.optBoolean("relay",false);uiHandler.post(()->{myStromRequestRunning=false;if(!active)return;if(Double.isFinite(watts)){myStromPower.setText(String.format(Locale.GERMANY,"%.0f W",watts));if(myStromStat!=null)myStromStat.setText(String.format(Locale.GERMANY,"%.0f W",watts));updateMyStromChart(watts);myStromStatus.setText("● Lokal verbunden · Schalter "+(relay?"Ein":"Aus")+" · "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(new Date()));myStromRow.setVisibility(View.GONE);myStromEdit.setVisibility(View.VISIBLE);}else myStromStatus.setText("Keine gültige Leistung von der Steckdose erhalten");});}catch(Exception e){uiHandler.post(()->{myStromRequestRunning=false;if(active)myStromStatus.setText("● Nicht erreichbar · IP und WLAN prüfen");});}}); }uiHandler.postDelayed(this,1000);}};
    private final Runnable cachedRefresh=new Runnable(){@Override public void run(){if(!active)return;android.content.SharedPreferences p=getSharedPreferences("cloud_session",MODE_PRIVATE);String raw=p.getString("last_scene","");String cachedSite=p.getString("site_id","");if(!raw.isEmpty()&&cachedSite.equals(loadedSiteId))try{JSONObject scene=new JSONObject(raw);applyLiveTelemetry(scene,p);render(scene,cachedSite);long liveAt=latestLiveTimestamp(p);if(liveAt>0)updated.setText("Live · "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(new Date(liveAt)));}catch(Exception ignored){}uiHandler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state){super.onCreate(state);android.content.SharedPreferences prefs=getSharedPreferences("cloud_session",MODE_PRIVATE);chartLineWidthDp=Math.max(.1f,Math.min(3f,prefs.getFloat("chart_line_width_dp",1.5f)));chartLegendTextSizeSp=Math.max(9f,Math.min(16f,prefs.getFloat("chart_legend_size_sp",11f)));if(!prefs.getBoolean("theme_choice_saved",false))prefs.edit().putBoolean("dark_theme",true).putBoolean("theme_choice_saved",true).apply();darkTheme=prefs.getBoolean("dark_theme",true);applyThemePalette();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);buildUi();restoreSession();uiHandler.postDelayed(cachedRefresh,1000);uiHandler.postDelayed(myStromPoll,500);}
    @Override protected void onResume(){super.onResume();if(flowMap!=null)flowMap.startFlowAnimation();if(cloudClient==null&&devicesArea!=null){android.content.SharedPreferences prefs=getSharedPreferences("cloud_session",MODE_PRIVATE);String saved=prefs.getString("last_scene","");String savedSite=prefs.getString("site_id","");if(!saved.isEmpty()&&!savedSite.isEmpty())try{render(new JSONObject(saved),savedSite);status.setText("●  Hintergrunddaten zuletzt aktualisiert");}catch(Exception ignored){}}}
    @Override protected void onPause(){if(flowMap!=null)flowMap.stopFlowAnimation();super.onPause();}
    @Override protected void onDestroy(){active=false;uiHandler.removeCallbacks(cachedRefresh);uiHandler.removeCallbacks(myStromPoll);io.shutdownNow();localIo.shutdownNow();super.onDestroy();}

    private void buildUi(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(darkTheme?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(16),dp(18),dp(20));scroll.addView(page);
        LinearLayout topHeader=new LinearLayout(this);topHeader.setOrientation(LinearLayout.HORIZONTAL);topHeader.setGravity(Gravity.CENTER_VERTICAL);page.addView(topHeader,params(-1,58));TextView sunMark=text("☀",38,YELLOW,true);sunMark.setGravity(Gravity.CENTER);topHeader.addView(sunMark,params(50,-1));LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams brandLp=new LinearLayout.LayoutParams(0,-2,1);brandLp.leftMargin=dp(8);topHeader.addView(brand,brandLp);brand.addView(text("Solarbank 4 Pro",22,INK,true));status=text("●  Live Monitoring · Anker Cloud",11,GREEN,false);brand.addView(status);themeButton=new Button(this);themeButton.setText(darkTheme?"☀ Hell":"☾ Dunkel");themeButton.setTextSize(11);themeButton.setTextColor(INK);themeButton.setAllCaps(false);themeButton.setMinWidth(0);themeButton.setPadding(dp(8),0,dp(8),0);themeButton.setBackground(round(CARD,18));topHeader.addView(themeButton,params(80,40));themeButton.setOnClickListener(v->toggleTheme());

        authCard=card(page,0);authCard.addView(text("ANKER-KONTO",12,MUTED,true));
        emailField=new EditText(this);emailField.setSingleLine(true);emailField.setHint("E-Mail-Adresse");emailField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);LinearLayout.LayoutParams ep=params(-1,50);ep.topMargin=dp(8);authCard.addView(emailField,ep);
        passwordField=new EditText(this);passwordField.setSingleLine(true);passwordField.setHint("Passwort");passwordField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);LinearLayout.LayoutParams pp=params(-1,50);pp.topMargin=dp(4);authCard.addView(passwordField,pp);
        loginButton=new Button(this);loginButton.setText("Bei Anker SOLIX anmelden");loginButton.setTextColor(Color.WHITE);loginButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));LinearLayout.LayoutParams bp=params(-1,50);bp.topMargin=dp(8);authCard.addView(loginButton,bp);

        TextView section=text("HEUTE · "+new SimpleDateFormat("EEE, dd.MM.yyyy",Locale.GERMANY).format(new Date()),12,MUTED,true);LinearLayout.LayoutParams sectionParams=params(-1,-2);sectionParams.topMargin=dp(10);sectionParams.bottomMargin=dp(5);page.addView(section,sectionParams);
        siteSelector=new Spinner(this);siteSelector.setBackground(round(CARD,14));siteSelector.setPopupBackgroundDrawable(round(CARD,14));siteSelector.setPadding(dp(12),0,dp(10),0);siteSelector.setAdapter(systemAdapter(new ArrayList<>()));siteSelector.setEnabled(false);page.addView(siteSelector,params(-1,44));
        siteSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?> parent){} @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(!settingSystems&&position>=0&&position<siteIds.size()){loadSite(siteIds.get(position));keepSyncing(siteIds.get(position));}}});

        LinearLayout flowCard=card(page,10);flowCard.setPadding(dp(12),dp(12),dp(12),dp(12));
        LinearLayout flowHeader=new LinearLayout(this);flowHeader.setGravity(Gravity.CENTER_VERTICAL);flowCard.addView(flowHeader,params(-1,36));flowHeader.addView(text("ENERGIEFLUSS · LIVE",13,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout viewModes=new LinearLayout(this);viewModes.setOrientation(LinearLayout.HORIZONTAL);flowHeader.addView(viewModes,params(116,30));mode2d=modeButton("2D");mode3d=modeButton("3D");viewModes.addView(mode2d,new LinearLayout.LayoutParams(0,-1,1));LinearLayout.LayoutParams m3p=new LinearLayout.LayoutParams(0,-1,1);m3p.leftMargin=dp(5);viewModes.addView(mode3d,m3p);
        flowMap=new EnergyFlowView(this);LinearLayout.LayoutParams fmp=params(-1,285);fmp.topMargin=dp(7);flowCard.addView(flowMap,fmp);mode2d.setOnClickListener(v->selectHouseView(false));mode3d.setOnClickListener(v->selectHouseView(true));selectHouseView(false);

        TextView statsTitle=text("LEISTUNG · LIVE",11,MUTED,true);LinearLayout.LayoutParams stp=params(-1,-2);stp.topMargin=dp(8);stp.bottomMargin=dp(5);page.addView(statsTitle,stp);HorizontalScrollView metricScroll=new HorizontalScrollView(this);metricScroll.setHorizontalScrollBarEnabled(false);page.addView(metricScroll,params(-1,-2));LinearLayout statsRow=new LinearLayout(this);statsRow.setOrientation(LinearLayout.HORIZONTAL);metricScroll.addView(statsRow,new HorizontalScrollView.LayoutParams(-2,-2));pvStat=smallStat(statsRow,"☀ Solar","— W",YELLOW,true);batteryStat=smallStat(statsRow,"▣ Akku","— W",GREEN,false);homeStat=smallStat(statsRow,"⌂ Haus","— W",BLUE,false);gridStat=smallStat(statsRow,"⚡ Netz","— W",Color.rgb(170,99,255),false);myStromStat=smallStat(statsRow,"☀ myStrom","— W",ORANGE,false);



        LinearLayout analysis=new LinearLayout(this);analysis.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams alp=params(-1,-2);alp.topMargin=dp(14);page.addView(analysis,alp);
        LinearLayout chartCard=card(analysis,0);chartCard.setPadding(dp(12),dp(10),dp(12),dp(9));
        LinearLayout chartHeader=new LinearLayout(this);chartHeader.setGravity(Gravity.CENTER_VERTICAL);chartCard.addView(chartHeader,params(-1,32));
        chartTitle=text("↕  Energiefluss & Leistung",13,INK,true);chartHeader.addView(chartTitle,new LinearLayout.LayoutParams(0,-2,1));
        chartPeriods=new LinearLayout(this);chartPeriods.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);chartPeriods.setBackground(round(darkTheme?Color.rgb(5,17,31):Color.rgb(238,244,249),16));chartHeader.addView(chartPeriods,params(196,28));
        addChartPeriodButton("Heute","Tag");addChartPeriodButton("7 Tage","Woche");addChartPeriodButton("30 Tage","Monat");addChartPeriodButton("365 Tage","Jahr");
        chartStatus=text("Live",9,GREEN,true);chartStatus.setVisibility(View.GONE);
        chart=new LiveChart(this);LinearLayout.LayoutParams cp=params(-1,174);cp.topMargin=dp(4);chartCard.addView(chart,cp);
        chartPointLabel=text("",10,MUTED,false);chartPointLabel.setVisibility(View.GONE);chartCard.addView(chartPointLabel,params(-1,-2));
        selectChartPeriod("Tag");
        addSummaryCards(page);
        LinearLayout batteryCard=card(analysis,12);batteryCard.setPadding(dp(15),dp(13),dp(15),dp(13));LinearLayout batteryHeader=new LinearLayout(this);batteryHeader.setGravity(Gravity.CENTER_VERTICAL);batteryCard.addView(batteryHeader,params(-1,28));batteryHeader.addView(text("▣  BATTERIESTATUS",13,INK,true));LinearLayout batteryLine=new LinearLayout(this);batteryLine.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams blp=params(-1,68);blp.topMargin=dp(7);batteryCard.addView(batteryLine,blp);batteryDetailProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);batteryDetailProgress.setMax(100);batteryDetailProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));batteryDetailProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(darkTheme?Color.rgb(31,52,75):Color.rgb(222,231,239)));LinearLayout.LayoutParams bpr=params(0,8);bpr.width=dp(58);batteryLine.addView(batteryDetailProgress,bpr);LinearLayout socBlock=new LinearLayout(this);socBlock.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams socBlockLp=params(0,-2);socBlockLp.width=dp(108);socBlockLp.leftMargin=dp(7);batteryLine.addView(socBlock,socBlockLp);batteryDetailSoc=text("—%",21,INK,true);socBlock.addView(batteryDetailSoc);batteryCapacityUnderSoc=text("100 % = —",11,MUTED,false);socBlock.addView(batteryCapacityUnderSoc);View divider=new View(this);divider.setBackgroundColor(borderColor());LinearLayout.LayoutParams dvp=params(1,42);dvp.leftMargin=dp(8);dvp.rightMargin=dp(12);batteryLine.addView(divider,dvp);LinearLayout powerWrap=new LinearLayout(this);powerWrap.setGravity(Gravity.CENTER_VERTICAL);batteryLine.addView(powerWrap,new LinearLayout.LayoutParams(0,-2,1));LinearLayout chargeBlock=new LinearLayout(this);chargeBlock.setOrientation(LinearLayout.VERTICAL);powerWrap.addView(chargeBlock,new LinearLayout.LayoutParams(0,-2,1));chargeBlock.addView(text("LADEN",9,MUTED,true));batteryChargePower=text("— W",15,GREEN,true);chargeBlock.addView(batteryChargePower);View powerDivider=new View(this);powerDivider.setBackgroundColor(borderColor());LinearLayout.LayoutParams powerDividerLp=params(1,38);powerDividerLp.leftMargin=dp(5);powerDividerLp.rightMargin=dp(7);powerWrap.addView(powerDivider,powerDividerLp);LinearLayout dischargeBlock=new LinearLayout(this);dischargeBlock.setOrientation(LinearLayout.VERTICAL);powerWrap.addView(dischargeBlock,new LinearLayout.LayoutParams(0,-2,1));dischargeBlock.addView(text("ENTLADEN",9,MUTED,true));batteryDischargePower=text("— W",15,BLUE,true);dischargeBlock.addView(batteryDischargePower);addBatterySocChart(analysis);
        LinearLayout myStromCard=card(page,12);myStromCard.setPadding(dp(13),dp(12),dp(13),dp(12));LinearLayout myStromHeader=new LinearLayout(this);myStromHeader.setGravity(Gravity.CENTER_VERTICAL);myStromCard.addView(myStromHeader,params(-1,30));myStromHeader.addView(text("☀  WEITERE PV · MYSTROM",12,INK,true),new LinearLayout.LayoutParams(0,-2,1));myStromEdit=new Button(this);myStromEdit.setText("IP ändern");myStromEdit.setAllCaps(false);myStromEdit.setTextSize(10);myStromEdit.setMinHeight(0);myStromEdit.setPadding(dp(6),0,dp(6),0);myStromEdit.setTextColor(BLUE);myStromEdit.setBackgroundColor(Color.TRANSPARENT);myStromHeader.addView(myStromEdit,params(-2,30));myStromPower=text("— W",20,YELLOW,true);myStromHeader.addView(myStromPower);TextView myStromHint=text("Lokale Messung der Growatt-Einspeisung im WLAN",11,MUTED,false);LinearLayout.LayoutParams mhp=params(-1,-2);mhp.topMargin=dp(3);myStromCard.addView(myStromHint,mhp);myStromRow=new LinearLayout(this);myStromRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams msrp=params(-1,48);msrp.topMargin=dp(6);myStromCard.addView(myStromRow,msrp);myStromIpField=new EditText(this);myStromIpField.setSingleLine(true);myStromIpField.setHint("Steckdosen-IP, z. B. 192.168.1.50");myStromIpField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);String savedMyStromIp=getSharedPreferences("cloud_session",MODE_PRIVATE).getString("mystrom_ip","");myStromIpField.setText(savedMyStromIp);myStromIpField.setPadding(dp(10),0,dp(8),0);myStromIpField.setBackground(round(darkTheme?Color.rgb(23,42,59):Color.rgb(245,248,251),10));myStromRow.addView(myStromIpField,new LinearLayout.LayoutParams(0,-1,1));Button myStromSave=new Button(this);myStromSave.setText("Verbinden");myStromSave.setAllCaps(false);myStromSave.setTextColor(Color.WHITE);myStromSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));LinearLayout.LayoutParams msp=params(104,-1);msp.leftMargin=dp(7);myStromRow.addView(myStromSave,msp);myStromStatus=text(savedMyStromIp.isEmpty()?"IP eingeben, um die Steckdose lokal auszulesen":"Verbinde lokal mit "+savedMyStromIp+" …",10,MUTED,false);LinearLayout.LayoutParams mssp=params(-1,-2);mssp.topMargin=dp(4);myStromCard.addView(myStromStatus,mssp);myStromRow.setVisibility(savedMyStromIp.isEmpty()?View.VISIBLE:View.GONE);myStromEdit.setVisibility(savedMyStromIp.isEmpty()?View.GONE:View.VISIBLE);myStromEdit.setOnClickListener(v->{myStromIpField.setText(getSharedPreferences("cloud_session",MODE_PRIVATE).getString("mystrom_ip",""));myStromRow.setVisibility(View.VISIBLE);myStromEdit.setVisibility(View.GONE);});myStromSave.setOnClickListener(v->saveMyStromAddress());addMyStromChart(page);
        LinearLayout cloudControls=new LinearLayout(this);cloudControls.setOrientation(LinearLayout.HORIZONTAL);cloudControls.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams ccp=params(-1,52);ccp.topMargin=dp(12);page.addView(cloudControls,ccp);
        TextView liveMode=text("●  LIVE · MQTT",13,GREEN,true);liveMode.setGravity(Gravity.CENTER);liveMode.setPadding(dp(16),dp(10),dp(16),dp(10));liveMode.setBackground(round(darkTheme?Color.rgb(13,70,58):Color.rgb(224,246,237),16));cloudControls.addView(liveMode,new LinearLayout.LayoutParams(-1,-1));getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putInt("refresh_interval",30_000).apply();
        updated=text("Noch keine Cloudwerte geladen",12,MUTED,false);LinearLayout.LayoutParams up=params(-1,-2);up.topMargin=dp(6);page.addView(updated,up);
        TextView deviceTitle=text("GERÄTE",12,MUTED,true);LinearLayout.LayoutParams dtp=params(-1,-2);dtp.topMargin=dp(22);dtp.bottomMargin=dp(8);page.addView(deviceTitle,dtp);
        devicesArea=new LinearLayout(this);devicesArea.setOrientation(LinearLayout.VERTICAL);page.addView(devicesArea,params(-1,-2));
        devicesArea.addView(text("Melde dich an, um die Geräte aus deinem Anker-Konto zu laden.",14,MUTED,false));
        TextView note=text("Cloud-only ohne Modbus TCP. Anker aktualisiert Cloudwerte möglicherweise verzögert; für die Solarbank 4 Pro sind Livewerte über die inoffizielle Schnittstelle nicht garantiert.",12,MUTED,false);LinearLayout.LayoutParams noteParams=params(-1,-2);noteParams.topMargin=dp(18);page.addView(note,noteParams);
        LinearLayout chartSettings=card(page,10);chartSettings.addView(text("DIAGRAMM-DARSTELLUNG",11,MUTED,true));addChartLineControl(chartSettings);addChartLegendControl(chartSettings);

        loginButton.setOnClickListener(v->login());
        final float[] pullStart={0};final boolean[] pullReady={false};
        scroll.setOnTouchListener((v,event)->{switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:pullStart[0]=event.getY();pullReady[0]=false;break;case MotionEvent.ACTION_MOVE:if(scroll.getScrollY()==0&&event.getY()-pullStart[0]>dp(92)){pullReady[0]=true;status.setText("↓  Zum Aktualisieren loslassen");}break;case MotionEvent.ACTION_UP:if(pullReady[0])refreshSelectedSite();pullReady[0]=false;break;case MotionEvent.ACTION_CANCEL:pullReady[0]=false;break;}return false;});
        addBottomNavigation(root,scroll,siteSelector,analysis,deviceTitle,note);root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});setContentView(root);
    }

    private void refreshSelectedSite(){int i=siteSelector.getSelectedItemPosition();if(i>=0&&i<siteIds.size()){status.setText("●  Aktualisiere Cloudwerte …");loadedSiteId="";loadSite(siteIds.get(i));}}
    private void saveMyStromAddress(){String ip=myStromIpField.getText().toString().trim();if(!isPrivateIpv4(ip)){myStromStatus.setText("Bitte eine lokale IPv4-Adresse aus dem Heimnetz eingeben");return;}getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putString("mystrom_ip",ip).apply();myStromStatus.setText("Verbinde lokal mit "+ip+" …");uiHandler.removeCallbacks(myStromPoll);uiHandler.post(myStromPoll);}
    private boolean isPrivateIpv4(String ip){String[] p=ip.split("\\.",-1);if(p.length!=4)return false;int[] n=new int[4];try{for(int i=0;i<4;i++){if(p[i].isEmpty()||p[i].length()>3)return false;n[i]=Integer.parseInt(p[i]);if(n[i]<0||n[i]>255)return false;}}catch(NumberFormatException e){return false;}return n[0]==10||(n[0]==192&&n[1]==168)||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==169&&n[1]==254);}
    private JSONObject readMyStromReport(String ip)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL("http://"+ip+"/report").openConnection();connection.setRequestMethod("GET");connection.setConnectTimeout(2500);connection.setReadTimeout(2500);connection.setUseCaches(false);try{if(connection.getResponseCode()!=200)throw new java.io.IOException("HTTP "+connection.getResponseCode());try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[512];int count;while((count=input.read(buffer))!=-1){output.write(buffer,0,count);if(output.size()>8192)throw new java.io.IOException("Antwort zu groß");}return new JSONObject(output.toString("UTF-8"));}}finally{connection.disconnect();}}
    private void applyThemePalette(){if(darkTheme){INK=Color.rgb(236,243,250);MUTED=Color.rgb(157,175,194);GREEN=Color.rgb(46,215,151);BG=Color.rgb(7,16,27);CARD=Color.rgb(17,31,46);SOFT=Color.rgb(22,60,58);YELLOW=Color.rgb(255,197,54);BLUE=Color.rgb(56,163,255);ORANGE=Color.rgb(255,153,62);}else{INK=Color.rgb(24,39,54);MUTED=Color.rgb(101,119,137);GREEN=Color.rgb(0,166,112);BG=Color.rgb(243,247,251);CARD=Color.WHITE;SOFT=Color.rgb(226,245,239);YELLOW=Color.rgb(224,157,0);BLUE=Color.rgb(16,129,218);ORANGE=Color.rgb(232,126,27);}}
    private void toggleTheme(){darkTheme=!darkTheme;getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putBoolean("dark_theme",darkTheme).putBoolean("theme_choice_saved",true).apply();recreate();}
    private int borderColor(){return darkTheme?Color.rgb(35,59,80):Color.rgb(222,231,239);}
    private long intervalMillis(){return getSharedPreferences("cloud_session",MODE_PRIVATE).getInt("refresh_interval",30_000);}
    private TextView modeButton(String label){TextView t=text(label,12,INK,true);t.setGravity(Gravity.CENTER);t.setClickable(true);t.setFocusable(true);return t;}
    private void addChartPeriodButton(String label,String key){TextView chip=text(label,8,MUTED,true);chip.setGravity(Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);chartPeriods.addView(chip,lp);chartPeriodButtons.put(key,chip);chip.setOnClickListener(v->selectChartPeriod(key));}
    private void updateChartPeriodButtons(){for(Map.Entry<String,TextView> entry:chartPeriodButtons.entrySet()){boolean selected=entry.getKey().equals(selectedChartPeriod);TextView chip=entry.getValue();chip.setTextColor(selected?Color.WHITE:MUTED);chip.setBackground(selected?round(darkTheme?Color.rgb(0,116,186):BLUE,14):null);}}
    private ArrayAdapter<String> systemAdapter(ArrayList<String> labels){ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){@Override public View getView(int position,View convertView,ViewGroup parent){TextView t=(TextView)super.getView(position,convertView,parent);t.setTextColor(INK);t.setTextSize(16);return t;}@Override public View getDropDownView(int position,View convertView,ViewGroup parent){TextView t=(TextView)super.getDropDownView(position,convertView,parent);t.setTextColor(INK);t.setBackgroundColor(CARD);t.setPadding(dp(14),dp(12),dp(14),dp(12));return t;}};adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return adapter;}
    private void selectHouseView(boolean threeD){houseView3D=threeD;if(mode2d==null||mode3d==null)return;mode2d.setTextColor(threeD?MUTED:Color.WHITE);mode2d.setBackground(round(threeD?(darkTheme?Color.rgb(22,39,55):Color.rgb(231,238,245)):BLUE,16));mode3d.setTextColor(threeD?Color.WHITE:MUTED);mode3d.setBackground(round(threeD?BLUE:(darkTheme?Color.rgb(22,39,55):Color.rgb(231,238,245)),16));if(flowMap!=null)flowMap.invalidate();}

    private void addBottomNavigation(LinearLayout root,ScrollView scroll,View system,View analysis,View devices,View more){LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(4),dp(6),dp(4),dp(7));nav.setBackgroundColor(CARD);String[] labels={"⌂\nÜbersicht","▥\nVerlauf","⚙\nSystem","▦\nGeräte","•••\nMehr"};View[] targets={null,analysis,system,devices,more};for(int i=0;i<labels.length;i++){final View target=targets[i];TextView item=text(labels[i],10,i==0?BLUE:MUTED,true);item.setGravity(Gravity.CENTER);item.setPadding(dp(2),dp(2),dp(2),dp(2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);nav.addView(item,lp);item.setOnClickListener(v->{if(target==null)scroll.smoothScrollTo(0,0);else scroll.smoothScrollTo(0,Math.max(0,target.getTop()-dp(12)));});}root.addView(nav,new LinearLayout.LayoutParams(-1,dp(56)));}
    private TextView smallStat(LinearLayout row,String label,String value,int accent,boolean first){LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(8),dp(7),dp(7),dp(6));GradientDrawable shape=darkTheme?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(15,32,49),Color.rgb(6,17,30)}):round(CARD,15);shape.setCornerRadius(dp(15));shape.setStroke(dp(1),darkTheme?Color.argb(105,Color.red(accent),Color.green(accent),Color.blue(accent)):borderColor());tile.setBackground(shape);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(68),dp(88));if(!first)lp.leftMargin=dp(4);row.addView(tile,lp);TextView labelView=text(label,10,accent,true);labelView.setSingleLine(true);labelView.setAutoSizeTextTypeUniformWithConfiguration(7,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);tile.addView(labelView);TextView v=text(value,14,INK,true);v.setSingleLine(true);v.setAutoSizeTextTypeUniformWithConfiguration(9,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);LinearLayout.LayoutParams vp=params(-1,-2);vp.topMargin=dp(4);tile.addView(v,vp);SparklineView spark=new SparklineView(this,accent);liveMetricLines.add(spark);LinearLayout.LayoutParams sp=params(-1,25);sp.topMargin=dp(3);tile.addView(spark,sp);return v;}
    private void updateMetricSparklines(){for(int series=0;series<liveMetricLines.size();series++){ArrayList<Float> sample=new ArrayList<>();if(series<4){int from=Math.max(0,history.size()-48);for(int i=from;i<history.size();i++){float[] point=history.get(i);if(point.length>series&&Float.isFinite(point[series]))sample.add(point[series]);}}else{int from=Math.max(0,myStromDayValues.size()-90);for(int i=from;i<myStromDayValues.size();i++){float[] point=myStromDayValues.get(i);if(point.length>0&&Float.isFinite(point[0]))sample.add(point[0]);}}liveMetricLines.get(series).setSamples(sample);}}

    private class SparklineView extends View {
        private final Paint line=new Paint(3);private final int accent;private final ArrayList<Float> samples=new ArrayList<>();
        SparklineView(android.content.Context context,int color){super(context);accent=color;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void setSamples(java.util.List<Float> points){samples.clear();samples.addAll(points);invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();if(samples.size()<2)return;float lo=Float.MAX_VALUE,hi=-Float.MAX_VALUE;for(float value:samples){lo=Math.min(lo,value);hi=Math.max(hi,value);}float span=Math.max(1f,hi-lo);Path path=new Path();for(int i=0;i<samples.size();i++){float x=dp(2)+(w-dp(4))*i/(samples.size()-1);float y=h-dp(2)-(samples.get(i)-lo)/span*(h-dp(5));if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}line.setStyle(Paint.Style.STROKE);line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeJoin(Paint.Join.ROUND);line.setColor(Color.argb(58,Color.red(accent),Color.green(accent),Color.blue(accent)));line.setStrokeWidth(dp(4));line.setShadowLayer(dp(4),0,0,accent);canvas.drawPath(path,line);line.clearShadowLayer();line.setColor(accent);line.setStrokeWidth(1.4f*getResources().getDisplayMetrics().density);canvas.drawPath(path,line);line.setStyle(Paint.Style.FILL);}
    }

    private void addSummaryCards(LinearLayout parent){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rp=params(-1,-2);rp.topMargin=dp(10);parent.addView(row,rp);
        LinearLayout bank=compactSummaryCard(row,"▣  SOLARBANK",true);summarySoc=compactSummaryValue(bank,"Ladestand","—%");summaryCharge=compactSummaryValue(bank,"Laden","— W");summaryDischarge=compactSummaryValue(bank,"Entladen","— W");
        LinearLayout today=compactSummaryCard(row,"ϟ  HEUTE · LIVE",false);summaryPv=compactSummaryValue(today,"PV-Leistung","— W");summaryHome=compactSummaryValue(today,"Hausverbrauch","— W");summaryGridImport=compactSummaryValue(today,"Netzbezug","— W");
        LinearLayout meter=compactSummaryCard(row,"⌁  SMART METER",false);summaryGridExport=compactSummaryValue(meter,"Einspeisung","— W");
    }
    private LinearLayout compactSummaryCard(LinearLayout row,String title,boolean first){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(9),dp(6),dp(8));GradientDrawable bg=darkTheme?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(18,38,57),Color.rgb(7,18,31)}):round(CARD,15);bg.setCornerRadius(dp(15));bg.setStroke(dp(1),borderColor());box.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(130),1);if(!first)lp.leftMargin=dp(5);row.addView(box,lp);TextView heading=text(title,9,INK,true);heading.setSingleLine(true);heading.setAutoSizeTextTypeUniformWithConfiguration(7,9,1,android.util.TypedValue.COMPLEX_UNIT_SP);box.addView(heading);return box;}
    private TextView compactSummaryValue(LinearLayout box,String label,String initial){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams rp=params(-1,-2);rp.topMargin=dp(7);box.addView(row,rp);TextView key=text(label,8,MUTED,false);key.setSingleLine(true);row.addView(key);TextView value=text(initial,10,INK,true);value.setSingleLine(true);value.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);row.addView(value);return value;}

    private void login(){
        final String email=emailField.getText().toString().trim(),password=passwordField.getText().toString();
        if(email.isEmpty()||password.isEmpty()){status.setText("●  E-Mail-Adresse und Passwort eingeben");return;}
        loginButton.setEnabled(false);status.setText("●  Anmeldung bei Anker SOLIX …");
        io.execute(()->{try{
            AnkerCloudClient client=new AnkerCloudClient();AnkerCloudClient.Result result=client.loginAndGetSites(email,password);
            JSONArray users=new JSONArray(),bound=new JSONArray();String deviceWarning="";
            try{users=client.getUserDevices();}catch(Exception ignored){deviceWarning=" · Geräteliste teilweise nicht verfügbar";}
            try{bound=client.getBoundDevices();}catch(Exception ignored){deviceWarning=" · Geräteliste teilweise nicht verfügbar";}
            final JSONArray loadedUsers=users,loadedBound=bound;final String warning=deviceWarning;
            runOnUiThread(()->{if(!active)return;cloudClient=client;accountDevices=loadedUsers;boundDevices=loadedBound;getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putString("auth_token",client.authToken()).putString("user_token",client.userToken()).putString("nickname",result.nickname).apply();loginButton.setEnabled(true);passwordField.setText("");authCard.setVisibility(View.GONE);status.setText("●  Angemeldet als "+result.nickname+warning);setSystems(result.sites);});
        }catch(Exception e){runOnUiThread(()->{if(!active)return;loginButton.setEnabled(true);passwordField.setText("");status.setText("●  Anmeldung fehlgeschlagen: "+(e.getMessage()==null?"Unbekannter Fehler":e.getMessage()));});}});
    }

    private void restoreSession(){
        android.content.SharedPreferences prefs=getSharedPreferences("cloud_session",MODE_PRIVATE);
        String token=prefs.getString("auth_token","");String userToken=prefs.getString("user_token","");
        if(token.isEmpty()||userToken.isEmpty())return;
        authCard.setVisibility(View.GONE);String nickname=prefs.getString("nickname","Anker SOLIX");status.setText("●  Angemeldet als "+nickname);
        AnkerCloudClient client=new AnkerCloudClient(token,userToken);cloudClient=client;
        io.execute(()->{try{
            AnkerCloudClient.Result result=client.getSites(nickname);JSONArray users=new JSONArray(),bound=new JSONArray();
            try{users=client.getUserDevices();}catch(Exception ignored){}try{bound=client.getBoundDevices();}catch(Exception ignored){}
            JSONArray loadedUsers=users,loadedBound=bound;
            runOnUiThread(()->{if(!active)return;accountDevices=loadedUsers;boundDevices=loadedBound;status.setText("●  Angemeldet als "+nickname);setSystems(result.sites);});
        }catch(Exception e){runOnUiThread(()->{if(!active)return;status.setText("●  Sitzung gespeichert · Anker-Systeme gerade nicht erreichbar");});}});
    }

    private void setSystems(JSONArray sites){
        siteIds.clear();siteNames.clear();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<sites.length();i++){JSONObject site=sites.optJSONObject(i);if(site==null)continue;JSONObject info=site.optJSONObject("site_info");String id=site.optString("site_id","");if(id.isEmpty()&&info!=null)id=info.optString("site_id","");if(id.isEmpty())continue;String name=site.optString("site_name","");if(name.isEmpty()&&info!=null)name=info.optString("site_name","");if(name.isEmpty())name=site.optString("name","SOLIX-System "+(labels.size()+1));siteIds.add(id);siteNames.put(id,name);labels.add(name);}
        if(labels.isEmpty()){siteSelector.setEnabled(false);status.setText("●  Angemeldet · keine Systeme in der Cloud-Antwort");return;}
        settingSystems=true;siteSelector.setAdapter(systemAdapter(labels));siteSelector.setEnabled(true);String preferred=getSharedPreferences("cloud_session",MODE_PRIVATE).getString("site_id","");int selected=siteIds.indexOf(preferred);if(selected<0)selected=0;siteSelector.setSelection(selected);settingSystems=false;loadSite(siteIds.get(selected));keepSyncing(siteIds.get(selected));
    }

    private void keepSyncing(String siteId){
        if(cloudClient==null||siteId==null||siteId.isEmpty())return;
        getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putString("site_id",siteId).apply();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},REQUEST_NOTIFICATIONS);return;}
        startSyncService(siteId);
    }
    private void startSyncService(String siteId){Intent i=new Intent(this,BackgroundSyncService.class).putExtra("site_id",siteId);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_NOTIFICATIONS){int i=siteSelector==null?-1:siteSelector.getSelectedItemPosition();if(i>=0&&i<siteIds.size())startSyncService(siteIds.get(i));}}

    private void loadSite(String siteId){if(cloudClient==null||siteId.equals(loadingSiteId)||siteId.equals(loadedSiteId))return;loadingSiteId=siteId;status.setText("●  Lade Systemdaten …");io.execute(()->{try{JSONObject data=cloudClient.getSceneInfo(siteId);runOnUiThread(()->{if(!active)return;loadingSiteId="";loadedSiteId=siteId;render(data,siteId);status.setText("●  Systemdaten empfangen");});}catch(Exception e){runOnUiThread(()->{if(!active)return;loadingSiteId="";status.setText("●  Keine Detaildaten: "+(e.getMessage()==null?"Cloudfehler":e.getMessage()));clearMetrics();devicesArea.removeAllViews();devicesArea.addView(text("Geräte dieses Systems konnten nicht geladen werden.",14,MUTED,false));});}});}

    private void render(JSONObject data,String siteId){
        applyLiveTelemetry(data,getSharedPreferences("cloud_session",MODE_PRIVATE));
        JSONObject bank=data.optJSONObject("solarbank_info");if(bank==null)bank=new JSONObject();
        JSONObject first=bank.optJSONArray("solarbank_list")!=null?bank.optJSONArray("solarbank_list").optJSONObject(0):null;if(first==null)first=new JSONObject();
        chartDeviceSn=firstNonEmpty(first.optString("device_sn",""),first.optString("sn",""));
        String socValue=firstNonEmpty(bank.optString("total_battery_power",""),first.optString("battery_soc",""));double soc=parseNumber(socValue,Double.NaN);if(soc>=0&&soc<=1.0)soc*=100;if(!Double.isFinite(soc)||soc<0||soc>100){soc=parseNumber(first.optString("battery_soc",""),Double.NaN);if(soc>=0&&soc<=1.0)soc*=100;}
        // Do not clamp malformed payloads to 0/100: that made the battery ring jump.
        int percent=Double.isFinite(soc)&&soc>=0&&soc<=100?(int)Math.round(soc):-1;double capacityWh=systemBatteryCapacityWh(bank);
        String pvRaw=firstNonEmpty(bank.optString("total_photovoltaic_power",""),bank.optString("total_pv_input_power",""));double pv=parseNumber(pvRaw,Double.NaN);
        JSONObject grid=data.optJSONObject("grid_info");String exportRaw=grid==null?"":grid.optString("photovoltaic_to_grid_power","");String signedBattery=bank.optString("total_battery_power_signed","");String batteryPower=firstNonEmpty(signedBattery,bank.optString("total_charging_power",""),first.optString("charging_power",""));String dischargeRaw=firstNonEmpty(bank.optString("total_discharging_power",""),first.optString("discharging_power",""));double chargeW=parseNumber(batteryPower,Double.NaN),dischargeW=parseNumber(dischargeRaw,Double.NaN);if(!signedBattery.isEmpty()){double signed=parseNumber(signedBattery,Double.NaN);chargeW=Double.isFinite(signed)?Math.max(0,-signed):Double.NaN;dischargeW=Double.isFinite(signed)?Math.max(0,signed):Double.NaN;}else{if(Double.isFinite(dischargeW))dischargeW=Math.abs(dischargeW);if(Double.isFinite(chargeW)&&chargeW<0){dischargeW=Double.isFinite(dischargeW)?Math.max(dischargeW,Math.abs(chargeW)):Math.abs(chargeW);chargeW=0;}}String homeRaw=firstNonEmpty(data.optString("home_load_power",""),bank.optString("to_home_load",""),bank.optString("total_home_load_power",""));String gridRaw=grid==null?"":grid.optString("grid_to_home_power","");
        flowMap.setReadings(power(pvRaw),power(gridRaw),power(exportRaw),power(homeRaw),power(batteryPower),percent);if(summarySoc!=null){summarySoc.setText(percent<0?"—%":percent+"%");summaryCharge.setText(power(String.valueOf(chargeW)));summaryDischarge.setText(power(String.valueOf(dischargeW)));summaryPv.setText(power(pvRaw));summaryHome.setText(power(homeRaw));summaryGridImport.setText(power(gridRaw));summaryGridExport.setText(power(exportRaw));}pvStat.setText(power(pvRaw));batteryStat.setText(power(batteryPower));homeStat.setText(power(homeRaw));gridStat.setText(power(gridRaw));if(batteryDetailSoc!=null){batteryDetailSoc.setText(percent<0?"—%":percent+"%");batteryChargePower.setText(power(String.valueOf(chargeW)));batteryDischargePower.setText(power(String.valueOf(dischargeW)));batteryCapacityUnderSoc.setText(formatCapacity(capacityWh,percent));batteryDetailProgress.setProgress(Math.max(0,percent));}
        long sampleAt=latestLiveTimestamp(getSharedPreferences("cloud_session",MODE_PRIVATE));if(sampleAt<=0)sampleAt=parseTimeMillis(firstNonEmpty(bank.optString("updated_time",""),data.optString("updated_time","")));if(sampleAt<=0)sampleAt=System.currentTimeMillis();updateBatterySocChart(percent,sampleAt);
        if(Double.isFinite(pv)){if(Double.isNaN(peakPv)||pv>peakPv)peakPv=pv;if(sampleAt>0&&sampleAt!=lastChartSampleAt){lastChartSampleAt=sampleAt;double homeW=parseNumber(homeRaw,0),batterySigned=Double.isFinite(chargeW)||Double.isFinite(dischargeW)?finite0(chargeW)-finite0(dischargeW):parseNumber(batteryPower,0),gridSigned=parseNumber(gridRaw,0)-parseNumber(exportRaw,0);float[] point={(float)pv,(float)homeW,(float)batterySigned,(float)gridSigned};history.add(point);if(history.size()>240)history.remove(0);updateEnergyFlowDayChart(point,sampleAt);updateMetricSparklines();}}
        String time=firstNonEmpty(bank.optString("updated_time",""),data.optString("updated_time",""));sceneTime=time;sceneGridInfo=data.optJSONObject("grid_info");if(sceneGridInfo==null)sceneGridInfo=new JSONObject();updated.setText(time.isEmpty()?"Cloudwerte empfangen · Zeitstempel fehlt":"Letzter Cloudstand: "+formatTime(time));
        renderDevices(data,siteId);
    }

    private void applyLiveTelemetry(JSONObject scene,android.content.SharedPreferences prefs){
        JSONObject bank=scene.optJSONObject("solarbank_info");if(bank==null){bank=new JSONObject();try{scene.put("solarbank_info",bank);}catch(Exception ignored){}}JSONObject grid=scene.optJSONObject("grid_info");if(grid==null){grid=new JSONObject();try{scene.put("grid_info",grid);}catch(Exception ignored){}}
        try{JSONArray devices=new JSONArray(prefs.getString("mqtt_devices","[]"));for(int i=0;i<devices.length();i++){JSONObject d=devices.optJSONObject(i);if(d==null)continue;String pn=firstNonEmpty(d.optString("device_pn",""),d.optString("product_code",""),d.optString("device_model",""));if(!"AE103".equalsIgnoreCase(pn))continue;String sn=firstNonEmpty(d.optString("device_sn",""),d.optString("sn",""));JSONObject live=new JSONObject(prefs.getString("mqtt_device_"+sn,"{}"));if(live.length()==0)continue;
            // Do not read persisted AE103 SOC here: older app versions may have cached
            // the incorrectly decoded nested MQTT field under this name.
            if(live.has("photovoltaic_power"))bank.put("total_photovoltaic_power",live.optDouble("photovoltaic_power"));if(live.has("battery_power_signed"))bank.put("total_battery_power_signed",live.optDouble("battery_power_signed"));if(live.has("home_demand"))scene.put("home_load_power",live.optDouble("home_demand"));if(live.has("grid_power_signed")){double p=live.optDouble("grid_power_signed");grid.put("grid_to_home_power",Math.max(0,p));grid.put("photovoltaic_to_grid_power",Math.max(0,-p));}break;
        }}catch(Exception ignored){}
    }
    private void selectChartPeriod(String period){
        selectedChartPeriod=period;chartTitle.setText("↕  Energiefluss & Leistung");updateChartPeriodButtons();
        ArrayList<float[]> cached=periodCharts.get(period);if(cached!=null&&!cached.isEmpty()){chart.setValues(cached);chartStatus.setText("Anker-Verlauf");return;}
        if(period.equals("Tag")&&!energyFlowDayValues.isEmpty())chart.setValues(energyFlowDayValues);
        if(cloudClient==null||loadedSiteId.isEmpty()||chartDeviceSn.isEmpty()){chartStatus.setText(period.equals("Tag")?"Live":"Anmeldung nötig");return;}
        long now=System.currentTimeMillis();if(now-lastHistoryRequestAt<5500){chartStatus.setText("Kurz warten");return;}lastHistoryRequestAt=now;chartStatus.setText("Lädt …");String site=loadedSiteId,sn=chartDeviceSn;
        io.execute(()->{try{java.util.Calendar cal=java.util.Calendar.getInstance();java.util.Date end=cal.getTime();cal.set(java.util.Calendar.HOUR_OF_DAY,0);cal.set(java.util.Calendar.MINUTE,0);cal.set(java.util.Calendar.SECOND,0);cal.set(java.util.Calendar.MILLISECOND,0);if(period.equals("Woche"))cal.add(java.util.Calendar.DAY_OF_YEAR,-6);else if(period.equals("Monat"))cal.add(java.util.Calendar.DAY_OF_YEAR,-29);else if(period.equals("Jahr"))cal.add(java.util.Calendar.DAY_OF_YEAR,-364);java.util.Date start=cal.getTime();String granularity=period.equals("Tag")?"day":period.equals("Woche")?"week":period.equals("Monat")?"month":"year";SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);JSONObject result=cloudClient.getEnergyAnalysis(site,sn,granularity,iso.format(start),iso.format(end));JSONArray points=result.optJSONArray("power");if(points==null)points=result.optJSONArray("list");ArrayList<float[]> parsed=new ArrayList<>();if(points!=null)for(int i=0;i<points.length();i++){JSONObject point=points.optJSONObject(i);if(point==null)continue;double solar=energyVal(point,"solar_power","pv_power","photovoltaic_power"),home=energyVal(point,"output_power","home_power","home_load_power"),battery=energyVal(point,"battery_power","charge_power"),grid=energyVal(point,"grid_power","grid_import_power");if(Double.isFinite(solar)||Double.isFinite(home)||Double.isFinite(battery)||Double.isFinite(grid))parsed.add(new float[]{(float)finite0(solar),(float)finite0(home),(float)finite0(battery),(float)finite0(grid)});}runOnUiThread(()->{if(!active||!period.equals(selectedChartPeriod))return;if(!parsed.isEmpty()){periodCharts.put(period,parsed);chart.setValues(parsed);chartStatus.setText("Anker-Verlauf");}else{chartStatus.setText("Keine Verlaufsdaten");if(period.equals("Tag")&&!energyFlowDayValues.isEmpty())chart.setValues(energyFlowDayValues);}});}catch(Exception e){runOnUiThread(()->{if(!active||!period.equals(selectedChartPeriod))return;chartStatus.setText("Live · Cloud-Verlauf nicht verfügbar");if(period.equals("Tag")&&!energyFlowDayValues.isEmpty())chart.setValues(energyFlowDayValues);});}});
    }
    private void addMyStromChart(LinearLayout parent){
        LinearLayout card=card(parent,12);card.setPadding(dp(13),dp(13),dp(13),dp(12));LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);card.addView(header,params(-1,30));header.addView(text("▥  myStrom PV · Heute",15,INK,true),new LinearLayout.LayoutParams(0,-2,1));TextView today=text("HEUTE",10,GREEN,true);today.setGravity(Gravity.CENTER);today.setPadding(dp(10),dp(5),dp(10),dp(5));today.setBackground(round(darkTheme?Color.rgb(13,70,58):Color.rgb(224,246,237),18));header.addView(today);myStromChart=new LiveChart(this);myStromChart.setSingleSeries("myStrom PV",null);LinearLayout.LayoutParams chartLp=params(-1,232);chartLp.topMargin=dp(8);card.addView(myStromChart,chartLp);myStromChartPointLabel=text("Verlauf ab erfolgreicher Verbindung",10,MUTED,false);LinearLayout.LayoutParams valueLp=params(-1,-2);valueLp.topMargin=dp(4);card.addView(myStromChartPointLabel,valueLp);myStromChart.setSingleSeries("myStrom PV",myStromChartPointLabel);
    }
    private void addBatterySocChart(LinearLayout parent){
        LinearLayout card=card(parent,12);card.setPadding(dp(13),dp(13),dp(13),dp(12));LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);card.addView(header,params(-1,30));header.addView(text("▥  Batterieladestand · Heute",15,INK,true),new LinearLayout.LayoutParams(0,-2,1));TextView today=text("HEUTE",10,GREEN,true);today.setGravity(Gravity.CENTER);today.setPadding(dp(10),dp(5),dp(10),dp(5));today.setBackground(round(darkTheme?Color.rgb(13,70,58):Color.rgb(224,246,237),18));header.addView(today);batterySocChart=new LiveChart(this);batterySocChart.setPercentMode(true);LinearLayout.LayoutParams chartLp=params(-1,232);chartLp.topMargin=dp(8);card.addView(batterySocChart,chartLp);batterySocLabel=text("Ladezustand in % · Verlauf ab Messbeginn",10,MUTED,false);LinearLayout.LayoutParams valueLp=params(-1,-2);valueLp.topMargin=dp(4);card.addView(batterySocLabel,valueLp);batterySocChart.setSingleSeries("Ladezustand",batterySocLabel);
    }
    private void updateEnergyFlowDayChart(float[] point,long timestamp){String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(timestamp));if(!today.equals(energyFlowChartDay)){energyFlowChartDay=today;energyFlowDayValues.clear();for(int i=0;i<1440;i++)energyFlowDayValues.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});}java.util.Calendar at=java.util.Calendar.getInstance();at.setTimeInMillis(timestamp);int minute=at.get(java.util.Calendar.HOUR_OF_DAY)*60+at.get(java.util.Calendar.MINUTE);energyFlowDayValues.set(minute,point.clone());if("Tag".equals(selectedChartPeriod)&&chart!=null)chart.setValues(energyFlowDayValues,minute);}
    private void updateBatterySocChart(int percent,long timestamp){
        if(percent<0||percent>100||batterySocChart==null)return;String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(timestamp));if(!today.equals(batterySocChartDay)){batterySocChartDay=today;batterySocDayValues.clear();for(int i=0;i<1440;i++)batterySocDayValues.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});}
        java.util.Calendar at=java.util.Calendar.getInstance();at.setTimeInMillis(timestamp);int minute=at.get(java.util.Calendar.HOUR_OF_DAY)*60+at.get(java.util.Calendar.MINUTE);batterySocDayValues.set(minute,new float[]{percent,Float.NaN,Float.NaN,Float.NaN});batterySocChart.setValues(batterySocDayValues,minute);
    }
    private void updateMyStromChart(double watts){
        String today=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());if(!today.equals(myStromChartDay)){myStromChartDay=today;myStromDayValues.clear();for(int i=0;i<1440;i++)myStromDayValues.add(new float[]{Float.NaN,Float.NaN,Float.NaN,Float.NaN});}
        java.util.Calendar now=java.util.Calendar.getInstance();int minute=now.get(java.util.Calendar.HOUR_OF_DAY)*60+now.get(java.util.Calendar.MINUTE);myStromDayValues.set(minute,new float[]{(float)watts,Float.NaN,Float.NaN,Float.NaN});if(myStromChart!=null)myStromChart.setValues(myStromDayValues,minute);updateMetricSparklines();
    }
    private void addChartLineControl(LinearLayout parent){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams rowLp=params(-1,38);rowLp.topMargin=dp(4);parent.addView(row,rowLp);
        chartLineWidthLabel=text("Linie · "+String.format(Locale.GERMANY,"%.1f",chartLineWidthDp),10,MUTED,false);row.addView(chartLineWidthLabel,params(88,-2));
        SeekBar thickness=new SeekBar(this);thickness.setMax(29);thickness.setProgress(Math.max(0,Math.min(29,Math.round((chartLineWidthDp-.1f)*10f))));thickness.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));thickness.setThumbTintList(android.content.res.ColorStateList.valueOf(BLUE));row.addView(thickness,new LinearLayout.LayoutParams(0,dp(32),1));
        thickness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){chartLineWidthDp=.1f+progress*.1f;chartLineWidthLabel.setText("Linie · "+String.format(Locale.GERMANY,"%.1f",chartLineWidthDp));chart.invalidate();if(fromUser)getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putFloat("chart_line_width_dp",chartLineWidthDp).apply();}@Override public void onStartTrackingTouch(SeekBar bar){}@Override public void onStopTrackingTouch(SeekBar bar){}});
    }
    private void addChartLegendControl(LinearLayout parent){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams rowLp=params(-1,38);parent.addView(row,rowLp);
        chartLegendSizeLabel=text("Schrift · "+String.format(Locale.GERMANY,"%.0f",chartLegendTextSizeSp),10,MUTED,false);row.addView(chartLegendSizeLabel,params(88,-2));
        SeekBar size=new SeekBar(this);size.setMax(7);size.setProgress(Math.max(0,Math.min(7,Math.round(chartLegendTextSizeSp-9f))));size.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));size.setThumbTintList(android.content.res.ColorStateList.valueOf(BLUE));row.addView(size,new LinearLayout.LayoutParams(0,dp(32),1));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){chartLegendTextSizeSp=9f+progress;chartLegendSizeLabel.setText("Schrift · "+String.format(Locale.GERMANY,"%.0f",chartLegendTextSizeSp));chart.invalidate();if(fromUser)getSharedPreferences("cloud_session",MODE_PRIVATE).edit().putFloat("chart_legend_size_sp",chartLegendTextSizeSp).apply();}@Override public void onStartTrackingTouch(SeekBar bar){}@Override public void onStopTrackingTouch(SeekBar bar){}});
    }
    private double energyVal(JSONObject o,String... keys){for(String key:keys)if(o.has(key)&&!o.isNull(key)){Object v=o.opt(key);double d=v instanceof Number?((Number)v).doubleValue():parseNumber(String.valueOf(v),Double.NaN);if(Double.isFinite(d))return d;}return Double.NaN;}
    private double systemBatteryCapacityWh(JSONObject bank){return 5024d+5024d+(3d*2688d);}
    private String formatCapacity(double totalW,int percent){if(!Double.isFinite(totalW)||totalW<=0||percent<0)return percent<0?"— W":"100 % = — W";double currentW=totalW*percent/100d;return new java.text.DecimalFormat("#,##0",java.text.DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(currentW)+" W";}
    private double finite0(double v){return Double.isFinite(v)?v:0;}
    private long latestLiveTimestamp(android.content.SharedPreferences prefs){long newest=0;for(Map.Entry<String,?> entry:prefs.getAll().entrySet()){if(!entry.getKey().startsWith("mqtt_device_")||!(entry.getValue() instanceof String))continue;try{newest=Math.max(newest,new JSONObject((String)entry.getValue()).optLong("received_at",0));}catch(Exception ignored){}}return newest;}
    private long parseTimeMillis(String raw){if(raw==null||raw.isEmpty())return 0;double n=parseNumber(raw,Double.NaN);if(!Double.isNaN(n)){long t=(long)n;return t<100000000000L?t*1000:t;}try{String formatted=formatTime(raw);Date d=new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).parse(formatted);return d==null?0:d.getTime();}catch(Exception e){return 0;}}

    private void renderDevices(JSONObject scene,String selectedSiteId){
        LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();
        mergeArray(accountDevices,all,"");mergeArray(boundDevices,all,"");collectSceneDevices(scene,all,selectedSiteId);
        devicesArea.removeAllViews();
        if(all.isEmpty()){devicesArea.addView(text("Anker hat für dieses Konto keine Gerätedetails geliefert.",14,MUTED,false));return;}
        ArrayList<JSONObject> assigned=new ArrayList<>(),other=new ArrayList<>();
        for(JSONObject device:all.values()){String sys=firstNonEmpty(device.optString("site_id",""),device.optString("_app_site_id",""));if(selectedSiteId.equals(sys))assigned.add(device);else other.add(device);}
        TextView assignedHeader=text("DIESEM SYSTEM ZUGEORDNET · "+assigned.size(),11,MUTED,true);LinearLayout.LayoutParams ahp=params(-1,-2);ahp.bottomMargin=dp(4);devicesArea.addView(assignedHeader,ahp);
        if(assigned.isEmpty())devicesArea.addView(text("Keine zugeordneten Geräte in der Cloud-Antwort gefunden.",14,MUTED,false));
        else for(JSONObject device:assigned)addDeviceRow(device,selectedSiteId);
        TextView allHeader=text("WEITERE HINZUGEFÜGTE GERÄTE · "+other.size(),11,MUTED,true);LinearLayout.LayoutParams ohp=params(-1,-2);ohp.topMargin=dp(14);ohp.bottomMargin=dp(4);devicesArea.addView(allHeader,ohp);
        if(other.isEmpty())devicesArea.addView(text("Keine weiteren Geräte im Konto.",14,MUTED,false));
        else for(JSONObject device:other)addDeviceRow(device,selectedSiteId);
    }

    private void addDeviceRow(JSONObject device,String selectedSiteId){
        LinearLayout item=card(devicesArea,6);LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);item.addView(head,new LinearLayout.LayoutParams(-1,-2));
        String name=firstNonEmpty(device.optString("alias_name",""),device.optString("device_name",""),device.optString("name",""),device.optString("alias",""),device.optString("device_pn",""),device.optString("product_code","Anker-Gerät"));
        TextView title=text(name,15,INK,true);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        boolean online=device.has("wifi_online")&&!device.isNull("wifi_online")?device.optBoolean("wifi_online"):"1".equals(device.optString("status",""));
        TextView state=text(online?"● Online":"● Offline",12,online?GREEN:Color.rgb(177,65,49),true);head.addView(state);
        String model=firstNonEmpty(device.optString("device_pn",""),device.optString("product_code",""),device.optString("device_model",""));
        String sn=firstNonEmpty(device.optString("device_sn",""),device.optString("sn",""));
        TextView detail=text((model.isEmpty()?"Gerät":model)+(sn.isEmpty()?"":" · SN "+sn),12,MUTED,false);LinearLayout.LayoutParams dp=params(-1,-2);dp.topMargin=dp(5);item.addView(detail,dp);
        String site=firstNonEmpty(device.optString("site_id",""),device.optString("_app_site_id",""));String relationship=site.isEmpty()?"Keinem System zugeordnet":site.equals(selectedSiteId)?"Zugeordnet: "+siteNames.getOrDefault(site,"Ausgewähltes System"):"Zugeordnet: "+siteNames.getOrDefault(site,"anderes System");
        TextView assignment=text(relationship,12,MUTED,false);LinearLayout.LayoutParams ap=params(-1,-2);ap.topMargin=dp(3);item.addView(assignment,ap);
        ArrayList<String[]> readings=new ArrayList<>();boolean smartMeter=isSmartMeterGen2(device),smartPlug=isSmartPlugGen2(device);String serial=firstNonEmpty(device.optString("device_sn",""),device.optString("sn",""));JSONObject live=null;try{live=new JSONObject(getSharedPreferences("cloud_session",MODE_PRIVATE).getString("mqtt_device_"+serial,"{}"));}catch(Exception ignored){}if(live!=null){if(smartPlug)collectSmartPlugReadings(live,readings);else if("AE103".equalsIgnoreCase(model)){try{JSONObject safeLive=new JSONObject(live.toString());safeLive.remove("battery_soc");safeLive.remove("temperature");collectReadings(safeLive,readings);}catch(Exception ignored){}}else collectReadings(live,readings);}collectReadings(device,readings);
        if(smartPlug)collectSmartPlugReadings(device,readings);
        if(smartMeter)addMeterGridReadings(sceneGridInfo,readings);
        if(!sceneTime.isEmpty()&&!hasMetric(readings,"Aktualisiert"))readings.add(new String[]{"Systemzeit",formatTime(sceneTime)});
        if(readings.isEmpty()){TextView missing=text(smartMeter?"Anker-Cloud liefert für den Smart Meter gerade keine Messwerte. Phasenwerte können über den lokalen MQTT-Status kommen.":smartPlug?"Anker-Cloud liefert für diesen Smart Plug gerade keine Messwerte.":"Keine Messwerte für dieses Gerät in der Cloud-Antwort",12,MUTED,false);LinearLayout.LayoutParams mp=params(-1,-2);mp.topMargin=dp(8);item.addView(missing,mp);}
        else {TextView readingTitle=text(smartMeter?"SMART-METER-MESSWERTE":smartPlug?"SMART PLUG GEN 2 · ALLE CLOUD-MESSWERTE":"MESSWERTE",10,MUTED,true);LinearLayout.LayoutParams rtp=params(-1,-2);rtp.topMargin=dp(9);item.addView(readingTitle,rtp);for(int i=0;i<readings.size();i+=2){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams rlp=params(-1,-2);rlp.topMargin=dp(5);item.addView(row,rlp);addReadingCell(row,readings.get(i),true);if(i+1<readings.size())addReadingCell(row,readings.get(i+1),false);}}
    }

    private boolean isSmartMeterGen2(JSONObject d){String model=firstNonEmpty(d.optString("device_pn",""),d.optString("product_code",""),d.optString("device_model",""),d.optString("device_name",""),d.optString("alias_name",""),d.optString("name","" )).toUpperCase(Locale.ROOT);return model.contains("AE1X0")||model.contains("AE1X03")||(model.contains("SMART METER")&&(model.contains("GEN 2")||model.contains("GEN2")));}
    private boolean isSmartPlugGen2(JSONObject d){String pn=firstNonEmpty(d.optString("device_pn",""),d.optString("product_code",""),d.optString("device_model","")).toUpperCase(Locale.ROOT);if(pn.equals("A17X8"))return true;String model=firstNonEmpty(d.optString("alias_name",""),d.optString("device_name",""),d.optString("name",""),d.optString("device_pn",""),d.optString("product_code",""),d.optString("device_model","")).toUpperCase(Locale.ROOT).replace("-"," ").replace("_"," ");return model.contains("SMART PLUG")&&(model.contains("GEN2")||model.contains("GEN 2")||model.contains("GENERATION 2"));}
    private void addMeterGridReadings(JSONObject grid,ArrayList<String[]> out){
        addGridPower(grid,out,"grid_to_home_power","Netzbezug");addGridPower(grid,out,"grid_import_power","Netzbezug");addGridPower(grid,out,"photovoltaic_to_grid_power","Einspeisung");addGridPower(grid,out,"grid_export_power","Einspeisung");
        collectReadings(grid,out);
    }
    private void addGridPower(JSONObject grid,ArrayList<String[]> out,String key,String label){String v=grid.optString(key,"");if(v.isEmpty()||hasMetric(out,label))return;double n=parseNumber(v,Double.NaN);if(!Double.isNaN(n))out.add(new String[]{label,String.format(Locale.GERMANY,"%.0f W",n)});}

    private void addReadingCell(LinearLayout row,String[] reading,boolean first){LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(dp(9),dp(7),dp(7),dp(7));cell.setBackground(round(darkTheme?Color.rgb(25,43,61):Color.rgb(245,248,251),11));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(!first)p.leftMargin=dp(6);row.addView(cell,p);cell.addView(text(reading[0],10,MUTED,false));TextView value=text(reading[1],14,INK,true);LinearLayout.LayoutParams vp=params(-1,-2);vp.topMargin=dp(2);cell.addView(value,vp);}
    private void collectReadings(JSONObject object,ArrayList<String[]> out){java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();Object v=object.opt(key);if(v instanceof JSONObject)collectReadings((JSONObject)v,out);else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){JSONObject child=a.optJSONObject(i);if(child!=null)collectReadings(child,out);}}else if(v!=null&&!JSONObject.NULL.equals(v)){String[] reading=readingFor(key,String.valueOf(v));if(reading!=null&&!hasMetric(out,reading[0]))out.add(reading);}}}
    private void collectSmartPlugReadings(JSONObject object,ArrayList<String[]> out){java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();Object value=object.opt(key);if(value instanceof JSONObject){collectSmartPlugReadings((JSONObject)value,out);continue;}if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++){JSONObject child=array.optJSONObject(i);if(child!=null)collectSmartPlugReadings(child,out);}continue;}if(value==null||JSONObject.NULL.equals(value))continue;String raw=String.valueOf(value),k=key.toLowerCase(Locale.ROOT).replace("_","").replace("-","");if(k.matches(".*(switch|relay|plug|output)(state|status|ison|enabled|switch).*")||k.equals("switch")||k.equals("relay")){String state=raw.toLowerCase(Locale.ROOT);if(state.equals("1")||state.equals("true")||state.equals("on"))addMetric(out,"Schaltzustand","Ein");else if(state.equals("0")||state.equals("false")||state.equals("off"))addMetric(out,"Schaltzustand","Aus");continue;}String[] metric=readingFor(key,raw);if(metric!=null){addMetric(out,metric[0],metric[1]);continue;}if(k.matches(".*(power|voltage|current|energy|consumption|frequency|powerfactor|pf|temperature|temp|percent|soc|rssi|runtime|duration|uptime|watt|wattage|amper|volt|kilowatt|watt.?hour|updatedtime|timestamp|updatetime).*")&&!k.matches(".*(sn|serial|deviceid|siteid|timestampid).*")){double number=parseNumber(raw,Double.NaN);if(!Double.isNaN(number)){String label=humanMetricName(key);String unit=k.contains("voltage")||k.contains("volt")?"V":k.contains("current")||k.contains("amper")?"A":k.contains("energy")||k.contains("consumption")||k.contains("watt.?hour")?"Wh":k.contains("frequency")?"Hz":k.contains("temperature")||k.contains("temp")?"°C":k.contains("percent")||k.equals("pf")||k.contains("powerfactor")?"%":k.contains("rssi")?"dBm":k.contains("time")||k.contains("duration")||k.contains("runtime")||k.contains("uptime")?"s":"W";addMetric(out,label,String.format(Locale.GERMANY,"%.2f %s",number,unit));}}}}
    private void addMetric(ArrayList<String[]> out,String label,String value){if(!hasMetric(out,label))out.add(new String[]{label,value});}
    private String humanMetricName(String raw){String spaced=raw.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').replace('-',' ').trim();if(spaced.isEmpty())return "Messwert";String[] parts=spaced.split("\\s+");StringBuilder label=new StringBuilder();for(String part:parts){if(label.length()>0)label.append(' ');label.append(part.substring(0,1).toUpperCase(Locale.GERMANY)).append(part.substring(1));}return label.toString();}
    private String[] readingFor(String rawKey,String rawValue){String k=rawKey.toLowerCase(Locale.ROOT).replace("_","").replace("-","");String label,unit;double n;
        if(k.equals("batterysoc")||k.equals("soc")||k.equals("batterypercentage")||k.equals("chargepercentage")||k.equals("stateofcharge")){label="Ladestand";n=parseNumber(rawValue,Double.NaN);if(Double.isNaN(n))return null;if(n>=0&&n<=1)n*=100;if(n<0||n>100)return null;return new String[]{label,String.format(Locale.GERMANY,"%.0f %%",n)};}
        if(k.contains("voltage")||k.equals("voltage")){label=k.endsWith("l1")?"Spannung L1":k.endsWith("l2")?"Spannung L2":k.endsWith("l3")?"Spannung L3":"Spannung";unit="V";}
        else if(k.contains("current")&&!k.contains("power")){label=k.endsWith("l1")?"Strom L1":k.endsWith("l2")?"Strom L2":k.endsWith("l3")?"Strom L3":"Strom";unit="A";}
        else if(k.equals("gridexportenergy")||k.equals("gridexportenergyl1")||k.equals("gridexportenergyl2")||k.equals("gridexportenergyl3")){label=k.endsWith("l1")?"Einspeisung L1":k.endsWith("l2")?"Einspeisung L2":k.endsWith("l3")?"Einspeisung L3":"Einspeisung gesamt";unit="kWh";}
        else if(k.equals("gridimportenergy")||k.equals("gridimportenergyl1")||k.equals("gridimportenergyl2")||k.equals("gridimportenergyl3")){label=k.endsWith("l1")?"Netzbezug L1":k.endsWith("l2")?"Netzbezug L2":k.endsWith("l3")?"Netzbezug L3":"Netzbezug gesamt";unit="kWh";}
        else if(k.contains("updatedtime")||k.equals("timestamp")||k.equals("updatetime")||k.equals("lastupdatetime")){label="Aktualisiert";return new String[]{label,formatTime(rawValue)};}
        else if(k.contains("temperature")||k.equals("temp")){n=parseNumber(rawValue,Double.NaN);if(Double.isNaN(n)||n< -40||n>100)return null;label="Temperatur";unit="°C";}
        else if(k.contains("frequency")){label="Frequenz";unit="Hz";}
        else if(k.contains("powerfactor")||k.equals("pf")){label="Leistungsfaktor";unit="%";}
        else if(k.contains("energy")||k.contains("consumption")){label=k.contains("today")||k.contains("daily")?"Energie heute":k.contains("total")||k.contains("cumulative")?"Energie gesamt":"Energie";unit=k.contains("kwh")?"kWh":k.contains("wh")?"Wh":"kWh";}
        else if(k.contains("percent")||k.contains("percentage")){label="Prozent";unit="%";}
        else if(k.equals("gridtohomepower")){label="Netzbezug";unit="W";}
        else if(k.equals("photovoltaictogridpower")||k.equals("gridexportpower")){label="Einspeisung";unit="W";}
        else if(k.equals("gridpower")||k.equals("gridpowersigned")||k.matches("gridpowersignedl[123]")){label=k.endsWith("l1")?"Netzleistung L1 ±":k.endsWith("l2")?"Netzleistung L2 ±":k.endsWith("l3")?"Netzleistung L3 ±":"Netzleistung ±";unit="W";}
        else if(k.contains("photovoltaicpower")||k.equals("pvpower")||k.equals("solarpower")){label="Solarleistung";unit="W";}
        else if(k.contains("chargingpower")){label="Ladeleistung";unit="W";}
        else if(k.contains("dischargingpower")){label="Entladeleistung";unit="W";}
        else if(k.contains("outputpower")){label="Ausgangsleistung";unit="W";}
        else if(k.contains("homeloadpower")){label="Hausleistung";unit="W";}
        else if(k.contains("gridtohomepower")){label="Netzleistung";unit="W";}
        else if(k.contains("power")||k.contains("wattage")||k.equals("watt")){label="Leistung";unit="W";}
        else return null;
        n=parseNumber(rawValue,Double.NaN);if(Double.isNaN(n))return null;return new String[]{label,String.format(Locale.GERMANY,"%.1f %s",n,unit)};
    }
    private boolean hasMetric(ArrayList<String[]> metrics,String label){for(String[] a:metrics)if(a[0].equals(label))return true;return false;}
    private String formatTime(String raw){if(raw==null||raw.trim().isEmpty())return raw;String value=raw.trim();double n=parseNumber(value,Double.NaN);if(!Double.isNaN(n)){long millis=(long)n;if(millis<100000000000L)millis*=1000;return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(new Date(millis));}String[] formats={"yyyy-MM-dd HH:mm:ss.SSS","yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ss","yyyy/MM/dd HH:mm:ss","dd.MM.yyyy HH:mm:ss","dd.MM.yyyy HH:mm"};for(String pattern:formats){SimpleDateFormat input=new SimpleDateFormat(pattern,Locale.GERMANY);input.setLenient(false);ParsePosition position=new ParsePosition(0);Date date=input.parse(value,position);if(date!=null&&position.getIndex()==value.length())return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.GERMANY).format(date);}return raw;}

    private void mergeArray(JSONArray source,Map<String,JSONObject> target,String inferredSite){for(int i=0;i<source.length();i++){JSONObject device=source.optJSONObject(i);if(device!=null)addDevice(device,target,inferredSite);}}
    private void collectSceneDevices(Object node,Map<String,JSONObject> target,String siteId){
        if(node instanceof JSONObject){JSONObject object=(JSONObject)node;if(!object.optString("device_sn","").isEmpty())addDevice(object,target,siteId);java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();collectSceneDevices(object.opt(key),target,siteId);}}
        else if(node instanceof JSONArray){JSONArray array=(JSONArray)node;for(int i=0;i<array.length();i++)collectSceneDevices(array.opt(i),target,siteId);}
    }
    private void addDevice(JSONObject source,Map<String,JSONObject> target,String inferredSite){
        String sn=firstNonEmpty(source.optString("device_sn",""),source.optString("sn",""));if(sn.isEmpty())return;
        try{JSONObject merged=target.containsKey(sn)?new JSONObject(target.get(sn).toString()):new JSONObject(source.toString());
            java.util.Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();if(!merged.has(key)||merged.isNull(key))merged.put(key,source.opt(key));}
            if(!inferredSite.isEmpty()&&merged.optString("site_id","").isEmpty())merged.put("_app_site_id",inferredSite);
            target.put(sn,merged);
        }catch(Exception ignored){}
    }

    private void clearMetrics(){if(flowMap!=null)flowMap.setReadings("— W","— W","— W","— W","— W",-1);if(pvStat!=null)pvStat.setText("— W");if(batteryStat!=null)batteryStat.setText("— W");if(homeStat!=null)homeStat.setText("— W");if(gridStat!=null)gridStat.setText("— W");updated.setText("Keine Messwerte von Anker erhalten");}

    private LinearLayout card(LinearLayout parent,int topMargin){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(17),dp(16),dp(17),dp(16));GradientDrawable shape=darkTheme?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(20,38,58),Color.rgb(10,23,39)}):round(CARD,22);shape.setCornerRadius(dp(22));shape.setStroke(dp(1),borderColor());c.setBackground(shape);if(Build.VERSION.SDK_INT>=21)c.setElevation(dp(2));LinearLayout.LayoutParams p=params(-1,-2);p.topMargin=dp(topMargin);parent.addView(c,p);return c;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams params(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private static String firstNonEmpty(String... values){for(String s:values)if(s!=null&&!s.isEmpty()&&!s.equals("null"))return s;return "";}
    private static double parseNumber(String value,double fallback){try{return Double.parseDouble(value);}catch(Exception e){return fallback;}}
    private static String formatKw(double watts){if(!Double.isFinite(watts))return "—";if(Math.abs(watts)<1000d)return String.format(Locale.GERMANY,"%.0f W",watts);return new java.text.DecimalFormat("0.##",java.text.DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(watts/1000d)+" kW";}
    private static String power(String value){double n=parseNumber(value,Double.NaN);if(Double.isNaN(n))return "— W";return Math.abs(n)>=1000?String.format(Locale.GERMANY,"%.2f kW",n/1000d):String.format(Locale.GERMANY,"%.0f W",n);}

    private class FlowConnectorsView extends View {
        private final Paint paint=new Paint(3);
        FlowConnectorsView(android.content.Context c){super(c);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();int[] colors={GREEN,MUTED,BLUE,ORANGE};float busY=h*.36f;paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            Path source=new Path();source.moveTo(w*.76f,dp(1));source.cubicTo(w*.76f,h*.18f,w*.52f,h*.18f,w*.52f,busY);drawGlow(canvas,source,YELLOW,dp(3));
            Path bus=new Path();bus.moveTo(w*.125f,busY);bus.lineTo(w*.875f,busY);drawGlow(canvas,bus,YELLOW,dp(2));
            for(int i=0;i<4;i++){float x=w*(i+.5f)/4f;Path branch=new Path();branch.moveTo(x,busY);branch.cubicTo(x,h*.50f,x,h*.68f,x,h-dp(11));drawGlow(canvas,branch,colors[i],dp(3));paint.clearShadowLayer();paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(90,Color.red(colors[i]),Color.green(colors[i]),Color.blue(colors[i])));canvas.drawCircle(x,h*.70f,dp(5),paint);paint.setColor(colors[i]);canvas.drawCircle(x,h*.70f,dp(3),paint);Path arrow=new Path();arrow.moveTo(x-dp(6),h-dp(14));arrow.lineTo(x+dp(6),h-dp(14));arrow.lineTo(x,h-dp(6));arrow.close();canvas.drawPath(arrow,paint);paint.setStyle(Paint.Style.STROKE);}
            paint.clearShadowLayer();paint.setStyle(Paint.Style.FILL);paint.setColor(YELLOW);canvas.drawCircle(w*.52f,busY,dp(4),paint);
        }
        private void drawGlow(Canvas canvas,Path path,int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width*3f);paint.setColor(Color.argb(55,Color.red(color),Color.green(color),Color.blue(color)));paint.setShadowLayer(dp(7),0,0,color);canvas.drawPath(path,paint);paint.clearShadowLayer();paint.setStrokeWidth(width);paint.setColor(color);canvas.drawPath(path,paint);}
    }

    private class EnergyFlowView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Bitmap hero=BitmapFactory.decodeResource(getResources(),R.drawable.solar_hero_night);
        private final Runnable flowFrame=new Runnable(){@Override public void run(){if(getWindowVisibility()==View.VISIBLE&&isShown()){invalidate();postDelayed(this,40);}}};
        private String pv="— W",grid="— W",export="— W",home="— W",battery="— W";
        private int soc=-1;
        EnergyFlowView(android.content.Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(getWindowVisibility()==View.VISIBLE)startFlowAnimation();}
        @Override protected void onDetachedFromWindow(){stopFlowAnimation();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==View.VISIBLE)startFlowAnimation();else stopFlowAnimation();}
        void startFlowAnimation(){removeCallbacks(flowFrame);if(getWindowVisibility()==View.VISIBLE)postDelayed(flowFrame,40);}
        void stopFlowAnimation(){removeCallbacks(flowFrame);}
        void setReadings(String pv,String grid,String export,String home,String battery,int soc){this.pv=pv;this.grid=grid;this.export=export;this.home=home;this.battery=battery;this.soc=soc;invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density;c.save();c.scale(d,d);float w=getWidth()/d,h=getHeight()/d,cx=w*.51f,ringY=h*.29f,houseX=w*.65f,houseY=h*.59f,statsY=h-54;drawBackground(c,w,h);drawHeroPhoto(c,w,h);
            float tileW=Math.min(132,w*.365f),left=6,right=w-tileW-6;
            // Live connectors are layered over the photographic solar-house scene.
            Path pvPath=new Path();pvPath.moveTo(left+tileW-5,35);pvPath.cubicTo(left+tileW+24,42,cx-82,ringY-5,cx-54,ringY);drawGlow(c,pvPath,YELLOW,2.4f);
            Path homePath=new Path();homePath.moveTo(right+tileW*.50f,66);homePath.cubicTo(w-8,94,w-25,houseY-74,houseX+60,houseY-58);drawGlow(c,homePath,BLUE,2.4f);
            Path batteryPath=new Path();batteryPath.moveTo(cx,ringY+53);batteryPath.cubicTo(cx,ringY+78,cx-1,ringY+95,cx,houseY+17);drawGlow(c,batteryPath,GREEN,2.8f);
            int gridColor=Color.rgb(170,99,255);Path netPath=new Path();netPath.moveTo(cx-47,houseY+42);netPath.cubicTo(cx-78,houseY+40,145,houseY+30,110,houseY+25);drawGlow(c,netPath,gridColor,2.7f);
            Path houseFeed=new Path();houseFeed.moveTo(cx+47,houseY+41);houseFeed.cubicTo(cx+77,houseY+35,houseX-45,houseY+26,houseX-26,houseY+10);drawGlow(c,houseFeed,BLUE,2.4f);
            float phase=(SystemClock.uptimeMillis()%1900L)/1900f;
            if(watts(pv)>0)drawFlowPulse(c,pvPath,YELLOW,phase,false);
            if(watts(home)>0)drawFlowPulse(c,homePath,BLUE,phase,false);
            if(watts(battery)!=0)drawFlowPulse(c,batteryPath,GREEN,phase,watts(battery)>0);
            if(watts(grid)!=0)drawFlowPulse(c,netPath,gridColor,phase,watts(grid)>0);
            if(watts(home)>0)drawFlowPulse(c,houseFeed,BLUE,phase,false);
            drawArrow(c,cx-54,ringY,YELLOW,1,0);drawArrow(c,cx,houseY+17,GREEN,0,1);drawArrow(c,110,houseY+25,gridColor,-1,0);drawArrow(c,houseX+60,houseY-58,BLUE,-.3f,-1);drawArrow(c,houseX-26,houseY+10,BLUE,1,-.35f);
            drawBatteryRing(c,cx,ringY,52);drawStorage(c,cx-33,houseY+20,66,48);
            drawNode(c,left,5,tileW,65,Color.rgb(255,249,225),YELLOW,"☀  PV-ERZEUGUNG",pv,"Aktuelle Leistung",false);
            drawNode(c,right,5,tileW,65,Color.rgb(231,245,255),BLUE,"⌂  HAUSVERBRAUCH",home,"Aktueller Verbrauch",false);
            drawNode(c,6,houseY-6,104,68,Color.rgb(245,235,255),gridColor,"♧  NETZ",grid,"Einspeisung  "+export,false);
            c.restore();
        }
        private void drawHeroPhoto(Canvas c,float w,float h){if(hero==null)return;int save=c.save();Path clip=new Path();clip.addRoundRect(0,0,w,h,20,20,Path.Direction.CW);c.clipPath(clip);float scale=Math.max(w/hero.getWidth(),h/hero.getHeight());float bw=hero.getWidth()*scale,bh=hero.getHeight()*scale,left=(w-bw)*.5f,top=(h-bh)*.5f;p.setAlpha(255);p.setColor(Color.WHITE);p.setShader(null);c.drawBitmap(hero,null,new RectF(left,top,left+bw,top+bh),p);p.setShader(new LinearGradient(0,0,0,h,new int[]{Color.argb(62,4,14,28),Color.argb(15,4,12,24),Color.argb(126,4,13,26)},new float[]{0f,.46f,1f},Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);c.restoreToCount(save);}
        private void drawBackground(Canvas c,float w,float h){p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,0,w,h,darkTheme?Color.rgb(19,37,54):Color.rgb(255,255,255),darkTheme?Color.rgb(9,21,34):Color.rgb(242,248,252),Shader.TileMode.CLAMP));c.drawRoundRect(0,0,w,h,20,20,p);p.setShader(new RadialGradient(w*.52f,h*.51f,Math.min(w,h)*.47f,new int[]{Color.argb(52,30,181,125),Color.argb(16,25,112,112),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,w,h,20,20,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(borderColor());c.drawRoundRect(.5f,.5f,w-1,h-1,20,20,p);p.setStyle(Paint.Style.FILL);}
        private void drawNode(Canvas c,float x,float y,float w,float h,int fill,int accent,String title,String value,String sub,boolean batteryNode){p.setStyle(Paint.Style.FILL);int nodeFill=darkTheme?Color.rgb(20,39,56):fill;p.setShader(new LinearGradient(x,y,x+w,y+h,darkTheme?Color.rgb(31,52,69):Color.rgb(Math.min(255,Color.red(fill)+11),Math.min(255,Color.green(fill)+14),Math.min(255,Color.blue(fill)+18)),nodeFill,Shader.TileMode.CLAMP));p.setShadowLayer(9,0,3,Color.argb(52,Color.red(accent),Color.green(accent),Color.blue(accent)));c.drawRoundRect(x,y,x+w,y+h,13,13,p);p.clearShadowLayer();p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Color.argb(180,Color.red(accent),Color.green(accent),Color.blue(accent)));c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,13,13,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(210,Color.red(accent),Color.green(accent),Color.blue(accent)));c.drawRoundRect(x+1,y+11,x+3,y+h-11,1,1,p);p.setTextAlign(Paint.Align.LEFT);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));p.setTextSize(10);p.setColor(accent);c.drawText(title,x+10,y+17,p);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(18);p.setColor(INK);c.drawText(value,x+10,y+39,p);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(8.5f);p.setColor(MUTED);String fitted=sub;while(p.measureText(fitted)>w-18&&fitted.length()>8)fitted=fitted.substring(0,fitted.length()-2)+"…";c.drawText(fitted,x+10,y+h-8,p);if(batteryNode&&soc>=0){p.setColor(Color.rgb(215,237,228));c.drawRoundRect(x+w-42,y+12,x+w-11,y+17,3,3,p);p.setColor(GREEN);c.drawRoundRect(x+w-42,y+12,x+w-42+31*Math.max(0,Math.min(100,soc))/100f,y+17,3,3,p);}}
        private void drawGlow(Canvas c,Path path,int color,float width){p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(width*3.4f);p.setColor(Color.argb(42,Color.red(color),Color.green(color),Color.blue(color)));p.setShadowLayer(7,0,0,color);c.drawPath(path,p);p.clearShadowLayer();p.setStrokeWidth(width);p.setColor(color);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        private double watts(String raw){if(raw==null)return 0;String v=raw.replace(",",".").replace("kW","").replace("W","").trim();double n=parseNumber(v,0);return raw.contains("kW")?n*1000:n;}
        private void drawFlowPulse(Canvas c,Path path,int color,float phase,boolean reverse){PathMeasure measure=new PathMeasure(path,false);float length=measure.getLength();if(length<=0)return;float[] pos=new float[2];for(int i=0;i<2;i++){float t=(phase+i*.48f)%1f;if(reverse)t=1f-t;if(!measure.getPosTan(length*t,pos,null))continue;p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(68,Color.red(color),Color.green(color),Color.blue(color)));p.setShadowLayer(8,0,0,color);c.drawCircle(pos[0],pos[1],5.2f,p);p.clearShadowLayer();p.setColor(color);c.drawCircle(pos[0],pos[1],2.5f,p);}}
        private void drawArrow(Canvas c,float x,float y,int color,boolean up){p.setStyle(Paint.Style.FILL);p.setColor(color);Path a=new Path();if(up){a.moveTo(x,y);a.lineTo(x-5,y+8);a.lineTo(x+5,y+8);}else{a.moveTo(x,y);a.lineTo(x-5,y-8);a.lineTo(x+5,y-8);}a.close();c.drawPath(a,p);}
        private void drawArrow(Canvas c,float x,float y,int color,float dx,float dy){float n=(float)Math.sqrt(dx*dx+dy*dy);dx/=n;dy/=n;float bx=x-dx*8,by=y-dy*8,px=-dy*4,py=dx*4;p.setStyle(Paint.Style.FILL);p.setColor(color);Path a=new Path();a.moveTo(x,y);a.lineTo(bx+px,by+py);a.lineTo(bx-px,by-py);a.close();c.drawPath(a,p);}
        private void drawBatteryRing(Canvas c,float x,float y,float r){RectF box=new RectF(x-r,y-r,x+r,y+r);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(Color.rgb(218,235,228));c.drawArc(box,-220,260,false,p);p.setColor(Color.argb(80,36,229,145));c.drawArc(box,-220,260,false,p);p.setColor(GREEN);float progress=soc<0?0:Math.max(0,Math.min(100,soc))*2.6f;c.drawArc(box,-220,progress,false,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(38,42,227,151));c.drawCircle(x,y,34,p);p.setColor(GREEN);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(8);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));c.drawText("▣  AKKU",x,y-13,p);p.setTextSize(22);p.setColor(INK);c.drawText(soc<0?"—%":soc+"%",x,y+10,p);p.setTextSize(8);p.setColor(MUTED);c.drawText("Ladezustand",x,y+24,p);p.setTextAlign(Paint.Align.LEFT);p.setStyle(Paint.Style.FILL);}
        private void drawStorage(Canvas c,float x,float y,float w,float h){p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(x,y,x+w,y+h,Color.rgb(133,153,171),Color.rgb(31,46,61),Shader.TileMode.CLAMP));p.setShadowLayer(12,0,5,Color.argb(125,0,0,0));c.drawRoundRect(x,y,x+w,y+h,8,8,p);p.clearShadowLayer();p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Color.rgb(159,193,211));c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,8,8,p);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(7);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));p.setColor(INK);c.drawText("ANKER",x+w/2,y+18,p);p.setTextSize(6);c.drawText("SOLIX",x+w/2,y+26,p);p.setShader(new LinearGradient(x+8,y+h-12,x+w-8,y+h-12,Color.rgb(0,85,255),Color.rgb(60,208,255),Shader.TileMode.CLAMP));c.drawRoundRect(x+9,y+h-13,x+w-9,y+h-10,2,2,p);p.setShader(null);p.setTextAlign(Paint.Align.LEFT);}
        private void drawMini(Canvas c,float x,float y,float w,float h,int accent,String label,String value){p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(x,y,x,y+h,darkTheme?Color.rgb(23,42,59):Color.rgb(255,255,255),darkTheme?Color.rgb(14,29,44):Color.rgb(245,248,251),Shader.TileMode.CLAMP));c.drawRoundRect(x,y,x+w,y+h,9,9,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.8f);p.setColor(borderColor());c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,9,9,p);p.setStyle(Paint.Style.FILL);p.setColor(accent);p.setTextSize(8);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));c.drawText(label,x+7,y+16,p);p.setColor(INK);p.setTextSize(11);c.drawText(value,x+7,y+35,p);}
        private void drawHouse(Canvas c,float cx,float cy){p.setStyle(Paint.Style.FILL);p.setAlpha(255);p.setShader(new RadialGradient(cx,cy+8,94,new int[]{Color.argb(90,35,177,125),Color.argb(24,28,116,115),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));c.drawCircle(cx,cy+8,94,p);p.setShader(null);p.setAlpha(255);p.setColor(Color.rgb(19,70,60));c.drawOval(cx-110,cy+34,cx+108,cy+82,p);p.setColor(Color.argb(105,0,0,0));c.drawOval(cx-67,cy+49,cx+82,cy+66,p);drawTree(c,cx-101,cy+20);drawTree(c,cx+82,cy+16);p.setAlpha(255);
            if(houseView3D){Path side=new Path();side.moveTo(cx+3,cy-7);side.lineTo(cx+71,cy-39);side.lineTo(cx+71,cy+38);side.lineTo(cx+3,cy+62);side.close();p.setShader(new LinearGradient(cx+3,cy,cx+71,cy,Color.rgb(211,224,227),Color.rgb(135,157,169),Shader.TileMode.CLAMP));c.drawPath(side,p);p.setShader(null);Path front=new Path();front.moveTo(cx-69,cy-7);front.lineTo(cx+3,cy-7);front.lineTo(cx+3,cy+62);front.lineTo(cx-69,cy+38);front.close();p.setShader(new LinearGradient(cx-69,cy,cx+3,cy,Color.rgb(255,251,229),Color.rgb(217,228,225),Shader.TileMode.CLAMP));c.drawPath(front,p);p.setShader(null);Path roofA=new Path();roofA.moveTo(cx-82,cy-8);roofA.lineTo(cx-5,cy-78);roofA.lineTo(cx+10,cy-72);roofA.lineTo(cx-54,cy-2);roofA.close();p.setColor(Color.rgb(49,61,73));c.drawPath(roofA,p);Path roofB=new Path();roofB.moveTo(cx-5,cy-78);roofB.lineTo(cx+87,cy-36);roofB.lineTo(cx+71,cy-17);roofB.lineTo(cx+10,cy-72);roofB.close();p.setColor(Color.rgb(39,51,63));c.drawPath(roofB,p);drawSolarPanel(c,cx-35,cy-62,47,43);drawWindow(c,cx-51,cy+8);drawWindow(c,cx+31,cy-2);p.setColor(Color.rgb(137,92,62));c.drawRoundRect(cx-8,cy+27,cx+8,cy+61,2,2,p);}
            else{p.setShader(new LinearGradient(cx-70,cy,cx+70,cy,Color.rgb(255,252,235),Color.rgb(209,222,221),Shader.TileMode.CLAMP));c.drawRoundRect(cx-69,cy-5,cx+69,cy+62,3,3,p);p.setShader(null);Path roof=new Path();roof.moveTo(cx-87,cy-5);roof.lineTo(cx,cy-79);roof.lineTo(cx+87,cy-5);roof.close();p.setShader(new LinearGradient(cx-87,cy-65,cx+87,cy,Color.rgb(103,124,140),Color.rgb(71,91,107),Shader.TileMode.CLAMP));c.drawPath(roof,p);p.setShader(null);drawSolarPanel(c,cx-36,cy-65,70,41);drawWindow(c,cx-49,cy+7);drawWindow(c,cx+30,cy+7);p.setColor(Color.rgb(141,85,55));c.drawRoundRect(cx-9,cy+28,cx+9,cy+62,2,2,p);}
            p.setColor(Color.rgb(88,117,130));p.setStrokeWidth(1);p.setStyle(Paint.Style.STROKE);c.drawLine(cx-70,cy+5,cx+69,cy+5,p);p.setStyle(Paint.Style.FILL);
        }
        private void drawSolarPanel(Canvas c,float x,float y,float w,float h){p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(x,y,x+w,y+h,Color.rgb(33,140,210),Color.rgb(8,55,100),Shader.TileMode.CLAMP));p.setShadowLayer(7,0,0,Color.rgb(20,125,208));c.drawRoundRect(x,y,x+w,y+h,3,3,p);p.clearShadowLayer();p.setShader(null);p.setColor(Color.rgb(129,213,255));p.setStrokeWidth(.85f);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(x,y,x+w,y+h,3,3,p);for(int i=1;i<4;i++)c.drawLine(x+w*i/4f,y,x+w*i/4f,y+h,p);for(int i=1;i<3;i++)c.drawLine(x,y+h*i/3f,x+w,y+h*i/3f,p);p.setStyle(Paint.Style.FILL);}
        private void drawWindow(Canvas c,float x,float y){p.setStyle(Paint.Style.FILL);p.setAlpha(255);p.setColor(Color.argb(75,255,191,74));c.drawRoundRect(x-4,y-4,x+23,y+25,5,5,p);p.setAlpha(255);p.setShader(new LinearGradient(x,y,x+18,y+20,Color.rgb(255,211,115),Color.rgb(232,138,55),Shader.TileMode.CLAMP));c.drawRoundRect(x,y,x+18,y+20,2,2,p);p.setShader(null);p.setAlpha(255);p.setColor(Color.argb(210,255,240,190));p.setStrokeWidth(1);c.drawLine(x+9,y,x+9,y+20,p);c.drawLine(x,y+10,x+18,y+10,p);p.setAlpha(255);}
        private void drawTree(Canvas c,float x,float y){p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(82,112,66));c.drawRoundRect(x-2,y+8,x+2,y+42,2,2,p);p.setShader(new RadialGradient(x,y,18,new int[]{Color.rgb(76,160,112),Color.rgb(26,91,75)},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,18,p);p.setShader(null);p.setColor(Color.argb(95,255,255,255));c.drawCircle(x-6,y-6,3,p);}
    }

    private class SolarHouseView extends View {
        private final Paint p=new Paint(3);
        SolarHouseView(android.content.Context c){super(c);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();if(houseView3D){drawIsometric(c,w,h);return;}p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(226,239,248));c.drawRoundRect(0,0,w,h,dp(18),dp(18),p);p.setColor(Color.rgb(255,218,91));c.drawCircle(w*.83f,h*.18f,dp(19),p);p.setColor(Color.rgb(25,73,64));c.drawOval(w*.04f,h*.68f,w*.96f,h*.96f,p);
            Path roof=new Path();roof.moveTo(w*.22f,h*.50f);roof.lineTo(w*.48f,h*.10f);roof.lineTo(w*.78f,h*.50f);roof.close();p.setColor(Color.rgb(104,125,141));c.drawPath(roof,p);
            p.setColor(Color.rgb(247,248,246));c.drawRect(w*.27f,h*.48f,w*.75f,h*.83f,p);p.setColor(Color.rgb(190,211,224));c.drawRect(w*.31f,h*.57f,w*.43f,h*.71f,p);c.drawRect(w*.56f,h*.56f,w*.70f,h*.69f,p);
            Path panel=new Path();panel.moveTo(w*.43f,h*.30f);panel.lineTo(w*.61f,h*.19f);panel.lineTo(w*.72f,h*.40f);panel.lineTo(w*.53f,h*.45f);panel.close();p.setColor(Color.rgb(35,108,169));c.drawPath(panel,p);p.setColor(Color.rgb(130,194,235));p.setStrokeWidth(dp(1));p.setStyle(Paint.Style.STROKE);for(int i=1;i<4;i++){float f=i/4f;c.drawLine(w*(.43f+.10f*f),h*(.30f-.11f*f),w*(.53f+.19f*f),h*(.45f-.05f*f),p);}for(int i=1;i<4;i++){float f=i/4f;c.drawLine(w*(.43f+.18f*f),h*(.30f+.15f*f),w*(.61f+.11f*f),h*(.19f+.21f*f),p);}p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(91,151,82));c.drawCircle(w*.19f,h*.62f,dp(14),p);c.drawCircle(w*.14f,h*.66f,dp(10),p);c.drawRect(w*.18f,h*.65f,w*.20f,h*.83f,p);p.setColor(Color.rgb(112,163,82));c.drawCircle(w*.83f,h*.63f,dp(13),p);c.drawRect(w*.82f,h*.65f,w*.84f,h*.83f,p);
        }
        private void drawIsometric(Canvas c,float w,float h){
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(226,239,248));c.drawRoundRect(0,0,w,h,dp(18),dp(18),p);
            p.setColor(Color.rgb(255,217,91));c.drawCircle(w*.84f,h*.19f,dp(17),p);
            p.setColor(Color.rgb(25,73,64));c.drawOval(w*.07f,h*.68f,w*.94f,h*.98f,p);
            p.setColor(Color.argb(45,32,54,52));c.drawOval(w*.25f,h*.71f,w*.78f,h*.87f,p);
            // Side walls of the house, drawn as separate light and shaded planes.
            polygon(c,Color.rgb(250,250,245),w*.29f,h*.45f,w*.53f,h*.54f,w*.53f,h*.84f,w*.29f,h*.72f);
            polygon(c,Color.rgb(216,225,228),w*.53f,h*.54f,w*.76f,h*.43f,w*.76f,h*.72f,w*.53f,h*.84f);
            // Two roof planes create the isometric silhouette.
            polygon(c,Color.rgb(104,125,141),w*.25f,h*.46f,w*.49f,h*.16f,w*.55f,h*.20f,w*.34f,h*.49f);
            polygon(c,Color.rgb(82,103,119),w*.49f,h*.16f,w*.79f,h*.34f,w*.76f,h*.45f,w*.55f,h*.20f);
            // Solar array on the front-facing roof plane.
            polygon(c,Color.rgb(32,101,164),w*.38f,h*.35f,w*.50f,h*.22f,w*.65f,h*.31f,w*.53f,h*.44f);
            p.setColor(Color.rgb(133,198,235));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));
            for(int i=1;i<4;i++){float f=i/4f;c.drawLine(w*(.38f+.12f*f),h*(.35f-.13f*f),w*(.53f+.12f*f),h*(.44f-.13f*f),p);}
            for(int i=1;i<4;i++){float f=i/4f;c.drawLine(w*(.38f+.15f*f),h*(.35f+.09f*f),w*(.50f+.15f*f),h*(.22f+.09f*f),p);}p.setStyle(Paint.Style.FILL);
            // Windows and entrance follow the two wall planes.
            polygon(c,Color.rgb(122,185,216),w*.34f,h*.52f,w*.42f,h*.55f,w*.42f,h*.64f,w*.34f,h*.60f);
            polygon(c,Color.rgb(122,185,216),w*.59f,h*.56f,w*.68f,h*.52f,w*.68f,h*.62f,w*.59f,h*.66f);
            polygon(c,Color.rgb(145,96,65),w*.46f,h*.60f,w*.52f,h*.62f,w*.52f,h*.83f,w*.46f,h*.80f);
            p.setColor(Color.rgb(94,151,83));c.drawCircle(w*.17f,h*.62f,dp(13),p);c.drawCircle(w*.20f,h*.59f,dp(11),p);c.drawRect(w*.18f,h*.63f,w*.20f,h*.82f,p);
            p.setColor(Color.rgb(108,161,84));c.drawCircle(w*.84f,h*.60f,dp(13),p);c.drawRect(w*.83f,h*.62f,w*.85f,h*.82f,p);
        }
        private void polygon(Canvas c,int color,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);path.close();p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawPath(path,p);}
    }
    private class LiveChart extends View {
        private final Paint p=new Paint(3);private java.util.List<float[]> values=new ArrayList<>();private int selected=-1;private float scaleMax=1000f,scaleMin=0f;private String singleSeriesLabel="";private TextView pointLabel;private boolean touched=false,percentMode=false;
        LiveChart(android.content.Context c){super(c);setLayerType(View.LAYER_TYPE_NONE,null);}
        void setSingleSeries(String label,TextView valueLabel){singleSeriesLabel=label;pointLabel=valueLabel;invalidate();}
        void setPercentMode(boolean enabled){percentMode=enabled;invalidate();}
        void setValues(java.util.List<float[]> v){values=new ArrayList<>(v);if(selected>=values.size())selected=values.size()-1;if(selected<0&&!values.isEmpty())selected=values.size()-1;updatePointLabel();invalidate();}
        void setValues(java.util.List<float[]> v,int focus){values=new ArrayList<>(v);if(!touched||selected<0)selected=Math.max(0,Math.min(values.size()-1,focus));updatePointLabel();invalidate();}
        @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE){if(values.size()>0){touched=true;float left=dp(48),right=getWidth()-dp(4);selected=Math.max(0,Math.min(values.size()-1,Math.round((event.getX()-left)/(right-left)*(values.size()-1))));updatePointLabel();invalidate();}return true;}if(event.getActionMasked()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        private void updatePointLabel(){TextView target=pointLabel!=null?pointLabel:chartPointLabel;if(target==null)return;if(!touched){target.setVisibility(View.GONE);return;}target.setVisibility(View.VISIBLE);if(values.isEmpty()){target.setText("Werte: Tippe ins Diagramm");return;}float[] a=values.get(Math.max(0,Math.min(selected,values.size()-1)));if(a.length==0||!Float.isFinite(a[0])){target.setText(singleSeriesLabel.isEmpty()?"Keine Messung für diesen Zeitpunkt":singleSeriesLabel+" · keine Messung");return;}if(percentMode)target.setText(singleSeriesLabel+" · "+String.format(Locale.GERMANY,"%.0f%%",a[0]));else if(!singleSeriesLabel.isEmpty())target.setText(singleSeriesLabel+" · "+kw(a,0));else target.setText("Solar "+kw(a,0)+"  ·  Haus "+kw(a,1)+"\nAkku "+kw(a,2)+"  ·  Netz "+kw(a,3));}
        private String kw(float[] a,int i){return a.length>i&&Float.isFinite(a[i])?formatKw(a[i]):"—";}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight(),left=dp(36),right=w-dp(4),top=dp(6),bottom=h-dp(30);if(right<=left||bottom<=top)return;
            p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,top,0,bottom,darkTheme?Color.argb(22,25,116,162):Color.argb(20,36,151,210),Color.TRANSPARENT,Shader.TileMode.CLAMP));c.drawRoundRect(left,top,right,bottom,dp(8),dp(8),p);p.setShader(null);p.setTextSize(dp(8));p.setColor(MUTED);
            if(values.size()<2){String message="Verlauf wird gesammelt";float mw=p.measureText(message);c.drawText(message,Math.max(left,(w+left-mw)/2f),top+(bottom-top)*.52f,p);return;}
            float maxPositive=.5f,maxNegative=0;for(float[] a:values)for(float v:a)if(Float.isFinite(v)){if(v>maxPositive)maxPositive=v;else if(v<0)maxNegative=Math.max(maxNegative,-v);}
            float[] niceSteps={1,2,3,4,5,6,8,10,15,20,25,30,40,50,60,80,100,150,200,250,300,400,500,600,800,1000,1500,2000,2500,3000,4000,5000,6000,8000,10000,15000,20000,30000,50000};
            scaleMax=percentMode?100f:niceCeiling(maxPositive*1.35f,niceSteps);scaleMin=percentMode?0f:(maxNegative>0?-niceCeiling(maxNegative*1.35f,niceSteps):0f);if(scaleMax<=scaleMin)scaleMax=scaleMin+1;
            p.setStrokeWidth(dp(1));for(int i=0;i<5;i++){float value=scaleMax-(scaleMax-scaleMin)*i/4f,y=top+(bottom-top)*i/4f;p.setColor(Math.abs(value)<.001f?Color.argb(135,46,215,151):darkTheme?Color.rgb(24,43,62):Color.rgb(226,233,240));c.drawLine(left,y,right,y,p);p.setTextSize(dp(8));p.setColor(MUTED);String label=percentMode?String.format(Locale.GERMANY,"%.0f%%",value):formatKw(value);c.drawText(label,Math.max(0,left-dp(34)),y+dp(3),p);}
            drawLine(c,0,left,right,top,bottom,scaleMin,scaleMax,percentMode?GREEN:YELLOW);if(singleSeriesLabel.isEmpty()){drawLine(c,1,left,right,top,bottom,scaleMin,scaleMax,BLUE);drawLine(c,2,left,right,top,bottom,scaleMin,scaleMax,GREEN);drawLine(c,3,left,right,top,bottom,scaleMin,scaleMax,Color.rgb(0,222,175));}
            if(selected>=0&&selected<values.size()){float x=left+(right-left)*selected/Math.max(1,values.size()-1);p.setColor(darkTheme?Color.rgb(150,170,190):Color.rgb(125,143,160));p.setStrokeWidth(dp(1));p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3),dp(3)},0));c.drawLine(x,top,x,bottom,p);p.setPathEffect(null);for(int j=0;j<4;j++){float val=values.get(selected).length>j?values.get(selected)[j]:Float.NaN;if(!singleSeriesLabel.isEmpty()&&j>0)continue;if(!Float.isFinite(val))continue;float y=top+(bottom-top)*(scaleMax-Math.max(scaleMin,Math.min(val,scaleMax)))/(scaleMax-scaleMin);p.setColor(percentMode?GREEN:new int[]{YELLOW,BLUE,GREEN,Color.rgb(0,222,175)}[j]);c.drawCircle(x,y,2.5f*getResources().getDisplayMetrics().density,p);}}
            String[] x=chartXAxisLabels();p.setTextSize(dp(7));p.setColor(MUTED);for(int i=0;i<x.length;i++){float xx=left+(right-left)*i/(x.length-1f);float tw=p.measureText(x[i]);c.drawText(x[i],Math.max(left,Math.min(right-tw,xx-tw/2f)),bottom+dp(10),p);}
            p.setTextSize(chartLegendTextSizeSp*getResources().getDisplayMetrics().scaledDensity);float legendY=h-dp(2),slot=(right-left)/4f;if(!singleSeriesLabel.isEmpty()){p.setColor(YELLOW);c.drawText("● "+singleSeriesLabel,left,legendY,p);}else{p.setColor(YELLOW);c.drawText("● PV-Erzeugung",left,legendY,p);p.setColor(BLUE);c.drawText("● Verbrauch",left+slot,legendY,p);p.setColor(GREEN);c.drawText("● Batterie",left+slot*2,legendY,p);p.setColor(Color.rgb(0,222,175));c.drawText("● Netz",left+slot*3,legendY,p);}
        }
        private float niceCeiling(float desired,float[] steps){for(float step:steps)if(step>=desired)return step;return steps[steps.length-1];}
        private String[] chartXAxisLabels(){if(selectedChartPeriod.equals("Tag"))return new String[]{"00:00","04:00","08:00","12:00","16:00","20:00","24:00"};if(selectedChartPeriod.equals("Woche"))return new String[]{"−6T","−5T","−4T","−3T","−2T","−1T","Heute"};if(selectedChartPeriod.equals("Monat"))return new String[]{"−30T","−25T","−20T","−15T","−10T","−5T","Heute"};return new String[]{"Jan","Mär","Mai","Jul","Sep","Nov","Dez"};}
        private void drawLine(Canvas c,int index,float left,float right,float top,float bottom,float min,float max,int color){if(values.size()<2)return;float[] xs=new float[values.size()],ys=new float[values.size()];for(int i=0;i<values.size();i++){xs[i]=left+(right-left)*i/(values.size()-1);float v=values.get(i).length>index?values.get(i)[index]:Float.NaN;if(!Float.isFinite(v)){ys[i]=Float.NaN;continue;}float clipped=Math.max(min,Math.min(v,max));ys[i]=percentMode?top+(bottom-top)*(1f-Math.max(0f,Math.min(v,100f))/100f):top+(bottom-top)*(max-clipped)/(max-min);}Path path=new Path();boolean open=false;float prevX=0,prevY=0,firstX=0,lastX=0;for(int i=0;i<xs.length;i++){if(!Float.isFinite(ys[i])){open=false;continue;}if(!open){path.moveTo(xs[i],ys[i]);firstX=xs[i];open=true;}else{float midX=(prevX+xs[i])*.5f,midY=(prevY+ys[i])*.5f;path.quadTo(prevX,prevY,midX,midY);}prevX=xs[i];prevY=ys[i];lastX=xs[i];}if(open)path.lineTo(lastX,prevY);if(index<3&&singleSeriesLabel.isEmpty()){Path area=new Path(path);float zero=top+(bottom-top)*(max-Math.max(min,Math.min(0f,max)))/(max-min);area.lineTo(lastX,zero);area.lineTo(firstX,zero);area.close();p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,top,0,bottom,Color.argb(55,Color.red(color),Color.green(color),Color.blue(color)),Color.argb(4,Color.red(color),Color.green(color),Color.blue(color)),Shader.TileMode.CLAMP));c.drawPath(area,p);p.setShader(null);}if(darkTheme){p.setColor(Color.argb(72,Color.red(color),Color.green(color),Color.blue(color)));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(dp(3),chartLineWidthDp*dp(3)));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);if(index==3)p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4),dp(4)},0));c.drawPath(path,p);p.setPathEffect(null);}p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(chartLineWidthDp*getResources().getDisplayMetrics().density);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);if(index==3)p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4),dp(4)},0));c.drawPath(path,p);p.setPathEffect(null);p.setStyle(Paint.Style.FILL);}
    }
}
