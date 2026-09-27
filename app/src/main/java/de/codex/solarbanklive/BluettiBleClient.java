package de.codex.solarbanklive;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.UUID;

/** Local BLE telemetry reader for the legacy AC200MAX Modbus-over-GATT protocol. */
public final class BluettiBleClient {
    public interface Listener {
        void onStatus(String message);
        void onReading(Reading reading);
    }
    public static final class Reading {
        public int batteryPercent=-1, dcInput=-1, acInput=-1, acOutput=-1, dcOutput=-1;
        public double batteryVoltage=Double.NaN, acVoltage=Double.NaN, acCurrent=Double.NaN;
        public double dcVoltage=Double.NaN, dcCurrent=Double.NaN, generatedKwh=Double.NaN;
        public int packCount=-1, packMax=-1, packPercent=-1;
        public long updatedAt=System.currentTimeMillis();
    }
    private static final UUID WRITE=UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY=UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private final byte[] response=new byte[1024];
    private int responseLength=0, blockIndex=0;
    private boolean connecting=false, stopped=false;
    private Reading current=new Reading();
    private final Runnable timeout=()->{ if(connecting) fail("Bluetti antwortet nicht. Gerät einschalten und in Bluetooth-Reichweite bringen."); };
    private final Runnable nextPoll=()->pollNext();

    public BluettiBleClient(Context context, Listener listener){this.context=context.getApplicationContext();this.listener=listener;BluetoothManager manager=(BluetoothManager)this.context.getSystemService(Context.BLUETOOTH_SERVICE);if(manager!=null)adapter=manager.getAdapter();}
    public void scanAndConnect(){
        stopped=false;
        if(adapter==null||!adapter.isEnabled()){status("Bluetooth ist ausgeschaltet.");return;}
        scanner=adapter.getBluetoothLeScanner();if(scanner==null){status("Bluetooth-Suche ist nicht verfügbar.");return;}
        status("Suche nach Bluetti AC200 Max …");
        try{scanner.startScan(scanCallback);handler.postDelayed(()->{try{if(scanner!=null)scanner.stopScan(scanCallback);}catch(Exception ignored){}if(gatt==null)status("Keine AC200 Max gefunden. Gerät einschalten und näher ans Telefon stellen.");},12000);}catch(SecurityException e){status("Bluetooth-Berechtigung fehlt.");}
    }
    private final ScanCallback scanCallback=new ScanCallback(){@Override public void onScanResult(int type,ScanResult result){BluetoothDevice d=result.getDevice();String name=d.getName();if(name==null&&result.getScanRecord()!=null)name=result.getScanRecord().getDeviceName();if(name!=null&&(name.toUpperCase().startsWith("AC200M")||name.toUpperCase().contains("BLUETTI"))){try{scanner.stopScan(this);}catch(Exception ignored){}status("Bluetti gefunden · verbinde …");gatt=d.connectGatt(context,false,gattCallback, BluetoothDevice.TRANSPORT_LE);connecting=true;handler.postDelayed(timeout,16000);}}@Override public void onScanFailed(int error){status("Bluetooth-Suche fehlgeschlagen ("+error+").");}};
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){if(newState==BluetoothGatt.STATE_CONNECTED){connecting=false;handler.removeCallbacks(timeout);status("Bluetti verbunden · richte Messwerte ein …");g.discoverServices();}else if(newState==BluetoothGatt.STATE_DISCONNECTED){connecting=false;handler.removeCallbacks(nextPoll);if(!stopped)status("Bluetti-Verbindung getrennt.");closeGatt();}}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){BluetoothGattCharacteristic notify=null;for(BluetoothGattService s:g.getServices()){if(writeChar==null)writeChar=s.getCharacteristic(WRITE);if(notify==null)notify=s.getCharacteristic(NOTIFY);}if(writeChar==null||notify==null){fail("Bluetti-Messkanal nicht verfügbar.");return;}if(!g.setCharacteristicNotification(notify,true)){fail("Messkanal konnte nicht aktiviert werden.");return;}BluetoothGattDescriptor c=notify.getDescriptor(CCC);if(c==null){pollNext();return;}if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(c,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);else{c.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);g.writeDescriptor(c);}}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){if(status==BluetoothGatt.GATT_SUCCESS)pollNext();else fail("Bluetti-Messkanal konnte nicht gestartet werden.");}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){accept(c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){accept(value);}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(status!=BluetoothGatt.GATT_SUCCESS)fail("Bluetti-Abfrage konnte nicht gesendet werden.");}
    };
    private void pollNext(){if(gatt==null||writeChar==null||stopped)return;if(blockIndex>=3){current.updatedAt=System.currentTimeMillis();Reading done=current;handler.post(()->listener.onReading(done));current=new Reading();blockIndex=0;status("Bluetti live · aktualisiert");handler.postDelayed(nextPoll,5000);return;}int[] starts={10,70,91},counts={40,21,37};responseLength=0;sendRead(starts[blockIndex],counts[blockIndex]);}
    private void sendRead(int start,int count){byte[] frame={(byte)1,(byte)3,(byte)(start>>8),(byte)start,(byte)(count>>8),(byte)count,0,0};int crc=crc16(frame,6);frame[6]=(byte)crc;frame[7]=(byte)(crc>>8);try{if(Build.VERSION.SDK_INT>=33)gatt.writeCharacteristic(writeChar,frame,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);else{writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);writeChar.setValue(frame);gatt.writeCharacteristic(writeChar);}handler.removeCallbacks(timeout);handler.postDelayed(timeout,8000);}catch(Exception e){fail("Bluetooth-Abfrage fehlgeschlagen.");}}
    private synchronized void accept(byte[] bytes){if(bytes==null||bytes.length==0)return;handler.removeCallbacks(timeout);int copy=Math.min(bytes.length,response.length-responseLength);System.arraycopy(bytes,0,response,responseLength,copy);responseLength+=copy;int[] counts={40,21,37};int expected=2*counts[blockIndex]+5;if(responseLength<expected)return;byte[] frame=new byte[expected];System.arraycopy(response,0,frame,0,expected);responseLength=0;if(frame[1]==(byte)0x83||crc16(frame,expected-2)!=((frame[expected-1]&255)<<8|(frame[expected-2]&255))){blockIndex++;handler.post(nextPoll);return;}int start=new int[]{10,70,91}[blockIndex];parse(start,frame);blockIndex++;handler.post(nextPoll);}
    private void parse(int start,byte[] frame){int byteCount=frame[2]&255;for(int i=0;i<byteCount/2;i++){int reg=start+i;int v=((frame[3+i*2]&255)<<8)|(frame[4+i*2]&255);switch(reg){case 36:current.dcInput=v;break;case 37:current.acInput=v;break;case 38:current.acOutput=v;break;case 39:current.dcOutput=v;break;case 41:current.generatedKwh=v/10.0;break;case 43:if(v<=100)current.batteryPercent=v;break;case 71:current.acVoltage=v;break;case 72:current.acCurrent=v/10.0;break;case 86:current.dcVoltage=v;break;case 88:current.dcCurrent=v/100.0;break;case 92:current.batteryVoltage=v/100.0;break;case 96:current.packCount=v;break;case 91:current.packMax=v;break;case 99:if(v<=100)current.packPercent=v;break;}}}
    private int crc16(byte[] data,int len){int crc=0xffff;for(int i=0;i<len;i++){crc^=data[i]&255;for(int j=0;j<8;j++)crc=(crc&1)!=0?(crc>>1)^0xa001:crc>>1;}return crc&0xffff;}
    private void fail(String message){connecting=false;handler.removeCallbacks(timeout);status(message);closeGatt();}
    private void status(String value){handler.post(()->listener.onStatus(value));}
    public void disconnect(){stopped=true;handler.removeCallbacks(timeout);handler.removeCallbacks(nextPoll);try{if(scanner!=null)scanner.stopScan(scanCallback);}catch(Exception ignored){}closeGatt();status("Bluetti getrennt.");}
    private void closeGatt(){if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;}writeChar=null;}
}
