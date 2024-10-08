package id.kakzaki.blue_thermal_printer;

import static androidx.core.app.ActivityCompat.startActivityForResult;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

public class BlueThermalPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener, PluginRegistry.ActivityResultListener {

    private static final String TAG = "BThermalPrinterPlugin";
    private static final String NAMESPACE = "blue_thermal_printer";
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;

    private static final int REQUEST_ENABLE_BT = 18098;

    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static ConnectedThread THREAD = null;
    private BluetoothAdapter mBluetoothAdapter;

    private Result pendingResult;

    private Result reconnectPrinterPendingResult;

    private EventSink readSink;
    private EventSink statusSink;

    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private final Object initializationLock = new Object();
    private Context context;
    private MethodChannel channel;

    private EventChannel stateChannel;
    private BluetoothManager mBluetoothManager;

    private Activity activity;

    public BlueThermalPrinterPlugin() {
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        setup(pluginBinding.getBinaryMessenger(), (Application) pluginBinding.getApplicationContext(), activityBinding.getActivity(), activityBinding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        detach();
    }

    private void setup(final BinaryMessenger messenger, final Application application, final Activity activity, final ActivityPluginBinding activityBinding) {
        synchronized (initializationLock) {
            Log.i(TAG, "setup");
            this.activity = activity;
            this.context = application;
            channel = new MethodChannel(messenger, NAMESPACE + "/methods");
            channel.setMethodCallHandler(this);
            stateChannel = new EventChannel(messenger, NAMESPACE + "/state");
            stateChannel.setStreamHandler(stateStreamHandler);
            EventChannel readChannel = new EventChannel(messenger, NAMESPACE + "/read");
            readChannel.setStreamHandler(readResultsHandler);
            mBluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            activityBinding.addRequestPermissionsResultListener(this);
        }
    }


    private void detach() {
        Log.i(TAG, "detach");
        context = null;
        activityBinding.removeRequestPermissionsResultListener(this);
        activityBinding = null;
        channel.setMethodCallHandler(null);
        channel = null;
        stateChannel.setStreamHandler(null);
        stateChannel = null;
        mBluetoothAdapter = null;
        mBluetoothManager = null;
    }


    @Override
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (reconnectPrinterPendingResult != null) {
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth was enabled, proceed with reconnecting
                    reconnectPrinter(reconnectPrinterPendingResult);
                } else {
                    // Bluetooth not enabled
                    reconnectPrinterPendingResult.error("bluetooth_error", "Bluetooth was not enabled", null);
                }
                // Clear the pending result after handling it
                reconnectPrinterPendingResult = null;
            }
            return true;
        }
        return false;
    }


    // MethodChannel.Result wrapper that responds on the platform thread.
    private static class MethodResultWrapper implements Result {
        private final Result methodResult;
        private final Handler handler;

        MethodResultWrapper(Result result) {
            methodResult = result;
            handler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void success(final Object result) {
            handler.post(() -> methodResult.success(result));
        }

        @Override
        public void error(@NonNull final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(() -> methodResult.error(errorCode, errorMessage, errorDetails));
        }

        @Override
        public void notImplemented() {
            handler.post(methodResult::notImplemented);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result rawResult) {
        Result result = new MethodResultWrapper(rawResult);

        if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
            return;
        }

        final Map<String, Object> arguments = call.arguments();
        switch (call.method) {

            case "state":
                state(result);
                break;

            case "isAvailable":
                result.success(mBluetoothAdapter != null);
                break;

            case "reconnectPrinter":
                reconnectPrinter(result);
                break;

            case "isOn":
                try {
                    result.success(mBluetoothAdapter.isEnabled());
                } catch (Exception ex) {
                    result.error("Error", ex.getMessage(), exceptionToString(ex));
                }
                break;

            case "isConnected":
                result.success(THREAD != null);
                break;

            case "isDeviceConnected":
                if (arguments != null && arguments.containsKey("address")) {
                    String address = (String) arguments.get("address");
                    isDeviceConnected(result, address);
                }
                break;

            case "removeSavedDevice":
                removeDeviceAddress(result);
                break;

            case "openSettings":
                ContextCompat.startActivity(context, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), null);
                result.success(true);
                break;

            case "getBondedDevices":
                try {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,}, 1);

                            pendingResult = result;
                            break;
                        }
                    } else {
                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_COARSE_LOCATION_PERMISSIONS);

                            pendingResult = result;
                            break;
                        }
                    }

                    getBondedDevices(result);

                } catch (Exception ex) {
                    result.error("Error", ex.getMessage(), exceptionToString(ex));
                }

                break;

            case "connect":
                if (arguments != null && arguments.containsKey("address")) {
                    String address = (String) arguments.get("address");
                    connect(result, address);
                }
                break;

            case "disconnect":
                disconnect(result);
                break;

            case "write":
                if (arguments != null && arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    write(result, message);
                }
                break;

            case "writeBytes":
                if (arguments != null && arguments.containsKey("message")) {
                    byte[] message = (byte[]) arguments.get("message");
                    writeBytes(result, message);
                }
                break;

            case "printCustom":
                if (arguments != null && arguments.containsKey("message")) {
                    String message = (String) arguments.get("message");
                    int size = (int) arguments.get("size");
                    int align = (int) arguments.get("align");
                    String charset = (String) arguments.get("charset");
                    printCustom(result, message, size, align, charset);
                }
                break;

            case "printNewLine":
                printNewLine(result);
                break;

            case "paperCut":
                paperCut(result);
                break;

            case "drawerPin2":
                drawerPin2(result);
                break;

            case "drawerPin5":
                drawerPin5(result);
                break;

            case "printImage":
                if (arguments != null && arguments.containsKey("pathImage")) {
                    String pathImage = (String) arguments.get("pathImage");
                    printImage(result, pathImage);
                }
                break;

            case "printImageBytes":
                if (arguments != null && arguments.containsKey("bytes")) {
                    byte[] bytes = (byte[]) arguments.get("bytes");
                    printImageBytes(result, bytes);
                }
                break;

            case "printQRcode":
                if (arguments != null && arguments.containsKey("textToQR")) {
                    String textToQR = (String) arguments.get("textToQR");
                    int width = (int) arguments.get("width");
                    int height = (int) arguments.get("height");
                    int align = (int) arguments.get("align");
                    printQRcode(result, textToQR, width, height, align);
                }
                break;
            case "printLeftRight":
                if (arguments != null && arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    printLeftRight(result, string1, string2, size, charset, format);
                }
                break;
            case "print3Column":
                if (arguments != null && arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    String string3 = (String) arguments.get("string3");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    print3Column(result, string1, string2, string3, size, charset, format);
                }
                break;
            case "print4Column":
                if (arguments != null && arguments.containsKey("string1")) {
                    String string1 = (String) arguments.get("string1");
                    String string2 = (String) arguments.get("string2");
                    String string3 = (String) arguments.get("string3");
                    String string4 = (String) arguments.get("string4");
                    int size = (int) arguments.get("size");
                    String charset = (String) arguments.get("charset");
                    String format = (String) arguments.get("format");
                    print4Column(result, string1, string2, string3, string4, size, charset, format);
                }
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * @param requestCode  requestCode
     * @param permissions  permissions
     * @param grantResults grantResults
     * @return boolean
     */
    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, proceed with reconnection and device bonding
                reconnectPrinter(reconnectPrinterPendingResult);
                getBondedDevices(pendingResult);
            } else {
                // Permissions denied, send an error result
                if (pendingResult != null) {
                    pendingResult.error("no_permissions", "This plugin requires location permissions for scanning", null);
                }
            }

            // Clear pending results after handling
            pendingResult = null;
            reconnectPrinterPendingResult = null;

            return true;
        }

        return false;
    }


    private void state(Result result) {
        try {
            switch (mBluetoothAdapter.getState()) {
                case BluetoothAdapter.STATE_OFF:
                    result.success(BluetoothAdapter.STATE_OFF);
                    break;
                case BluetoothAdapter.STATE_ON:
                    result.success(BluetoothAdapter.STATE_ON);
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    result.success(BluetoothAdapter.STATE_TURNING_OFF);
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    result.success(BluetoothAdapter.STATE_TURNING_ON);
                    break;
                default:
                    result.success(0);
                    break;
            }
        } catch (SecurityException e) {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }
    }

    /**
     * @param result result
     */
    private void getBondedDevices(Result result) {

        if (result == null) return;

        List<Map<String, Object>> list = new ArrayList<>();

        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            Map<String, Object> ret = new HashMap<>();
            ret.put("address", device.getAddress());
            ret.put("name", device.getName());
            ret.put("type", device.getType());
            list.add(ret);
        }

        result.success(list);
    }


    /**
     * @param result  result
     * @param address address
     */
    private void isDeviceConnected(Result result, String address) {

        AsyncTask.execute(() -> {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

                if (device == null) {
                    result.error("connect_error", "device not found", null);
                    return;
                }

                if (THREAD != null && BluetoothDevice.ACTION_ACL_CONNECTED.equals(new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).getAction())) {
                    result.success(true);
                } else {
                    result.success(false);
                }

            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }

    private String exceptionToString(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * @param result  result
     * @param address address
     */
    private void connect(Result result, String address) {

        if (THREAD != null) {
            result.error("connect_error", "already connected", null);
            return;
        }


        AsyncTask.execute(() -> {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

                if (device == null) {
                    result.error("connect_error", "device not found", null);
                    return;
                }

                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);

                if (socket == null) {
                    result.error("connect_error", "socket connection not established", null);
                    return;
                }

                // Cancel bt discovery, even though we didn't start it
                mBluetoothAdapter.cancelDiscovery();

                try {
                    socket.connect();
                    THREAD = new ConnectedThread(socket);
                    THREAD.start();
                    saveDeviceAddress(address);

                    result.success(true);
                } catch (Exception ex) {
                    Log.e(TAG, ex.getMessage(), ex);
                    result.error("connect_error", ex.getMessage(), exceptionToString(ex));
                }
            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }


    private void saveDeviceAddress(String address) {
        String uniquePrefsName = context.getPackageName() + "_BluetoothPrefs";
        SharedPreferences sharedPreferences = context.getSharedPreferences(uniquePrefsName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("bluetooth_device_address", address);
        editor.apply();

        Log.d(TAG, "Device address saved: " + address);
    }

    private String getDeviceAddress() {
        String uniquePrefsName = context.getPackageName() + "_BluetoothPrefs";
        SharedPreferences sharedPreferences = context.getSharedPreferences(uniquePrefsName, Context.MODE_PRIVATE);
        return sharedPreferences.getString("bluetooth_device_address", null);
    }


    private void removeDeviceAddress(Result result) {
        try {
            String uniquePrefsName = context.getPackageName() + "_BluetoothPrefs";
            SharedPreferences sharedPreferences = context.getSharedPreferences(uniquePrefsName, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove("bluetooth_device_address");
            editor.apply();
            result.success("Device address removed");
        } catch (Exception ex) {
            result.error(TAG, ex.getMessage(), exceptionToString(ex));
        }
    }


    /**
     * @param result result
     */
    private void disconnect(Result result) {

        if (THREAD == null) {
            result.error("disconnection_error", "not connected", null);
            return;
        }
        AsyncTask.execute(() -> {
            try {
                THREAD.cancel();
                THREAD = null;
                result.success(true);
            } catch (Exception ex) {
                Log.e(TAG, ex.getMessage(), ex);
                result.error("disconnection_error", ex.getMessage(), exceptionToString(ex));
            }
        });
    }

    /**
     * @param result  result
     * @param message message
     */
    private void write(Result result, String message) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            THREAD.write(message.getBytes());
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void writeBytes(Result result, byte[] message) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            THREAD.write(message);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printCustom(Result result, String message, int size, int align, String charset) {
        // Print config "mode"
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        byte[] bb5 = new byte[]{0x1B, 0x21, 0x50}; // 5- extra strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }

        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
                case 5:
                    THREAD.write(bb5);
            }

            switch (align) {
                case 0:
                    // left align
                    THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
                    break;
                case 1:
                    // center align
                    THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                    break;
                case 2:
                    // right align
                    THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
                    break;
            }
            if (charset != null) {
                THREAD.write(message.getBytes(charset));
            } else {
                THREAD.write(message.getBytes());
            }
            THREAD.write(PrinterCommands.FEED_LINE);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printLeftRight(Result result, String msg1, String msg2, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-15s %15s %n", msg1, msg2);
            if (format != null) {
                line = String.format(format, msg1, msg2);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void print3Column(Result result, String msg1, String msg2, String msg3, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-10s %10s %10s %n", msg1, msg2, msg3);
            if (format != null) {
                line = String.format(format, msg1, msg2, msg3);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void print4Column(Result result, String msg1, String msg2, String msg3, String msg4, int size, String charset, String format) {
        byte[] cc = new byte[]{0x1B, 0x21, 0x03}; // 0- normal size text
        // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
        byte[] bb = new byte[]{0x1B, 0x21, 0x08}; // 1- only bold text
        byte[] bb2 = new byte[]{0x1B, 0x21, 0x20}; // 2- bold with medium text
        byte[] bb3 = new byte[]{0x1B, 0x21, 0x10}; // 3- bold with large text
        byte[] bb4 = new byte[]{0x1B, 0x21, 0x30}; // 4- strong text
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (size) {
                case 0:
                    THREAD.write(cc);
                    break;
                case 1:
                    THREAD.write(bb);
                    break;
                case 2:
                    THREAD.write(bb2);
                    break;
                case 3:
                    THREAD.write(bb3);
                    break;
                case 4:
                    THREAD.write(bb4);
                    break;
            }
            THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
            String line = String.format("%-8s %7s %7s %7s %n", msg1, msg2, msg3, msg4);
            if (format != null) {
                line = String.format(format, msg1, msg2, msg3, msg4);
            }
            if (charset != null) {
                THREAD.write(line.getBytes(charset));
            } else {
                THREAD.write(line.getBytes());
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }

    }

    private void printNewLine(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.FEED_LINE);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void paperCut(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.FEED_PAPER_AND_CUT);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void drawerPin2(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.ESC_DRAWER_PIN2);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void drawerPin5(Result result) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            THREAD.write(PrinterCommands.ESC_DRAWER_PIN5);
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printImage(Result result, String pathImage) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeFile(pathImage);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printImageBytes(Result result, byte[] bytes) {
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private void printQRcode(Result result, String textToQR, int width, int height, int align) {
        MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
        if (THREAD == null) {
            result.error("write_error", "not connected", null);
            return;
        }
        try {
            switch (align) {
                case 0:
                    // left align
                    THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
                    break;
                case 1:
                    // center align
                    THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
                    break;
                case 2:
                    // right align
                    THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
                    break;
            }
            BitMatrix bitMatrix = multiFormatWriter.encode(textToQR, BarcodeFormat.QR_CODE, width, height);
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            Bitmap bmp = barcodeEncoder.createBitmap(bitMatrix);
            if (bmp != null) {
                byte[] command = Utils.decodeBitmap(bmp);
                THREAD.write(command);
            } else {
                Log.e("Print Photo error", "the file isn't exists");
            }
            result.success(true);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
            result.error("write_error", ex.getMessage(), exceptionToString(ex));
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    readSink.success(new String(buffer, 0, bytes));
                } catch (NullPointerException e) {
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {

                String errorMessage = "Error writing data: " + e.getMessage();

                Log.e(TAG, errorMessage);

                if (Objects.requireNonNull(e.getMessage()).contains("Broken pipe")) {
                    THREAD = null;
                    reconnectPrinter();
                    return;
                }
                // Show error as a Toast message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show();
            }
        }


        public void cancel() {
            try {
                outputStream.flush();
                outputStream.close();

                inputStream.close();

                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public void reconnectPrinter() {
        reconnectPrinter(null);
    }


    public void reconnectPrinter(@Nullable Result result) {

        THREAD = null;

        // Ask Bluetooth Permission
        boolean permissionRequired = askBluetoothPermission();

        if (permissionRequired) {
            if (result != null) {
                reconnectPrinterPendingResult = result;
            }
            return;
        }


        // Check if BluetoothAdapter is available and enabled
        if (mBluetoothAdapter == null) {
            Log.e("bluetooth_error", "Bluetooth not supported on this device");
            if (result != null) {
                result.error("bluetooth_error", "Bluetooth not supported on this device", null);
            }
            return;
        }

        // If Bluetooth is not enabled, prompt the user to enable it
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // Assuming this is in an Activity, otherwise you will need to pass the activity context
            startActivityForResult(activity, enableBtIntent, REQUEST_ENABLE_BT, null);
            Log.d("bluetooth_status", "Bluetooth is not enabled. Requesting user to enable it.");
            reconnectPrinterPendingResult = result;
            return;
        }

        BluetoothSocket socket = null;

        try {

            // Get the previous device address
            String prevDeviceAddress = getDeviceAddress();

            if (prevDeviceAddress == null) {
                Log.d("connect_error", "No previous device address found");
                if (result != null) {
                    result.success("No previous device address found");
                }
                return;
            }

            Log.d("SAVED_BLUETOOTH_DEVICE", prevDeviceAddress);

            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(prevDeviceAddress);
            if (device == null) {
                Log.d("connect_error", "Device not found");
                if (result != null) {
                    result.error("connect_error", "Device not found", null);
                }
                return;
            }


            // Attempt to create the socket
            socket = device.createRfcommSocketToServiceRecord(MY_UUID);

            // Cancel discovery as it may slow down the connection
            mBluetoothAdapter.cancelDiscovery();

            // Try connecting the socket
            socket.connect();
            THREAD = new ConnectedThread(socket);
            THREAD.start();

            Log.d("connect_status", "Connection established successfully");

            // Notify result if provided
            if (result != null) {
                result.success(true);
            }

        } catch (IOException ex) {
            Log.e("connect_error", "Failed to connect: " + ex.getMessage(), ex);
            if (socket != null) {
                closeSocket(socket);
            }

            // Report the error through the result object
            if (result != null) {
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }

        } catch (Exception ex) {
            Log.e("connect_error", "Unexpected error: " + ex.getMessage(), ex);
            if (result != null) {
                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
            }

        } finally {
            if (socket != null && !socket.isConnected()) {
                closeSocket(socket);
            }
        }
    }

    private boolean askBluetoothPermission() {
        try {
            // For Android S (API level 31) and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Check Bluetooth and Location permissions
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    // Request the required permissions
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,}, 1);

                    return true; // Permissions requested
                }
            } else { // For versions below Android S
                // Check location permissions
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    // Request location permissions
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_COARSE_LOCATION_PERMISSIONS);

                    return true; // Permissions requested
                }
            }

        } catch (Exception ex) {
            Log.e("Error", exceptionToString(ex));
            return false;
        }

        // Permissions are already granted
        return false;
    }


    private void closeSocket(BluetoothSocket socket) {
        try {
            socket.close();
            Log.d("connect_status", "Socket closed successfully");
        } catch (IOException e) {
            Log.e("connect_error", "Error closing socket: " + e.getMessage(), e);
        }
    }


    private final StreamHandler stateStreamHandler = new StreamHandler() {

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case BluetoothAdapter.ACTION_STATE_CHANGED:
                            THREAD = null;
                            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                            if (state == BluetoothAdapter.STATE_ON) {
                                statusSink.success(BluetoothAdapter.STATE_ON);
                                reconnectPrinter();
                            } else if (state == BluetoothAdapter.STATE_OFF) {
                                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                                // Assuming this is in an Activity, otherwise you will need to pass the activity context
                                startActivityForResult(activity, enableBtIntent, REQUEST_ENABLE_BT, null);
                                Log.d("bluetooth_status", "Bluetooth is not enabled. Requesting user to enable it.");
                            }
                            statusSink.success(state);
                            break;
                        case BluetoothDevice.ACTION_ACL_CONNECTED:
                            statusSink.success(1);
                            break;
                        case BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED:
                            THREAD = null;
                            statusSink.success(2);
                            break;
                        case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                            THREAD = null;
                            statusSink.success(0);
                            break;
                    }
                }
            }
        };

        @Override
        public void onListen(Object o, EventSink eventSink) {
            statusSink = eventSink;
            context.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));

            context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));

        }

        @Override
        public void onCancel(Object o) {
            statusSink = null;
            context.unregisterReceiver(mReceiver);
        }
    };

    private final StreamHandler readResultsHandler = new StreamHandler() {
        @Override
        public void onListen(Object o, EventSink eventSink) {
            readSink = eventSink;
        }

        @Override
        public void onCancel(Object o) {
            readSink = null;
        }
    };
}