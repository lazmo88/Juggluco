/*
 * TEST ONLY - DO NOT MERGE.
 *
 * Fake sync of a glucose meter named TESTMETER, to exercise MeterAsk ("Ask
 * before saving readings") on an emulator without a meter.  The packets go
 * through GlucoseMeterGatt.onCharacteristicChanged, the entry the Bluetooth
 * stack calls, on a plain background thread like the real callbacks.
 */
package tk.glucodata;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.UUID;

final class MeterAskTest {
private MeterAskTest() {}

private static final String LOG_ID="MeterAskTest";
static final String NAME="TESTMETER";
private static final String PREFS="jm_meter_ask_test";
private static final String GLUCOSE="00002a18-0000-1000-8000-00805f9b34fb";
private static final String RECORDS="00002a52-0000-1000-8000-00805f9b34fb";
/* mg/dL: 5.4, 6.1 and 7.9 mmol/L */
private static final int[] VALUES={97,110,142};

private static BluetoothGattCharacteristic characteristic(String uuid,byte[] value) {
    BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(UUID.fromString(uuid),BluetoothGattCharacteristic.PROPERTY_NOTIFY,0);
    c.setValue(value);
    return c;
    }

/* Glucose Measurement: time offset and concentration present, kg/L. */
private static byte[] packet(int seq,long time,int mgdl) {
    Calendar cal=Calendar.getInstance();
    cal.setTimeInMillis(time);
    final int year=cal.get(Calendar.YEAR);
    final int sfloat=(0xB<<12)|(mgdl&0x0FFF); // mgdl * 10^-5 kg/L
    return new byte[] {
        0x03,
        (byte)seq,(byte)(seq>>8),
        (byte)year,(byte)(year>>8),(byte)(cal.get(Calendar.MONTH)+1),(byte)cal.get(Calendar.DAY_OF_MONTH),
        (byte)cal.get(Calendar.HOUR_OF_DAY),(byte)cal.get(Calendar.MINUTE),(byte)cal.get(Calendar.SECOND),
        0,0, // time offset 0 minutes
        (byte)sfloat,(byte)(sfloat>>8),
        0x11 // capillary whole blood, finger
        };
    }

private static void toast(Context ctx,String text) {
    Log.i(LOG_ID,text);
    Applic.argToaster(ctx,text,Toast.LENGTH_LONG);
    }

/* The gatt of the meter, registered like an active meter's but never connected. */
private static GlucoseMeterGatt gatt(int index) {
    GlucoseMeterGatt gatt=BluetoothGlucoseMeter.getExistingGatt(index);
    if(gatt!=null)
        return gatt;
    gatt=new GlucoseMeterGatt(index);
    final GlucoseMeterGatt[] old=BluetoothGlucoseMeter.meterGatts;
    final int len=old==null?0:old.length;
    final GlucoseMeterGatt[] all=new GlucoseMeterGatt[len+1];
    if(len>0)
        System.arraycopy(old,0,all,0,len);
    all[len]=gatt;
    BluetoothGlucoseMeter.meterGatts=all;
    return gatt;
    }

static void fakeSync(MainActivity act,boolean resend) {
    try {
        final SharedPreferences prefs=act.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        final ArrayList<String> labels=Natives.getLabels();
        if(Natives.getbloodvar()>=labels.size()-1) {
            final int blood=labels.indexOf("Blood");
            if(blood>=0&&blood<labels.size()-1)
                Natives.setbloodvar((byte)blood);
            else
                toast(act,"TEST: no label 'Blood', nothing will be saved");
            }
        final int index=Natives.GlucoseMeterGetIndex(NAME,null);
        if(index<0) {
            toast(act,"TEST: could not add "+NAME);
            return;
            }
        Natives.GlucoseMeterSetActive(index,true); // the flag only, no Bluetooth
        final GlucoseMeterGatt gatt=gatt(index);
        final byte[][] packets=new byte[VALUES.length][];
        if(resend) {
            final String last=prefs.getString("last",null);
            final String[] parts=last==null?new String[0]:last.split(" ");
            if(parts.length!=packets.length) {
                toast(act,"TEST: no fake sync yet");
                return;
                }
            for(int i=0;i<packets.length;i++)
                packets[i]=Base64.decode(parts[i],Base64.NO_WRAP);
            }
        else {
            /* 3, 2 and 1 minutes ago; later when that is not after the last
             * reading (an earlier fake sync), because the native side only
             * saves a record that is newer than the last one of the meter. */
            final long now=System.currentTimeMillis()/1000L*1000L;
            final long floor=Math.max(prefs.getLong("lasttime",0L),Natives.GlucoseMeterGetLastTime(index))+1000L;
            long first=now-180000L,step=60000L;
            if(floor>first) {
                first=floor;
                step=Math.max(1000L,(now-floor)/3L/1000L*1000L);
                }
            int seq=prefs.getInt("seq",1);
            StringBuilder sb=new StringBuilder();
            long time=first;
            for(int i=0;i<packets.length;i++) {
                time=first+i*step;
                packets[i]=packet(seq++,time,VALUES[i]);
                if(i>0)
                    sb.append(' ');
                sb.append(Base64.encodeToString(packets[i],Base64.NO_WRAP));
                }
            prefs.edit().putInt("seq",seq&0xFFFF).putLong("lasttime",time).putString("last",sb.toString()).commit();
            }
        final int from=MeterAsk.sequence(packets[0]);
        toast(act,"TEST: "+(resend?"resend":"sync")+" sequence "+from+"-"+(from+packets.length-1)+", ask "+(MeterAsk.enabled(NAME)?"on":"off"));
        new Thread(() -> {
            try {
                for(byte[] p:packets)
                    gatt.onCharacteristicChanged(null,characteristic(GLUCOSE,p));
                gatt.onCharacteristicChanged(null,characteristic(RECORDS,new byte[]{6,0,1,1}));
                }
            catch(Throwable th) {
                Log.stack(LOG_ID,"fake sync",th);
                }
            },"TESTsync").start();
        MeterList.refreshOpen();
        }
    catch(Throwable th) {
        Log.stack(LOG_ID,"fakeSync",th);
        toast(act,"TEST failed: "+th);
        }
    }
}
