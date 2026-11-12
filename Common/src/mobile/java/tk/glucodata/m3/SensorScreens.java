package tk.glucodata.m3;

import tk.glucodata.MainActivity;
import tk.glucodata.PhoneUi;

/* Sensors, scanning, Bluetooth, calibration, meters, pens. */
final class SensorScreens {
private SensorScreens() {}

private static long ptr(Object[] args,int i) {
    return args!=null&&args.length>i&&args[i] instanceof Long?(Long)args[i]:0L;
    }
private static String str(Object[] args,int i) {
    return args!=null&&args.length>i&&args[i] instanceof String?(String)args[i]:null;
    }

static void register() {
    /* Menu → Sensor (bluediag.start / nosensors) */
    PhoneUi.register("bluediag",(act,args) -> tk.glucodata.SensorsPage.start(act));
    /* One sensor (Sensors.show, GlucoseCurve.showsensorinfo): args text, sensorptr */
    PhoneUi.register("sensors",(act,args) -> tk.glucodata.SensorsPage.showSensor(act,ptr(args,1),str(args,0)));
    /* Calibrations of a sensor: args sensorptr */
    PhoneUi.register("calibratelist",(act,args) -> tk.glucodata.CalibrateListPage.show(act,ptr(args,0)));
    /* Settings → Calibration */
    PhoneUi.register("settings.calibration",(act,args) -> tk.glucodata.settings.CalibrationPage.show(act));
    /* NFC start of a sensor: args text, name */
    PhoneUi.register("scannfcv.newsensor",(act,args) -> tk.glucodata.SensorStart.newSensor(act,str(args,0),str(args,1)));
    PhoneUi.register("activateresult",(act,args) -> tk.glucodata.SensorStart.activateResult(act,args!=null&&args.length>0&&Boolean.TRUE.equals(args[0])));
    /* Glucose meters list (Exchange data → Glucose Meters); phone-only class,
     * registered so other pages can link to it: PhoneUi.show("meters",act). */
    PhoneUi.register("meters",(act,args) -> tk.glucodata.MeterList.show(act,null));
    }
}
