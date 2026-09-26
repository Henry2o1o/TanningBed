package de.codex.solarbanklive;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECField;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Minimal client for the undocumented Anker SOLIX cloud login and site-list API. */
final class AnkerCloudClient {
    static final class Result {
        final String nickname;
        final JSONArray sites;
        Result(String nickname, JSONArray sites) { this.nickname=nickname; this.sites=sites; }
    }

    private static final String BASE = "https://ankerpower-api-eu.anker.com/";
    private static final String SERVER_KEY = "04c5c00c4f8d1197cc7c3167c52bf7acb054d722f0ef08dcd7e0883236e0d72a3868d9750cb47fa4619248f3d83f0f662671dadc6e2d31c2f41db0161651c7c076";
    private static final String P = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff";
    private static final String A = "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc";
    private static final String B = "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b";
    private static final String GX = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296";
    private static final String GY = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5";
    private String token, gtoken;

    AnkerCloudClient() {}
    AnkerCloudClient(String token, String gtoken) { this.token=token; this.gtoken=gtoken; }
    String authToken() { return token; }
    String userToken() { return gtoken; }

    Result getSites(String nickname) throws Exception {
        JSONObject response=post("power_service/v1/site/get_site_list",new JSONObject(),token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null)throw new Exception(message(response,"Die Systemliste konnte nicht geladen werden."));
        JSONArray sites=data.optJSONArray("site_list");if(sites==null)sites=data.optJSONArray("site_info_list");if(sites==null)sites=new JSONArray();
        return new Result(nickname,sites);
    }

    Result loginAndGetSites(String email, String password) throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair=gen.generateKeyPair();
        ECPublicKey pub=(ECPublicKey)pair.getPublic();
        byte[] clientPub=new byte[65]; clientPub[0]=4;
        put32(pub.getW().getAffineX().toByteArray(),clientPub,1);
        put32(pub.getW().getAffineY().toByteArray(),clientPub,33);
        PublicKey server=serverKey(pub.getParams());
        KeyAgreement agreement=KeyAgreement.getInstance("ECDH"); agreement.init(pair.getPrivate()); agreement.doPhase(server,true);
        byte[] shared=agreement.generateSecret();
        byte[] iv=new byte[16]; System.arraycopy(shared,0,iv,0,16);
        Cipher aes=Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(shared,"AES"),new IvParameterSpec(iv));
        String encrypted=Base64.getEncoder().encodeToString(aes.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        JSONObject login=new JSONObject(); login.put("ab","DE");
        login.put("client_secret_info",new JSONObject().put("public_key",hex(clientPub)));
        login.put("enc",0); login.put("email",email); login.put("password",encrypted);
        login.put("time_zone",java.util.TimeZone.getDefault().getRawOffset());
        login.put("transaction",String.valueOf(System.currentTimeMillis()));
        JSONObject auth=post("passport/login",login,null,null);
        JSONObject data=auth.optJSONObject("data");
        if(data==null || data.optString("auth_token").isEmpty() || data.optString("user_id").isEmpty())
            throw new Exception(message(auth,"Anmeldung fehlgeschlagen. Zugangsdaten oder Anker-Cloud-Antwort prüfen."));
        token=data.getString("auth_token");
        gtoken=hex(MessageDigest.getInstance("MD5").digest(data.getString("user_id").getBytes(StandardCharsets.UTF_8)));
        JSONObject sitesResponse=post("power_service/v1/site/get_site_list",new JSONObject(),token,gtoken);
        JSONObject siteData=sitesResponse.optJSONObject("data");
        if(siteData==null) throw new Exception(message(sitesResponse,"Anmeldung erfolgreich, aber die Systemliste konnte nicht geladen werden."));
        JSONArray sites=siteData.optJSONArray("site_list");
        if(sites==null) sites=siteData.optJSONArray("site_info_list");
        if(sites==null) sites=new JSONArray();
        return new Result(data.optString("nick_name",email),sites);
    }

    JSONObject getSceneInfo(String siteId) throws Exception {
        JSONObject response=post("power_service/v1/site/get_scen_info",new JSONObject().put("site_id",siteId),token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null) throw new Exception(message(response,"Anker hat für dieses System keine Detaildaten geliefert."));
        return data;
    }

    JSONArray getUserDevices() throws Exception {
        JSONObject response=post("power_service/v1/site/list_user_devices",new JSONObject(),token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null) throw new Exception(message(response,"Die Geräteliste konnte nicht geladen werden."));
        JSONArray devices=data.optJSONArray("device_list");
        return devices==null?new JSONArray():devices;
    }

    JSONArray getBoundDevices() throws Exception {
        JSONObject response=post("power_service/v1/app/get_relate_and_bind_devices",new JSONObject(),token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null) throw new Exception(message(response,"Die verbundenen Geräte konnten nicht geladen werden."));
        JSONArray devices=data.optJSONArray("data");
        return devices==null?new JSONArray():devices;
    }

    JSONObject getMqttInfo() throws Exception {
        JSONObject response=post("app/devicemanage/get_user_mqtt_info",new JSONObject(),token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null)throw new Exception(message(response,"Anker hat keine MQTT-Verbindungsdaten geliefert."));
        return data;
    }

    JSONObject getEnergyAnalysis(String siteId, String deviceSn, String granularity, String startTime, String endTime) throws Exception {
        JSONObject body=new JSONObject().put("site_id",siteId).put("device_sn",deviceSn)
                .put("type",granularity).put("start_time",startTime).put("end_time",endTime);
        JSONObject response=post("power_service/v1/site/energy_analysis",body,token,gtoken);
        JSONObject data=response.optJSONObject("data");
        if(data==null)throw new Exception(message(response,"Der Anker-Verlauf ist gerade nicht verfügbar."));
        return data;
    }

    private JSONObject post(String path, JSONObject body, String auth, String gt) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setDoOutput(true);
        c.setRequestProperty("content-type","application/json"); c.setRequestProperty("model-type","DESKTOP");
        c.setRequestProperty("app-name","anker_power"); c.setRequestProperty("os-type","android");
        c.setRequestProperty("country","DE"); c.setRequestProperty("timezone",timezone());
        if(auth!=null){c.setRequestProperty("x-auth-token",auth);c.setRequestProperty("gtoken",gt);}
        try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode(); InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
        while((n=in.read(b))!=-1) out.write(b,0,n); in.close(); c.disconnect();
        JSONObject response=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        if(code>=400) throw new Exception("Anker-Cloud antwortet mit HTTP "+code+": "+message(response,"Bitte später erneut versuchen."));
        if(response.has(" code") || (response.has("code") && response.optInt("code",0)!=0))
            throw new Exception(message(response,"Anker-Cloud hat die Anfrage abgelehnt."));
        return response;
    }
    private static String message(JSONObject o,String fallback){JSONObject d=o.optJSONObject("data");String m=o.optString("msg",o.optString("message",""));if(m.isEmpty()&&d!=null)m=d.optString("msg","");return m.isEmpty()?fallback:m;}
    private static String timezone(){int m=java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())/60000;return String.format(Locale.US,"GMT%c%02d:%02d",m>=0?'+':'-',Math.abs(m)/60,Math.abs(m)%60);}
    private static PublicKey serverKey(ECParameterSpec template)throws Exception{
        EllipticCurve curve=template.getCurve(); ECPoint w=new ECPoint(new java.math.BigInteger(SERVER_KEY.substring(2,66),16),new java.math.BigInteger(SERVER_KEY.substring(66),16));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w,template));
    }
    private static void put32(byte[] value,byte[] target,int offset){int from=Math.max(0,value.length-32),len=Math.min(value.length,32);System.arraycopy(value,from,target,offset+32-len,len);}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
}
