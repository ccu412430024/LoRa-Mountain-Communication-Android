package com.example.lora_mountain_communication;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.content.Intent;

public class MainActivity extends AppCompatActivity {

    // 必須與 ESP32 程式碼中的 UUID 一致
    private static final UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private boolean isScanning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // UI 元件 (移除 etFreq, etName)
    private TextView tvStatus;
    private Button btnScan, btnSave, btnDisconnect, btnAbout;
    private EditText etId; // 這個是用來輸入字串 ID 的
    private LinearLayout layoutConfig;

    // 掃描到的裝置列表
    private ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;
    private ArrayList<String> deviceNameList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 UI
        tvStatus = findViewById(R.id.tvStatus);
        btnScan = findViewById(R.id.btnScan);
        btnSave = findViewById(R.id.btnSave);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnAbout = findViewById(R.id.btnAbout);

        etId = findViewById(R.id.etId);
        layoutConfig = findViewById(R.id.layoutConfig);

        // 初始化藍牙
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        btnScan.setOnClickListener(v -> startScan());
        btnSave.setOnClickListener(v -> saveConfig());
        btnDisconnect.setOnClickListener(v -> disconnect());
        // [新增] 關於我們按鈕事件
        btnAbout.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AboutActivity.class);
            startActivity(intent);
        });
    }

    // =================================================================
    // 1. 權限檢查與掃描
    // =================================================================
    private void startScan() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        if (isScanning) return;

        deviceList.clear();
        deviceNameList.clear();

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            updateStatus("藍牙未開啟");
            return;
        }

        updateStatus("正在掃描 LoRa 裝置...");
        isScanning = true;

        // 5秒後停止掃描
        handler.postDelayed(this::stopScanAndShowList, 5000);

        try {
            bluetoothLeScanner.startScan(scanCallback);
        } catch (SecurityException e) {
            updateStatus("權限錯誤: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();

            // 1. 安全取得名字 (防止 NPE)
            // 如果 device.getName() 是 null，我們就把它當作 "Unknown Device"
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String name = device.getName();
            String safeName = (name != null) ? name : "Unknown";

            // 2. [除錯] 印出所有掃到的東西 (包含無名氏)
            Log.d("BLE_DEBUG", "Scanned: " + safeName + " [" + device.getAddress() + "]");

            // 3. 過濾邏輯 (放寬標準)
            // 只要名字有對到，或者 (名字是空的 但 UUID 對) 就顯示
            boolean isTarget = false;

            if (name != null && name.startsWith("LoRa_Node_")) {
                isTarget = true;
            }
            // 額外檢查：如果名字是 null，檢查 ScanRecord 裡的 UUID (比較穩)
            else if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                if (result.getScanRecord().getServiceUuids().contains(new android.os.ParcelUuid(SERVICE_UUID))) {
                    isTarget = true;
                }
            }

            if (isTarget) {
                addDeviceToList(device, safeName);
            }
        }
    };

    // 抽取出加入列表的函式，避免重複代碼
    private void addDeviceToList(BluetoothDevice device, String displayName) {
        if (!deviceList.contains(device)) {
            deviceList.add(device);
            deviceNameList.add(displayName + "\n" + device.getAddress());

            // 更新 UI
            // 注意：ScanCallback 不一定在主執行緒，更新 UI 要小心，
            // 但 ArrayAdapter 內部通常有處理，或是前面 showDeviceSelectionDialog 會重讀 list
            Log.i("BLE", "Found Target: " + displayName);
        }
    }

    private void stopScanAndShowList() {
        if (!isScanning) return;
        isScanning = false;
        try {
            bluetoothLeScanner.stopScan(scanCallback);
        } catch (SecurityException e) {}

        if (deviceList.isEmpty()) {
            updateStatus("未發現裝置，請靠近再試");
            return;
        }

        showDeviceSelectionDialog();
    }

    private void showDeviceSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("選擇裝置");
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNameList);

        builder.setAdapter(deviceListAdapter, (dialog, which) -> {
            BluetoothDevice device = deviceList.get(which);
            connectToDevice(device);
        });
        builder.show();
    }

    // =================================================================
    // 2. 連線與讀取
    // =================================================================
    private void connectToDevice(BluetoothDevice device) {
        try {
            updateStatus("正在連線到 " + device.getName() + "...");
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            updateStatus("連線失敗: 權限不足");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread(() -> updateStatus("已連線，正在讀取服務..."));
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread(() -> {
                        updateStatus("已斷線");
                        layoutConfig.setVisibility(View.GONE);
                        btnScan.setEnabled(true);
                    });
                }
            } catch (SecurityException e) {}
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
                    if (characteristic != null) {
                        try {
                            gatt.readCharacteristic(characteristic);
                        } catch (SecurityException e) {}
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                String jsonString = new String(data, StandardCharsets.UTF_8);
                Log.i("BLE", "Read: " + jsonString);

                runOnUiThread(() -> {
                    parseJsonAndFillUI(jsonString);
                    layoutConfig.setVisibility(View.VISIBLE);
                    btnScan.setEnabled(false);
                    updateStatus("讀取成功！");
                });
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> {
                    updateStatus("寫入成功！裝置正在重啟...");
                    Toast.makeText(MainActivity.this, "設定已儲存", Toast.LENGTH_LONG).show();
                    disconnect();
                });
            }
        }
    };

    private void parseJsonAndFillUI(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            // 只讀取 id 欄位 (現在是字串)
            etId.setText(obj.getString("id"));
        } catch (JSONException e) {
            updateStatus("JSON 解析失敗");
        }
    }

    // =================================================================
    // 3. 寫入設定
    // =================================================================
    private void saveConfig() {
        try {
            JSONObject obj = new JSONObject();
            // 取得輸入的文字 (字串)
            String newId = etId.getText().toString().trim();

            if(newId.isEmpty()) {
                Toast.makeText(this, "ID 不能為空", Toast.LENGTH_SHORT).show();
                return;
            }

            // 寫入 JSON
            obj.put("id", newId);

            String jsonString = obj.toString();
            writeCharacteristic(jsonString);

        } catch (Exception e) {
            Toast.makeText(this, "格式錯誤", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeCharacteristic(String data) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
            if (characteristic != null) {
                characteristic.setValue(data.getBytes(StandardCharsets.UTF_8));
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                try {
                    bluetoothGatt.writeCharacteristic(characteristic);
                } catch (SecurityException e) {
                    updateStatus("寫入失敗");
                }
            }
        }
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {}
            bluetoothGatt = null;
        }
        updateStatus("已斷線");
        layoutConfig.setVisibility(View.GONE);
        btnScan.setEnabled(true);
    }

    // =================================================================
    // 輔助函式：權限與 UI
    // =================================================================
    private void updateStatus(String msg) {
        tvStatus.setText("狀態: " + msg);
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            Toast.makeText(this, "需要藍牙權限才能運作", Toast.LENGTH_SHORT).show();
        }
    }
}