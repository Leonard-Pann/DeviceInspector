package com.example.deviceinspector2_tartiflette;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class MainActivity extends Activity implements SensorEventListener {

    private TextView eventInfoTextView;
    private TextView touchCaptureView;
    private TextView smallStatusTextView;
    private ScrollView infoScrollView;
    private Switch liveUpdateSwitch;
    private Button refreshSystemButton;
    private long updateCounter = 0;

    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;
    private Sensor magnetometerSensor;

    private final float[] accelerometerValues = new float[3];
    private final float[] gyroscopeValues = new float[3];
    private final float[] magnetometerValues = new float[3];

    private boolean hasAccelerometerValue = false;
    private boolean hasGyroscopeValue = false;
    private boolean hasMagnetometerValue = false;

    private int accelerometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private int gyroscopeAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private int magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;

    private long accelerometerTimestampNs = 0L;
    private long gyroscopeTimestampNs = 0L;
    private long magnetometerTimestampNs = 0L;

    private long lastSensorUiRefreshMs = 0L;
    private static final long SENSOR_UI_REFRESH_INTERVAL_MS = 100L; // 10 refresh UI / seconde max

    private String lastSensorName = "aucun capteur";

    private MotionEvent lastMotionEventCopy = null;
    private String lastCaptureMethod = "aucun événement";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        eventInfoTextView = findViewById(R.id.eventInfoTextView);
        touchCaptureView = findViewById(R.id.touchCaptureView);
        smallStatusTextView = findViewById(R.id.smallStatusTextView);
        infoScrollView = findViewById(R.id.infoScrollView);
        liveUpdateSwitch = findViewById(R.id.liveUpdateSwitch);
        refreshSystemButton = findViewById(R.id.refreshSystemButton);

        setupSensors();
        setupTouchCaptureZone();
        setupRefreshButton();

        renderScreen(null, "initialisation", true);
    }

    // region Sensor

    private void setupSensors() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            if (sensorManager == null) {
                return;
            }

            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        } catch (Exception e) {
            sensorManager = null;
            accelerometerSensor = null;
            gyroscopeSensor = null;
            magnetometerSensor = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensorListeners();
    }

    @Override
    protected void onPause() {
        unregisterSensorListeners();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterSensorListeners();

        super.onDestroy();

        if (lastMotionEventCopy != null) {
            lastMotionEventCopy.recycle();
            lastMotionEventCopy = null;
        }
    }

        private void registerSensorListeners() {
            if (sensorManager == null) {
                return;
            }

            /*
             * SENSOR_DELAY_UI est suffisant pour afficher du texte en temps réel.
             * Si tu veux plus réactif, tu peux essayer SENSOR_DELAY_GAME.
             */
            int delay = SensorManager.SENSOR_DELAY_UI;

            if (accelerometerSensor != null) {
                sensorManager.registerListener(this, accelerometerSensor, delay);
            }

            if (gyroscopeSensor != null) {
                sensorManager.registerListener(this, gyroscopeSensor, delay);
            }

            if (magnetometerSensor != null) {
                sensorManager.registerListener(this, magnetometerSensor, delay);
            }
        }

    private void unregisterSensorListeners() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) {
            return;
        }

        int sensorType = event.sensor.getType();
        lastSensorName = sensorTypeToString(sensorType);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                copyFirst3(event.values, accelerometerValues);
                accelerometerAccuracy = event.accuracy;
                accelerometerTimestampNs = event.timestamp;
                hasAccelerometerValue = true;
                break;

            case Sensor.TYPE_GYROSCOPE:
                copyFirst3(event.values, gyroscopeValues);
                gyroscopeAccuracy = event.accuracy;
                gyroscopeTimestampNs = event.timestamp;
                hasGyroscopeValue = true;
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                copyFirst3(event.values, magnetometerValues);
                magnetometerAccuracy = event.accuracy;
                magnetometerTimestampNs = event.timestamp;
                hasMagnetometerValue = true;
                break;

            default:
                return;
        }

        if (liveUpdateSwitch != null && !liveUpdateSwitch.isChecked()) {
            return;
        }

        long nowMs = System.currentTimeMillis();

        if (nowMs - lastSensorUiRefreshMs < SENSOR_UI_REFRESH_INTERVAL_MS) {
            return;
        }

        lastSensorUiRefreshMs = nowMs;

        renderScreen(lastMotionEventCopy, "SensorEvent : " + lastSensorName, true);
        updateSmallStatus("Capteurs live : " + lastSensorName + " mis à jour");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == null) {
            return;
        }

        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                accelerometerAccuracy = accuracy;
                break;

            case Sensor.TYPE_GYROSCOPE:
                gyroscopeAccuracy = accuracy;
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                magnetometerAccuracy = accuracy;
                break;
        }
    }

    private void appendRealtimeSensorsInfo(StringBuilder sb) {
        appendSubSection(sb, "Capteurs temps réel : accéléromètre / gyroscope / magnétomètre");

        appendEmptyLine(sb);

        appendSensorBlock(
                sb,
                "Accéléromètre",
                accelerometerSensor,
                hasAccelerometerValue,
                accelerometerValues,
                "m/s²",
                accelerometerAccuracy,
                accelerometerTimestampNs
        );

        appendSensorBlock(
                sb,
                "Gyroscope",
                gyroscopeSensor,
                hasGyroscopeValue,
                gyroscopeValues,
                "rad/s",
                gyroscopeAccuracy,
                gyroscopeTimestampNs
        );

        appendSensorBlock(
                sb,
                "Magnétomètre",
                magnetometerSensor,
                hasMagnetometerValue,
                magnetometerValues,
                "µT",
                magnetometerAccuracy,
                magnetometerTimestampNs
        );
    }

    private void appendSensorBlock(
            StringBuilder sb,
            String label,
            Sensor sensor,
            boolean hasValue,
            float[] values,
            String unit,
            int accuracy,
            long timestampNs
    ) {
        appendLine(sb, label + " disponible", sensor != null ? "oui" : "non");

        if (sensor != null) {
            appendLine(sb, label + " nom", safeString(sensor.getName()));
            appendLine(sb, label + " vendor", safeString(sensor.getVendor()));
            appendLine(sb, label + " version", String.valueOf(sensor.getVersion()));
            appendLine(sb, label + " portée max", formatFloat(sensor.getMaximumRange()) + " " + unit);
            appendLine(sb, label + " résolution", formatFloat(sensor.getResolution()) + " " + unit);
            appendLine(sb, label + " consommation", formatFloat(sensor.getPower()) + " mA");
            appendLine(sb, label + " minDelay", sensor.getMinDelay() + " µs");
        }

        if (hasValue && values != null && values.length >= 3) {
            appendLine(sb, label + " X", formatFloat(values[0]) + " " + unit);
            appendLine(sb, label + " Y", formatFloat(values[1]) + " " + unit);
            appendLine(sb, label + " Z", formatFloat(values[2]) + " " + unit);
            appendLine(sb, label + " précision", accuracyToString(accuracy) + " / " + formatIntDecHex(accuracy));
            appendLine(sb, label + " timestamp", timestampNs + " ns");
        } else {
            appendLine(sb, label + " valeurs", sensor == null ? "capteur absent" : "en attente du premier événement");
        }

        appendEmptyLine(sb);
    }

    private static void copyFirst3(float[] source, float[] destination) {
        if (source == null || destination == null || destination.length < 3) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            destination[i] = i < source.length ? source[i] : 0.0f;
        }
    }

    private static String sensorTypeToString(int sensorType) {
        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                return "TYPE_ACCELEROMETER";
            case Sensor.TYPE_GYROSCOPE:
                return "TYPE_GYROSCOPE";
            case Sensor.TYPE_MAGNETIC_FIELD:
                return "TYPE_MAGNETIC_FIELD";
            default:
                return "Type capteur inconnu : " + sensorType;
        }
    }

    private static String accuracyToString(int accuracy) {
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                return "SENSOR_STATUS_UNRELIABLE";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                return "SENSOR_STATUS_ACCURACY_LOW";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                return "SENSOR_STATUS_ACCURACY_MEDIUM";
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                return "SENSOR_STATUS_ACCURACY_HIGH";
            default:
                return "Précision inconnue";
        }
    }

    //endregion

    private void setupTouchCaptureZone() {
        if (touchCaptureView == null) {
            return;
        }

        touchCaptureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) {
                    return true;
                }

                if (liveUpdateSwitch != null && !liveUpdateSwitch.isChecked()) {
                    updateSmallStatus("Live désactivé — MotionEvent ignoré.");
                    return true;
                }

                saveLastMotionEventCopy(event);
                renderScreen(event, "OnTouchListener sur zone tactile", false);

                /*
                 * true = la zone tactile consomme l'événement.
                 * Cela permet de recevoir ACTION_MOVE et ACTION_UP après ACTION_DOWN.
                 */
                return true;
            }
        });
    }

    private void setupRefreshButton() {
        if (refreshSystemButton == null) {
            return;
        }

        refreshSystemButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderScreen(lastMotionEventCopy, "refresh manuel système", true);
            }
        });
    }

    private void saveLastMotionEventCopy(MotionEvent event) {
        if (event == null) {
            return;
        }

        if (lastMotionEventCopy != null) {
            lastMotionEventCopy.recycle();
            lastMotionEventCopy = null;
        }

        lastMotionEventCopy = MotionEvent.obtain(event);
    }

    private void renderScreen(MotionEvent event, String captureMethod, boolean preserveScroll) {
        if (eventInfoTextView == null) {
            return;
        }

        updateCounter++;
        lastCaptureMethod = safeString(captureMethod);

        StringBuilder sb = new StringBuilder(24000);

        appendSection(sb, "0. INFOS INDÉPENDANTES DES MOTIONEVENT");
        appendSystemIndependentInfo(sb);

        appendSection(sb, "1. INFORMATIONS GÉNÉRALES");
        appendLine(sb, "Mise à jour n°", String.valueOf(updateCounter));
        appendLine(sb, "Méthode de capture", lastCaptureMethod);
        appendLine(sb, "SDK Android", String.valueOf(Build.VERSION.SDK_INT));
        appendLine(sb, "Appareil", safeString(Build.MANUFACTURER + " " + Build.MODEL));

        if (event == null) {
            appendLine(sb, "Dernier MotionEvent", "aucun pour le moment");
            setInfoText(sb.toString(), preserveScroll);
            updateSmallStatus("Aucun MotionEvent capturé pour le moment.");
            return;
        }

        int deviceId = safeGetDeviceId(event);
        InputDevice inputDevice = event.getDevice();

        appendLine(sb, "MotionEvent présent", "oui");
        appendLine(sb, "deviceId depuis MotionEvent", formatIntDecHex(deviceId));
        appendLine(sb, "InputDevice trouvé avec l'id", InputDevice.getDevice(deviceId) != null ? "oui" : "non");
        appendLine(sb, "InputDevice trouvé avec le MotionEvent", inputDevice != null ? "oui" : "non");

        appendSection(sb, "2. INFORMATIONS SUR L'INPUTDEVICE");
        appendAllInputDevices(sb);

        if (inputDevice == null) {
            appendLine(sb, "InputDevice lié au MotionEvent", "null");
            appendLine(sb, "Remarque", "Un deviceId peut être 0, invalide, virtuel ou ne plus être disponible.");
        } else {
            appendInputDeviceInfo(sb, inputDevice);
        }

        appendSection(sb, "3. INFORMATIONS SUR LE MOTIONEVENT");
        appendMotionEventInfo(sb, event);

        appendSection(sb, "4. INFORMATIONS HISTORIQUES DU MOTIONEVENT");
        appendHistoricalMotionEventInfo(sb, event);

        setInfoText(sb.toString(), preserveScroll);

        updateSmallStatus(
                "Dernier event : " +
                        actionToString(event.getActionMasked()) +
                        " | pointers=" + event.getPointerCount() +
                        " | history=" + event.getHistorySize()
        );
    }

    private void setInfoText(String text, boolean preserveScroll) {
        int oldScrollY = 0;

        if (preserveScroll && infoScrollView != null) {
            oldScrollY = infoScrollView.getScrollY();
        }

        eventInfoTextView.setText(text);

        if (preserveScroll && infoScrollView != null) {
            final int finalOldScrollY = oldScrollY;
            infoScrollView.post(new Runnable() {
                @Override
                public void run() {
                    infoScrollView.scrollTo(0, finalOldScrollY);
                }
            });
        }
    }

    private void updateSmallStatus(String text) {
        if (smallStatusTextView != null) {
            smallStatusTextView.setText(safeString(text));
        }
    }

    private void appendSystemIndependentInfo(StringBuilder sb) {
        appendRealtimeSensorsInfo(sb);

        appendAdbInfo(sb);
        appendBatteryAndUsbConnectionInfo(sb);
        appendUsbHostDevicesInfo(sb);
        appendUsbAccessoriesInfo(sb);
    }

    private void appendAdbInfo(StringBuilder sb) {
        appendSubSection(sb, "ADB / débogage USB");

        try {
            int adbEnabled = Settings.Global.getInt(
                    getContentResolver(),
                    Settings.Global.ADB_ENABLED,
                    -1
            );

            appendLine(sb, "Settings.Global.ADB_ENABLED brut", Integer.toString(adbEnabled));
        } catch (Exception e) {
            appendLine(sb, "Settings.Global.ADB_ENABLED", errorText(e));
        }

        try {
            int adbEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
            appendLine(sb, "Settings.Secure.ADB_ENABLED brut", Integer.toString(adbEnabled));

        }
        catch (Exception e) {
            appendLine(sb, "Settings.Secure.ADB_ENABLED", errorText(e));
        }

        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method getMethod = systemPropertiesClass.getMethod("get", String.class);
            String adbdStatus = (String) getMethod.invoke(null, "init.svc.adbd");
            boolean isAdbDaemonRunning = "running".equals(adbdStatus);
            appendLine(sb, "SystemProperties.nit.svc.adbd is running", Boolean.toString(isAdbDaemonRunning));
        }
        catch (Exception e) {
            appendLine(sb, "SystemProperties.init.svc.adbd unavailable", errorText(e));
        }

        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method getMethod = systemPropertiesClass.getMethod("get", String.class);
            String usbConfig = (String) getMethod.invoke(null, "sys.usb.config");
            boolean isAdbInUsbConfig = usbConfig != null && usbConfig.contains("adb");
            appendLine(sb, "SystemProperties.sys.usb.config is running", Boolean.toString(isAdbInUsbConfig));
        }
        catch (Exception e) {
            appendLine(sb, "SystemProperties.sys.usb.config unavailable", errorText(e));
        }

        appendEmptyLine(sb);

        try {
            Intent usbStateIntent = registerReceiver(
                    null,
                    new IntentFilter("android.hardware.usb.action.USB_STATE")
            );

            if (usbStateIntent == null) {
                appendLine(sb, "USB_STATE broadcast", "indisponible");
                appendLine(sb, "ADB connecté à un PC", "non déterminable de façon fiable");
            } else {
                boolean connected = usbStateIntent.getBooleanExtra("connected", false);
                boolean configured = usbStateIntent.getBooleanExtra("configured", false);
                boolean adb = usbStateIntent.getBooleanExtra("adb", false);
                boolean mtp = usbStateIntent.getBooleanExtra("mtp", false);
                boolean ptp = usbStateIntent.getBooleanExtra("ptp", false);
                boolean rndis = usbStateIntent.getBooleanExtra("rndis", false);
                boolean midi = usbStateIntent.getBooleanExtra("midi", false);

                appendLine(sb, "USB_STATE.connected", String.valueOf(connected));
                appendLine(sb, "USB_STATE.configured", String.valueOf(configured));
                appendLine(sb, "USB_STATE.adb", String.valueOf(adb));
                appendLine(sb, "USB_STATE.mtp", String.valueOf(mtp));
                appendLine(sb, "USB_STATE.ptp", String.valueOf(ptp));
                appendLine(sb, "USB_STATE.rndis", String.valueOf(rndis));
                appendLine(sb, "USB_STATE.midi", String.valueOf(midi));

                if (connected && configured) {
                    appendLine(sb, "Téléphone connecté à un hôte USB/PC", "probable");
                } else if (connected) {
                    appendLine(sb, "Téléphone connecté à un hôte USB/PC", "possible, mais non configuré");
                } else {
                    appendLine(sb, "Téléphone connecté à un hôte USB/PC", "non détecté via USB_STATE");
                }

                if (adb) {
                    appendLine(sb, "ADB actif selon USB_STATE", "oui, indicateur expérimental");
                } else {
                    appendLine(sb, "ADB actif selon USB_STATE", "non ou non exposé");
                }
            }
        } catch (Exception e) {
            appendLine(sb, "Lecture USB_STATE", errorText(e));
            appendLine(sb, "ADB connecté à un PC", "non déterminable de façon fiable");
        }

        appendEmptyLine(sb);

        Context context = null;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getMethod("currentApplication");
            context = (Application) currentApplicationMethod.invoke(null);
        } catch (Exception e) {
            appendLine(sb, "ECHEC_CONTEXT", "Impossible de récupérer le contexte de l'application.");
        }

        if (context != null) {
            try {
                DisplayManager displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
                if (displayManager != null) {
                    Display[] displays = displayManager.getDisplays();

                    appendLine(sb, "Nombre d'écrans détecté: ",  Integer.toString(displays.length));

                    if(displays.length > 1) {
                        appendLine(sb, "Display info", "");

                        for (Display display : displays) {
                            Boolean isMainDisplay = display.getDisplayId() == Display.DEFAULT_DISPLAY;
                            String name = display.getName();
                            int flags = display.getFlags();
                            appendLine(sb, "id", Integer.toString(display.getDisplayId()));
                            appendLine(sb, "isMainDisplay", Boolean.toString(isMainDisplay));
                            appendLine(sb, "Nom d'écran", name);
                            appendLine(sb, "Flag", formatIntDecHex(flags));
                            appendLine(sb, "Est sécurisé", Boolean.toString((flags & Display.FLAG_SECURE) == 0));
                            appendLine(sb, "Est de type présentation", Boolean.toString((flags & Display.FLAG_PRESENTATION) != 0));
                        }
                    }
                }
            } catch (Exception e) {
                appendLine(sb, "Impossible d'obtenir des informations sur les écran", e.toString());
            }
        }

        appendEmptyLine(sb);
    }

    private void appendBatteryAndUsbConnectionInfo(StringBuilder sb) {
        appendSubSection(sb, "Batterie / charge / connexion USB");

        try {
            Intent batteryIntent = registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (batteryIntent == null) {
                appendLine(sb, "ACTION_BATTERY_CHANGED", "indisponible");
                return;
            }

            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

            appendLine(sb, "Battery status", Integer.toString(status) + " / " + batteryStatusToString(status));
            appendLine(sb, "EXTRA_PLUGGED", Integer.toString(plugged)+ " / " + pluggedToString(plugged));
            appendLine(sb, "Téléphone en charge", String.valueOf(plugged != 0));

            if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) == BatteryManager.BATTERY_PLUGGED_USB) {
                appendLine(sb, "Connexion USB détectée par batterie", "oui : BATTERY_PLUGGED_USB");
                appendLine(sb, "Est-ce forcément un PC ?", "non : cela peut aussi être un chargeur USB ou une power bank");
            } else {
                appendLine(sb, "Connexion USB détectée par batterie", "non");
            }

        } catch (Exception e) {
            appendLine(sb, "Lecture batterie/charge", errorText(e));
        }

        appendEmptyLine(sb);
    }

    private void appendUsbHostDevicesInfo(StringBuilder sb) {
        appendSubSection(sb, "Périphériques USB host connectés au téléphone");

        try {
            PackageManager pm = getPackageManager();
            boolean hasUsbHost = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);

            appendLine(sb, "FEATURE_USB_HOST", String.valueOf(hasUsbHost));

            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                appendLine(sb, "UsbManager", "null");
                return;
            }

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

            if (deviceList == null || deviceList.isEmpty()) {
                appendLine(sb, "UsbManager.getDeviceList()", "aucun périphérique USB host détecté");
                return;
            }

            appendLine(sb, "Nombre de périphériques USB host", String.valueOf(deviceList.size()));

            int index = 0;
            for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
                index++;

                String key = entry.getKey();
                UsbDevice device = entry.getValue();

                appendSubSection(sb, "USB device host #" + index);

                if (device == null) {
                    appendLine(sb, "Device", "null pour key=" + safeString(key));
                    continue;
                }

                appendLine(sb, "Map key", safeString(key));
                appendLine(sb, "getDeviceName()", safeString(device.getDeviceName()));
                appendLine(sb, "getVendorId()", formatIntDecHex(device.getVendorId()));
                appendLine(sb, "getProductId()", formatIntDecHex(device.getProductId()));
                appendLine(sb, "getDeviceClass()", formatIntDecHex(device.getDeviceClass()));
                appendLine(sb, "getDeviceSubclass()", formatIntDecHex(device.getDeviceSubclass()));
                appendLine(sb, "getDeviceProtocol()", formatIntDecHex(device.getDeviceProtocol()));
                appendLine(sb, "getInterfaceCount()", String.valueOf(device.getInterfaceCount()));

                try {
                    appendLine(sb, "hasPermission(device)", String.valueOf(usbManager.hasPermission(device)));
                } catch (Exception e) {
                    appendLine(sb, "hasPermission(device)", errorText(e));
                }

                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    try {
                        UsbInterface usbInterface = device.getInterface(i);

                        appendLine(sb, "Interface " + i + " id", String.valueOf(usbInterface.getId()));
                        appendLine(sb, "Interface " + i + " class", formatIntDecHex(usbInterface.getInterfaceClass()));
                        appendLine(sb, "Interface " + i + " subclass", formatIntDecHex(usbInterface.getInterfaceSubclass()));
                        appendLine(sb, "Interface " + i + " protocol", formatIntDecHex(usbInterface.getInterfaceProtocol()));
                        appendLine(sb, "Interface " + i + " endpoints", String.valueOf(usbInterface.getEndpointCount()));
                    } catch (Exception e) {
                        appendLine(sb, "Interface " + i, errorText(e));
                    }
                }
            }

        } catch (Exception e) {
            appendLine(sb, "Lecture UsbManager.getDeviceList()", errorText(e));
        }

        appendEmptyLine(sb);
    }

    private void appendUsbAccessoriesInfo(StringBuilder sb) {
        appendSubSection(sb, "Accessoires USB Android Accessory");

        try {
            PackageManager pm = getPackageManager();
            boolean hasUsbAccessory = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);

            appendLine(sb, "FEATURE_USB_ACCESSORY", String.valueOf(hasUsbAccessory));

            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                appendLine(sb, "UsbManager", "null");
                return;
            }

            UsbAccessory[] accessories = usbManager.getAccessoryList();

            if (accessories == null || accessories.length == 0) {
                appendLine(sb, "getAccessoryList()", "aucun accessoire USB détecté");
                return;
            }

            appendLine(sb, "Nombre d'accessoires", String.valueOf(accessories.length));

            for (int i = 0; i < accessories.length; i++) {
                UsbAccessory accessory = accessories[i];

                appendSubSection(sb, "USB accessory #" + i);

                if (accessory == null) {
                    appendLine(sb, "Accessory", "null");
                    continue;
                }

                appendLine(sb, "getManufacturer()", safeString(accessory.getManufacturer()));
                appendLine(sb, "getModel()", safeString(accessory.getModel()));
                appendLine(sb, "getDescription()", safeString(accessory.getDescription()));
                appendLine(sb, "getVersion()", safeString(accessory.getVersion()));
                appendLine(sb, "getUri()", safeString(accessory.getUri()));
                appendLine(sb, "getSerial()", safeString(accessory.getSerial()));

                try {
                    appendLine(sb, "hasPermission(accessory)", String.valueOf(usbManager.hasPermission(accessory)));
                } catch (Exception e) {
                    appendLine(sb, "hasPermission(accessory)", errorText(e));
                }
            }

        } catch (Exception e) {
            appendLine(sb, "Lecture accessoires USB", errorText(e));
        }

        appendEmptyLine(sb);
    }

    private void appendAllInputDevices(StringBuilder sb) {
        try {
            int[] ids = InputDevice.getDeviceIds();
            appendLine(sb, "InputDevice.getDeviceIds()", Arrays.toString(ids));

            for (int id : ids) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) {
                    appendLine(sb, "InputDevice id " + id, "null");
                } else {
                    appendLine(
                            sb,
                            "InputDevice id " + id,
                            safeString(device.getName()) +
                                    " | sources=" + formatIntDecHex(device.getSources()) +
                                    " | external=" + device.isExternal() +
                                    " | virtual=" + device.isVirtual()
                    );
                }
            }
        } catch (Exception e) {
            appendLine(sb, "InputDevice.getDeviceIds()", errorText(e));
        }

        appendEmptyLine(sb);
    }

    private void appendInputDeviceInfo(StringBuilder sb, InputDevice device) {
        try {
            appendLine(sb, "getId()", formatIntDecHex(device.getId()));
        } catch (Exception e) {
            appendLine(sb, "getId()", errorText(e));
        }

        try {
            appendLine(sb, "getDescriptor()", safeString(device.getDescriptor()));
        } catch (Exception e) {
            appendLine(sb, "getDescriptor()", errorText(e));
        }

        try {
            appendLine(sb, "getName()", safeString(device.getName()));
        } catch (Exception e) {
            appendLine(sb, "getName()", errorText(e));
        }

        try {
            int sources = device.getSources();
            appendLine(sb, "getSources()", formatIntDecHex(sources));
            appendLine(sb, "Sources décodées", describeSources(sources));
        } catch (Exception e) {
            appendLine(sb, "getSources()", errorText(e));
        }

        appendEmptyLine(sb);

        try {
            int keyboardType = device.getKeyboardType();
            appendLine(sb, "getKeyboardType()", formatIntDecHex(keyboardType) + " / " + keyboardTypeToString(keyboardType));
        } catch (Exception e) {
            appendLine(sb, "getKeyboardType()", errorText(e));
        }

        try {
            KeyCharacterMap keyCharacterMap = device.getKeyCharacterMap();
            appendLine(sb, "getKeyCharacterMap()", String.valueOf(keyCharacterMap));
        } catch (Exception e) {
            appendLine(sb, "getKeyCharacterMap()", errorText(e));
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                appendLine(sb, "getVendorId()", formatIntDecHex(device.getVendorId()));
            } else {
                appendLine(sb, "getVendorId()", "indisponible avant API 19");
            }
        } catch (Exception e) {
            appendLine(sb, "getVendorId()", errorText(e));
        }

        try {
            appendLine(sb, "isExternal()", String.valueOf(device.isExternal()));
        } catch (Exception e) {
            appendLine(sb, "isExternal()", errorText(e));
        }

        try {
            appendLine(sb, "isVirtual()", String.valueOf(device.isVirtual()));
        } catch (Exception e) {
            appendLine(sb, "isVirtual()", errorText(e));
        }

        try {
            appendLine(sb, "describeContents()", formatIntDecHex(device.describeContents()));
        } catch (Exception e) {
            appendLine(sb, "describeContents()", errorText(e));
        }

        appendEmptyLine(sb);
    }

    private void appendMotionEventInfo(StringBuilder sb, MotionEvent event) {
        try {
            appendLine(sb, "getDeviceId()", formatIntDecHex(event.getDeviceId()));
        } catch (Exception e) {
            appendLine(sb, "getDeviceId()", errorText(e));
        }

        try {
            int source = event.getSource();

            appendLine(sb, "getSource()", formatIntDecHex(source));
            appendLine(sb, "Source décodée", describeSources(source));
            appendLine(sb, "isFromSource(event.getSource())", String.valueOf(event.isFromSource(source)));
            appendLine(sb, "isFromSource(SOURCE_TOUCHSCREEN)", String.valueOf(event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)));
        } catch (Exception e) {
            appendLine(sb, "Source MotionEvent", errorText(e));
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendLine(sb, "getFlags()", formatIntDecHex(event.getFlags()));
            } else {
                appendLine(sb, "getFlags()", "indisponible avant API 29");
            }
        } catch (Exception e) {
            appendLine(sb, "getFlags()", errorText(e));
        }

        try {
            int action = event.getAction();
            int actionMasked = event.getActionMasked();

            appendLine(sb, "getAction()", formatIntDecHex(action));
            appendLine(sb, "getActionMasked()", formatIntDecHex(actionMasked) + " / " + actionToString(actionMasked));
            appendLine(sb, "getActionIndex()", formatIntDecHex(event.getActionIndex()));
            appendLine(sb, "getPointerCount()", String.valueOf(event.getPointerCount()));
            appendLine(sb, "getHistorySize()", String.valueOf(event.getHistorySize()));
            appendLine(sb, "getEventTime()", event.getEventTime() + " ms uptime");
            appendLine(sb, "getDownTime()", event.getDownTime() + " ms uptime");
        } catch (Exception e) {
            appendLine(sb, "Infos action/pointeurs", errorText(e));
        }

        appendEmptyLine(sb);

        int pointerCount = safeGetPointerCount(event);

        for (int pointerIndex = 0; pointerIndex < pointerCount; pointerIndex++) {
            appendSubSection(sb, "Pointeur index " + pointerIndex);

            try {
                appendLine(sb, "getPointerId(pointerIndex)", formatIntDecHex(event.getPointerId(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getPointerId(pointerIndex)", errorText(e));
            }

            try {
                MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
                event.getPointerProperties(pointerIndex, properties);

                appendLine(sb, "getPointerProperties(...).id", formatIntDecHex(properties.id));
                appendLine(
                        sb,
                        "getPointerProperties(...).toolType",
                        formatIntDecHex(properties.toolType) + " / " + toolTypeToString(properties.toolType)
                );
            } catch (Exception e) {
                appendLine(sb, "getPointerProperties(pointerIndex, properties)", errorText(e));
            }

            try {
                int toolType = event.getToolType(pointerIndex);
                appendLine(sb, "getToolType(pointerIndex)", formatIntDecHex(toolType) + " / " + toolTypeToString(toolType));
            } catch (Exception e) {
                appendLine(sb, "getToolType(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getX(pointerIndex)", formatFloat(event.getX(pointerIndex)));
                appendLine(sb, "getY(pointerIndex)", formatFloat(event.getY(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getX/getY(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getPressure(pointerIndex)", formatFloat(event.getPressure(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getPressure(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getSize(pointerIndex)", formatFloat(event.getSize(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getSize(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getTouchMajor(pointerIndex)", formatFloat(event.getTouchMajor(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getTouchMajor(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getTouchMinor(pointerIndex)", formatFloat(event.getTouchMinor(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getTouchMinor(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getToolMajor(pointerIndex)", formatFloat(event.getToolMajor(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getToolMajor(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getToolMinor(pointerIndex)", formatFloat(event.getToolMinor(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getToolMinor(pointerIndex)", errorText(e));
            }

            try {
                appendLine(sb, "getOrientation(pointerIndex)", formatFloat(event.getOrientation(pointerIndex)));
            } catch (Exception e) {
                appendLine(sb, "getOrientation(pointerIndex)", errorText(e));
            }

            appendEmptyLine(sb);
        }
    }

    private void appendHistoricalMotionEventInfo(StringBuilder sb, MotionEvent event) {
        int pointerCount = safeGetPointerCount(event);
        int historySize = safeGetHistorySize(event);

        appendLine(sb, "Nombre de pointeurs", String.valueOf(pointerCount));
        appendLine(sb, "Nombre d'entrées historiques", String.valueOf(historySize));
        appendEmptyLine(sb);

        if (historySize <= 0) {
            appendLine(sb, "Historique", "aucune donnée historique dans ce MotionEvent");
            return;
        }

        for (int historyIndex = 0; historyIndex < historySize; historyIndex++) {
            appendSubSection(sb, "History index " + historyIndex);

            try {
                appendLine(sb, "getHistoricalEventTime(historyIndex)", event.getHistoricalEventTime(historyIndex) + " ms uptime");
            } catch (Exception e) {
                appendLine(sb, "getHistoricalEventTime(historyIndex)", errorText(e));
            }

            for (int pointerIndex = 0; pointerIndex < pointerCount; pointerIndex++) {
                appendLine(sb, "Pointeur", "index " + pointerIndex);

                try {
                    appendLine(sb, "getHistoricalPressure(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalPressure(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalPressure(pointerIndex, historyIndex)", errorText(e));
                }

                try {
                    appendLine(sb, "getHistoricalSize(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalSize(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalSize(pointerIndex, historyIndex)", errorText(e));
                }

                try {
                    appendLine(sb, "getHistoricalTouchMajor(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalTouchMajor(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalTouchMajor(pointerIndex, historyIndex)", errorText(e));
                }

                try {
                    appendLine(sb, "getHistoricalTouchMinor(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalTouchMinor(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalTouchMinor(pointerIndex, historyIndex)", errorText(e));
                }

                try {
                    appendLine(sb, "getHistoricalToolMajor(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalToolMajor(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalToolMajor(pointerIndex, historyIndex)", errorText(e));
                }

                try {
                    appendLine(sb, "getHistoricalToolMinor(pointerIndex, historyIndex)",
                            formatFloat(event.getHistoricalToolMinor(pointerIndex, historyIndex)));
                } catch (Exception e) {
                    appendLine(sb, "getHistoricalToolMinor(pointerIndex, historyIndex)", errorText(e));
                }

                appendEmptyLine(sb);
            }
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private static int safeGetDeviceId(MotionEvent event) {
        try {
            return event.getDeviceId();
        } catch (Exception e) {
            return -1;
        }
    }

    private static InputDevice safeGetInputDevice(int deviceId) {
        try {
            return InputDevice.getDevice(deviceId);
        } catch (Exception e) {
            return null;
        }
    }

    private static int safeGetPointerCount(MotionEvent event) {
        try {
            return Math.max(0, event.getPointerCount());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int safeGetHistorySize(MotionEvent event) {
        try {
            return Math.max(0, event.getHistorySize());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safeSupportsSource(InputDevice device, int source) {
        if (device == null) {
            return "device null";
        }

        try {
            return String.valueOf(device.supportsSource(source)) + " pour " + formatIntDecHex(source);
        } catch (Exception e) {
            return errorText(e);
        }
    }

    private static String describeSources(int sources) {
        StringBuilder sb = new StringBuilder();

        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_TOUCHSCREEN, "SOURCE_TOUCHSCREEN");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_MOUSE, "SOURCE_MOUSE");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_STYLUS, "SOURCE_STYLUS");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_TOUCHPAD, "SOURCE_TOUCHPAD");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_KEYBOARD, "SOURCE_KEYBOARD");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_DPAD, "SOURCE_DPAD");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_JOYSTICK, "SOURCE_JOYSTICK");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_GAMEPAD, "SOURCE_GAMEPAD");
        appendSourceNameIfPresent(sb, sources, InputDevice.SOURCE_TRACKBALL, "SOURCE_TRACKBALL");

        if (sb.length() == 0) {
            return "Aucune source connue décodée pour " + formatIntDecHex(sources);
        }

        return sb.toString();
    }

    private static void appendSourceNameIfPresent(StringBuilder sb, int sources, int sourceToTest, String name) {
        if ((sources & sourceToTest) == sourceToTest) {
            if (sb.length() > 0) {
                sb.append(", ");
            }

            sb.append(name);
        }
    }

    private static String keyboardTypeToString(int keyboardType) {
        switch (keyboardType) {
            case InputDevice.KEYBOARD_TYPE_NONE:
                return "KEYBOARD_TYPE_NONE";
            case InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC:
                return "KEYBOARD_TYPE_NON_ALPHABETIC";
            case InputDevice.KEYBOARD_TYPE_ALPHABETIC:
                return "KEYBOARD_TYPE_ALPHABETIC";
            default:
                return "Type clavier inconnu";
        }
    }

    private static String actionToString(int actionMasked) {
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                return "ACTION_DOWN";
            case MotionEvent.ACTION_UP:
                return "ACTION_UP";
            case MotionEvent.ACTION_MOVE:
                return "ACTION_MOVE";
            case MotionEvent.ACTION_CANCEL:
                return "ACTION_CANCEL";
            case MotionEvent.ACTION_OUTSIDE:
                return "ACTION_OUTSIDE";
            case MotionEvent.ACTION_POINTER_DOWN:
                return "ACTION_POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP:
                return "ACTION_POINTER_UP";
            case MotionEvent.ACTION_HOVER_MOVE:
                return "ACTION_HOVER_MOVE";
            case MotionEvent.ACTION_SCROLL:
                return "ACTION_SCROLL";
            case MotionEvent.ACTION_HOVER_ENTER:
                return "ACTION_HOVER_ENTER";
            case MotionEvent.ACTION_HOVER_EXIT:
                return "ACTION_HOVER_EXIT";
            case MotionEvent.ACTION_BUTTON_PRESS:
                return "ACTION_BUTTON_PRESS";
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return "ACTION_BUTTON_RELEASE";
            default:
                return "Action inconnue";
        }
    }

    private static String toolTypeToString(int toolType) {
        switch (toolType) {
            case MotionEvent.TOOL_TYPE_UNKNOWN:
                return "TOOL_TYPE_UNKNOWN";
            case MotionEvent.TOOL_TYPE_FINGER:
                return "TOOL_TYPE_FINGER";
            case MotionEvent.TOOL_TYPE_STYLUS:
                return "TOOL_TYPE_STYLUS";
            case MotionEvent.TOOL_TYPE_MOUSE:
                return "TOOL_TYPE_MOUSE";
            case MotionEvent.TOOL_TYPE_ERASER:
                return "TOOL_TYPE_ERASER";
            default:
                return "Tool type inconnu";
        }
    }

    private static String batteryStatusToString(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "BATTERY_STATUS_CHARGING";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "BATTERY_STATUS_DISCHARGING";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "BATTERY_STATUS_FULL";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "BATTERY_STATUS_NOT_CHARGING";
            case BatteryManager.BATTERY_STATUS_UNKNOWN:
                return "BATTERY_STATUS_UNKNOWN";
            default:
                return "Statut batterie inconnu";
        }
    }

    private static String pluggedToString(int plugged) {
        if (plugged == 0) {
            return "sur batterie / non branché";
        }

        StringBuilder sb = new StringBuilder();

        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) == BatteryManager.BATTERY_PLUGGED_AC) {
            appendCommaValue(sb, "BATTERY_PLUGGED_AC");
        }

        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) == BatteryManager.BATTERY_PLUGGED_USB) {
            appendCommaValue(sb, "BATTERY_PLUGGED_USB");
        }

        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            appendCommaValue(sb, "BATTERY_PLUGGED_WIRELESS");
        }

        /*
         * Android 13 ajoute BATTERY_PLUGGED_DOCK = 8.
         * Pour rester simple et compatible Android 10, on évite de référencer
         * directement la constante API 33.
         */
        if ((plugged & 8) == 8) {
            appendCommaValue(sb, "BATTERY_PLUGGED_DOCK");
        }

        if (sb.length() == 0) {
            return "source d'alimentation inconnue";
        }

        return sb.toString();
    }

    private static void appendCommaValue(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(", ");
        }

        sb.append(value);
    }

    private static String formatIntDecHex(int value) {
        return String.format(Locale.US, "%d / 0x%08X", value, value);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String safeString(Object value) {
        return String.valueOf(value);
    }

    private static String errorText(Exception e) {
        if (e == null) {
            return "Erreur inconnue";
        }

        return "Erreur : " + e.getClass().getSimpleName() + " - " + safeString(e.getMessage());
    }

    private static void appendSection(StringBuilder sb, String title) {
        sb.append("\n");
        sb.append(title).append(":\n");
    }

    private static void appendSubSection(StringBuilder sb, String title) {
        sb.append("\n");
        sb.append(title).append(":\n");
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        sb.append(label).append(" : ").append(value).append("\n");
    }

    private static void appendEmptyLine(StringBuilder sb) {
        sb.append("\n");
    }
}