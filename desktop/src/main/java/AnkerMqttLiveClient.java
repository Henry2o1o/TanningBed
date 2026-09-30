

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Experimental Anker cloud MQTT listener for supported SOLIX telemetry. */
final class AnkerMqttLiveClient {
    interface Listener { void onStatus(String status); }
    private final DesktopPreferences prefs;
    private final JSONArray devices;
    private final JSONObject mqttInfo;
    private final Listener listener;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService connectionExecutor=Executors.newSingleThreadExecutor();
    private volatile SSLSocket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile java.util.concurrent.ScheduledFuture<?> triggerTask,pingTask;
    private String appName="anker_power", userId="", clientId="";
    private volatile boolean stopped;

    AnkerMqttLiveClient(DesktopPreferences prefs, JSONObject mqttInfo, JSONArray devices, Listener listener) {
        this.prefs=prefs; this.mqttInfo=mqttInfo; this.devices=devices; this.listener=listener;
    }

    void start() { connectionExecutor.execute(this::connectAndListen); }

    private void connectAndListen(){
        try{
            String host=first(mqttInfo,"endpoint_addr","endpoint","host");String thing=first(mqttInfo,"thing_name","client_id");
            appName=first(mqttInfo,"app_name");if(appName.isEmpty())appName="anker_power";userId=first(mqttInfo,"user_id","account_id");String certId=first(mqttInfo,"certificate_id","cert_id");clientId="android-"+appName+"-"+userId+"-"+certId;
            if(host.isEmpty()||thing.isEmpty())throw new Exception("Anker lieferte keine vollständigen MQTT-Verbindungsdaten.");
            SSLSocketFactory factory=socketFactory(mqttInfo);socket=(SSLSocket)factory.createSocket(host,8883);javax.net.ssl.SSLParameters sslParameters=socket.getSSLParameters();sslParameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(sslParameters);socket.setSoTimeout(5000);socket.startHandshake();input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());
            writePacket(0x10,connectBody(thing));byte[] connack=readPacket();if(connack==null||connack.length<3||connack[2]!=0)throw new Exception("Anker-MQTT-Anmeldung abgelehnt.");
            status("Echtzeitkanal verbunden");subscribeAndTrigger();pingTask=scheduler.scheduleAtFixedRate(()->{try{if(socket!=null&&!socket.isClosed())writePacket(0xc0,new byte[0]);}catch(Exception ignored){}},20,20,TimeUnit.SECONDS);
            while(!stopped&&socket!=null&&!socket.isClosed()){
                try{byte[] packet=readPacket();if(packet==null)continue;int type=(packet[0]>>4)&15;if(type==3){handlePublish(packet);if(((packet[0]>>1)&3)==1)sendPubAck(packet);} }
                catch(java.net.SocketTimeoutException ignored){}
            }
        }catch(Exception e){if(!stopped)status("Echtzeitkanal getrennt · verbinde erneut");cancelTasks();closeSocket();if(!stopped)scheduler.schedule(()->{if(!stopped)connectionExecutor.execute(this::connectAndListen);},10,TimeUnit.SECONDS);}
    }

    private void subscribeAndTrigger()throws Exception{
        ArrayList<JSONObject> supported=new ArrayList<>();
        for(int i=0;i<devices.length();i++){
            JSONObject d=devices.optJSONObject(i); if(d==null)continue;
            String pn=first(d,"device_pn","product_code","device_model").toUpperCase(Locale.ROOT);
            if(!pn.equals("AE103")&&!pn.equals("A17X8")&&!pn.equals("AE1X0"))continue;
            String sn=first(d,"device_sn","sn"); if(sn.isEmpty())continue;
            supported.add(d);
            byte[] topic=("dt/"+appName+"/"+pn+"/"+sn+"/#").getBytes(StandardCharsets.UTF_8);ByteBuffer body=ByteBuffer.allocate(2+2+topic.length+1).order(ByteOrder.BIG_ENDIAN);body.putShort((short)1).putShort((short)topic.length).put(topic).put((byte)0);writePacket(0x82,body.array());
        }
        triggerTask=scheduler.scheduleAtFixedRate(()->{for(JSONObject d:supported)publishRealtimeTrigger(d);},1,240,TimeUnit.SECONDS);
        if(supported.isEmpty())status("MQTT verbunden · keine unterstützten Geräte in der Geräteliste gefunden");
    }

    private void publishRealtimeTrigger(JSONObject device){
        try {
            if(stopped||socket==null||socket.isClosed())return;
            String pn=first(device,"device_pn","product_code","device_model").toUpperCase(Locale.ROOT), sn=first(device,"device_sn","sn");
            long now=System.currentTimeMillis()/1000L;
            byte[] command=realtimeCommand(now);
            JSONObject payload=new JSONObject().put("account_id",userId).put("device_sn",sn).put("data",java.util.Base64.getEncoder().encodeToString(command));
            JSONObject head=new JSONObject().put("version","1.0.0.1").put("client_id",clientId).put("sess_id",Integer.toHexString((int)(now&0xffff))+"-"+Integer.toHexString((int)(System.nanoTime()&0xffff))).put("msg_seq",now&0x7fffffff).put("seed",1).put("timestamp",now).put("cmd_status",2).put("cmd",17).put("sign_code",1).put("device_pn",pn).put("device_sn",sn);
            JSONObject envelope=new JSONObject().put("head",head).put("payload",payload.toString());
            byte[] topic=("cmd/"+appName+"/"+pn+"/"+sn+"/req").getBytes(StandardCharsets.UTF_8),payloadBytes=envelope.toString().getBytes(StandardCharsets.UTF_8);ByteBuffer packet=ByteBuffer.allocate(2+topic.length+payloadBytes.length).order(ByteOrder.BIG_ENDIAN);packet.putShort((short)topic.length).put(topic).put(payloadBytes);writePacket(0x30,packet.array());
        }catch(Exception ignored){}
    }

    private byte[] connectBody(String thing)throws Exception{byte[] protocol="MQTT".getBytes(StandardCharsets.UTF_8),id=thing.getBytes(StandardCharsets.UTF_8);ByteBuffer b=ByteBuffer.allocate(2+protocol.length+4+2+id.length).order(ByteOrder.BIG_ENDIAN);b.putShort((short)protocol.length).put(protocol).put((byte)4).put((byte)2).putShort((short)45).putShort((short)id.length).put(id);return b.array();}
    private synchronized void writePacket(int header,byte[] body)throws Exception{if(output==null)throw new Exception("MQTT-Verbindung geschlossen");output.writeByte(header);int n=body.length;do{int d=n%128;n/=128;if(n>0)d|=0x80;output.writeByte(d);}while(n>0);output.write(body);output.flush();}
    private byte[] readPacket()throws Exception{int header=input.readUnsignedByte(),multiplier=1,length=0,d;do{d=input.readUnsignedByte();length+=(d&127)*multiplier;multiplier*=128;}while((d&128)!=0);byte[] body=new byte[length+1];body[0]=(byte)header;input.readFully(body,1,length);return body;}
    private void handlePublish(byte[] packet)throws Exception{if(packet.length<4)return;int topicLen=((packet[1]&255)<<8)|(packet[2]&255);if(topicLen<1||packet.length<3+topicLen)return;String topic=new String(packet,3,topicLen,StandardCharsets.UTF_8);int qos=(packet[0]>>1)&3;int offset=3+topicLen;if(qos>0)offset+=2;if(offset<packet.length)handleMessage(topic,java.util.Arrays.copyOfRange(packet,offset,packet.length));}
    private void sendPubAck(byte[] packet)throws Exception{int t=((packet[1]&255)<<8)|(packet[2]&255),n=3+t;if(n+1>=packet.length)return;writePacket(0x40,new byte[]{packet[n],packet[n+1]});}

    private byte[] realtimeCommand(long timestamp){
        ArrayList<Byte> b=new ArrayList<>(); add(b,0xff,0x09,0,0,0x03,0,0x0f,0,0x57,0xa1,1,0x22,0xa2,2,1,1,0xa3,5,3,0x2c,1,0,0,0xfe,5,3);
        for(int i=0;i<4;i++)b.add((byte)((timestamp>>(8*i))&255));
        b.set(2,(byte)((b.size()+1)&255)); b.set(3,(byte)((b.size()+1)>>8)); int xor=0; for(byte v:b)xor^=v; b.add((byte)xor);
        byte[] result=new byte[b.size()];for(int i=0;i<b.size();i++)result[i]=b.get(i);return result;
    }
    private static void add(ArrayList<Byte>b,int...v){for(int x:v)b.add((byte)x);}

    private void handleMessage(String topic,byte[] raw)throws Exception{
        String pn="",sn="";String[] parts=topic.split("/");if(parts.length>=4){pn=parts[2];sn=parts[3];}
        JSONObject outer=new JSONObject(new String(raw,StandardCharsets.UTF_8));String encoded=findData(outer,0);if(encoded.isEmpty())return;
        byte[] bytes=java.util.Base64.getMimeDecoder().decode(encoded);Map<String,Double> vals=decode(bytes,pn);if(vals.isEmpty())return;
        JSONObject record=new JSONObject();for(Map.Entry<String,Double> e:vals.entrySet())record.put(e.getKey(),e.getValue());record.put("device_pn",pn);record.put("device_sn",sn);record.put("received_at",System.currentTimeMillis());
        DesktopPreferences p=prefs;String key="mqtt_device_"+sn;JSONObject merged=new JSONObject();try{merged=new JSONObject(p.getString(key,"{}"));}catch(Exception ignored){}java.util.Iterator<String> keys=record.keys();while(keys.hasNext()){String k=keys.next();merged.put(k,record.get(k));}
        p.putString(key,merged.toString());p.putString("mqtt_last_received",String.valueOf(System.currentTimeMillis()));status("Live-Messwerte empfangen");
    }

    private Map<String,Double> decode(byte[] b,String pn){
        if(b.length<12||(b[0]&255)!=0xff||(b[1]&255)!=0x09)return Collections.emptyMap();
        int type=((b[7]&255)<<8)|(b[8]&255); if(type!=0x0405&&type!=0x040a&&type!=0x0408&&type!=0x0420)return Collections.emptyMap();
        HashMap<String,Double> out=new HashMap<>();int i=9,end=b.length-1;
        while(i+2<=end){int id=b[i++]&255,len=b[i++]&255;if(len<1||i+len>end)break;int typ;int count;if(len==1){typ=0;count=1;}else{typ=b[i++]&255;count=len-1;}if(count<=0||i+count>end)break;long unsigned=0;for(int j=0;j<Math.min(count,8);j++)unsigned|=((long)b[i+j]&255)<<(8*j);long signed=unsigned;if(count<8&&(b[i+count-1]&0x80)!=0)signed|=(-1L)<<(count*8);double value;
            if(typ==2||typ==3)value=(double)signed;else if(typ==5&&count==4)value=ByteBuffer.wrap(b,i,count).order(ByteOrder.LITTLE_ENDIAN).getFloat();else value=(double)unsigned;
            String key=String.format(Locale.ROOT,"%02x",id);
            boolean pvChannel=pn.equals("AE103")&&key.matches("c[6-9]");
            if(pvChannel&&type!=0x0405){i+=count;continue;}
            String name=fieldName(pn,type,key);
            if(name!=null){
                if(pvChannel&&(typ!=5||count!=4||!Double.isFinite(value)||value<0||value>5000))value=-1d;
                double factor=fieldFactor(pn,key);out.put(name,value*factor);
            }
            i+=count;
        }
        return out;
    }
    private String fieldName(String pn,int type,String key){
        if(pn.equals("A17X8")){switch(key){case "a4":return "switch_state";case "a8":return "voltage";case "a9":return "current";case "aa":return "power";case "ab":return "output_energy";case "fe":return "timestamp";}}
        if(pn.equals("AE1X0")){switch(key){case "a8":return "grid_power_signed_l1";case "a9":return "grid_power_signed_l2";case "aa":return "grid_power_signed_l3";case "ab":return "grid_power_signed";case "ac":return "voltage_l1";case "ad":return "voltage_l2";case "ae":return "voltage_l3";case "af":return "current_l1";case "b0":return "current_l2";case "b1":return "current_l3";case "b2":return "grid_export_energy";case "b3":return "grid_import_energy";}}
        // AE103 (Solarbank 4) uses a different, nested MQTT layout. The a3/a5
        // byte IDs seen on older models are not safe SOC/temperature mappings
        // here; treating the entire nested field as a number produced huge,
        // rapidly changing readings. Keep only mappings validated for AE103.
        if(pn.equals("AE103")){switch(key){case "ab":return "photovoltaic_power";case "ac":return "battery_power_signed";case "ad":return "output_power";case "ae":return "ac_output_power_signed";case "b0":return "pv_yield";case "b1":return "charged_energy";case "b2":return "discharged_energy";case "b4":return "grid_export_energy";case "c4":return "grid_power_signed";case "c5":return "home_demand";case "c6":return type==0x0405?"pv_1_power":null;case "c7":return type==0x0405?"pv_2_power":null;case "c8":return type==0x0405?"pv_3_power":null;case "c9":return type==0x0405?"pv_4_power":null;}}
        return null;
    }
    private double fieldFactor(String pn,String key){if(pn.equals("A17X8")){if(key.equals("a8"))return .1;if(key.equals("a9"))return .01;if(key.equals("aa"))return .1;if(key.equals("ab"))return .001;}return 1;}

    private String findData(Object obj,int depth){if(depth>5)return "";if(obj instanceof JSONObject){JSONObject o=(JSONObject)obj;String direct=o.optString("data","");if(!direct.isEmpty()&&direct.matches("[A-Za-z0-9+/=]+"))return direct;for(String k:new String[]{"payload","message","body"}){Object v=o.opt(k);if(v instanceof String){try{String found=findData(new JSONObject((String)v),depth+1);if(!found.isEmpty())return found;}catch(Exception ignored){}}else{String found=findData(v,depth+1);if(!found.isEmpty())return found;}}}else if(obj instanceof JSONArray){JSONArray a=(JSONArray)obj;for(int i=0;i<a.length();i++){String s=findData(a.opt(i),depth+1);if(!s.isEmpty())return s;}}return "";}

    private javax.net.ssl.SSLSocketFactory socketFactory(JSONObject info)throws Exception{
        String certPem=first(info,"certificate_pem","client_certificate","cert");String keyPem=first(info,"private_key","private_key_pem","client_private_key");String caPem=first(info,"aws_root_ca1_pem","root_ca","ca_certificate");
        CertificateFactory cf=CertificateFactory.getInstance("X.509");Certificate[] chain=cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.US_ASCII))).toArray(new Certificate[0]);if(chain.length==0)throw new Exception("MQTT-Zertifikat fehlt.");
        PrivateKey privateKey=parsePrivateKey(keyPem,chain[0].getPublicKey().getAlgorithm());
        KeyStore clientStore=KeyStore.getInstance("PKCS12");clientStore.load(null,null);clientStore.setKeyEntry("anker",privateKey,new char[0],chain);javax.net.ssl.KeyManagerFactory kmf=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());kmf.init(clientStore,new char[0]);
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);for(Certificate c:cf.generateCertificates(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.US_ASCII))))trust.setCertificateEntry("anker-ca-"+trust.size(),c);javax.net.ssl.TrustManagerFactory tmf=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tmf.init(trust);
        javax.net.ssl.SSLContext ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),tmf.getTrustManagers(),null);return ssl.getSocketFactory();
    }
    private PrivateKey parsePrivateKey(String pem,String algorithm)throws Exception{
        Matcher m=Pattern.compile("-----BEGIN ([^-]+)-----([\\s\\S]+?)-----END \\1-----").matcher(pem);if(!m.find())throw new Exception("MQTT-Schlüssel fehlt.");String type=m.group(1);byte[] der=java.util.Base64.getDecoder().decode(m.group(2).replaceAll("\\s",""));
        if(type.contains("EC PRIVATE KEY")){byte[] algId=new byte[]{0x30,0x13,0x06,0x07,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x03,0x01,0x07};byte[] oct=derValue(0x04,der);byte[] version=new byte[]{0x02,0x01,0x00};der=derValue(0x30,concat(version,algId,oct));}
        else if(type.contains("RSA PRIVATE KEY")){byte[] algId=new byte[]{0x30,0x0d,0x06,0x09,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,0x01,0x01,0x01,0x05,0x00};byte[] version=new byte[]{0x02,0x01,0x00};der=derValue(0x30,concat(version,algId,derValue(0x04,der)));}
        return KeyFactory.getInstance(algorithm).generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }
    private byte[] derValue(int tag,byte[] value){byte[] len=asnLength(value.length);return concat(new byte[]{(byte)tag},len,value);}
    private byte[] asnLength(int n){if(n<128)return new byte[]{(byte)n};if(n<256)return new byte[]{(byte)0x81,(byte)n};return new byte[]{(byte)0x82,(byte)(n>>8),(byte)n};}
    private byte[] concat(byte[]...parts){int size=0;for(byte[] p:parts)size+=p.length;byte[] out=new byte[size];int at=0;for(byte[] p:parts){System.arraycopy(p,0,out,at,p.length);at+=p.length;}return out;}
    private static String first(JSONObject o,String...keys){for(String k:keys){String v=o.optString(k,"");if(!v.isEmpty()&&!v.equals("null"))return v;}return "";}
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null||m.isEmpty()?"Verbindungsfehler":m;}
    private void status(String value){if(listener!=null)listener.onStatus(value);}
    private void closeSocket(){try{if(socket!=null)socket.close();}catch(Exception ignored){}}
    private void cancelTasks(){if(triggerTask!=null)triggerTask.cancel(false);if(pingTask!=null)pingTask.cancel(false);triggerTask=null;pingTask=null;}
    void stop(){stopped=true;cancelTasks();scheduler.shutdownNow();connectionExecutor.shutdownNow();closeSocket();}
}
