/*
Copyright (c) 2016, Cypress Semiconductor Corporation
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.



For more information on Cypress BLE products visit:
http://www.cypress.com/products/bluetooth-low-energy-ble
 */

package com.cypress.academy.ble101;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothGattCharacteristic;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing the BLE data connection with the GATT database.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP) // This is required to allow us to use the lollipop and later scan APIs
public class mBleService extends Service {
    private final static String TAG = mBleService.class.getSimpleName();

    private static final long SCAN_PERIOD = 5000;

    private Handler mHandler;

    // Bluetooth objects that we need to interact with
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;
    private static BluetoothLeScanner mLEScanner;
    private static BluetoothDevice mLeDevice;
    private static BluetoothGatt mBluetoothGatt;

    // Bluetooth characteristics that we need to read/write
    private static BluetoothGattCharacteristic mLedCharacterisitc;
    private static BluetoothGattCharacteristic mCapsenseCharacteristic;
    private static BluetoothGattDescriptor mCapSenseCccd;

    private static BluetoothGattCharacteristic mVibIntensityCharacteristic;
    private static BluetoothGattCharacteristic mAlarmCharacteristic;
    private static BluetoothGattDescriptor mAlarmCccd;

    // Notification objects
    private static NotificationManager mNotifManager;


    // UUIDs for the service and characteristics that the custom CapSenseLED service uses
    private final static String baseUUID =                       "00000000-0000-1000-8000-00805f9b34f";
    private final static String capsenseLedServiceUUID =         baseUUID + "0";
    public  final static String ledCharacteristicUUID =          baseUUID + "1";
    public  final static String capsenseCharacteristicUUID =     baseUUID + "2";
    private final static String alarmCharacteristicUUID =        baseUUID + "5";
    private final static String vibIntensityCharacteristicUUID = baseUUID + "4";
    private final static String CccdUUID =                       "00002902-0000-1000-8000-00805f9b34fb";

     // Variables to keep track of the LED switch state and CapSense Value
    private static boolean mLedSwitchState = false;
    private static String mCapSenseValue = "-1"; // This is the No Touch value (0xFFFF)

    private static boolean mBleConnected = false;

    private static int mVibIntensityId;

    // Actions used during broadcasts to the main activity
    public final static String ACTION_BLESCAN_CALLBACK =
            "com.cypress.academy.ble101.ACTION_BLESCAN_CALLBACK";
    public final static String ACTION_CONNECTED =
            "com.cypress.academy.ble101.ACTION_CONNECTED";
    public final static String ACTION_DISCONNECTED =
            "com.cypress.academy.ble101.ACTION_DISCONNECTED";
    public final static String ACTION_SERVICES_DISCOVERED =
            "com.cypress.academy.ble101.ACTION_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_RECEIVED =
            "com.cypress.academy.ble101.ACTION_DATA_RECEIVED";
    public final static String ACTION_DEVICE_NOT_FOUND =
            "com.cypress.academy.ble101.ACTION_DEVICE_NOT_FOUND";

    // Actions used to broadcast to service
    public final static String ACTION_ALARM_NOTIF_DISMISSED =
            "com.cypress.academy.ble101.ACTION_ALARM_NOTIF_DISMISSED";


    public mBleService() {
    }

    /**
     * This is a binder for the mBleService
     */
    public class LocalBinder extends Binder {
        mBleService getService() {
            return mBleService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // The BLE close method is called when we unbind the service to free up the resources.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return START_STICKY;
    }

    private final BroadcastReceiver mNotifReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG,"Service broadcast receiver triggered");
            switch (action) {
                case mBleService.ACTION_ALARM_NOTIF_DISMISSED:
                    Log.d(TAG, "Notification dismissed");
                    if (mBluetoothGatt != null) {
                        byte[] byteVal = new byte[1];
                        byteVal[0] = (byte)(0);
                        Log.i(TAG, "Clearing the alarm state and writing it back");
                        mAlarmCharacteristic.setValue(byteVal);

                        while (!mBluetoothGatt.writeCharacteristic(mAlarmCharacteristic)) {

                            Log.i(TAG, "Writing alarm characteristics failed");
                        }

                        // Check whether the value was written
                        byteVal[0] = (byte)(3);
                        mAlarmCharacteristic.setValue(byteVal);
                        mBluetoothGatt.readCharacteristic(mAlarmCharacteristic);

                        Log.i(TAG, "Alarm Characteristic is now " + mAlarmCharacteristic.getValue()[0]);

                        unregisterReceiver(this);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Initializes necessary references to the system services.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        mHandler = new Handler();

        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        // Set up notification capabilities
        if (mNotifManager == null) {
            mNotifManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        }
        return true;
    }

    /**
     * Scans for BLE devices that support the service we are looking for
     */
    public void scan() {
        /* Scan for devices and look for the one with the service that we want */
        UUID   capsenseLedService =       UUID.fromString(capsenseLedServiceUUID);
        UUID[] capsenseLedServiceArray = {capsenseLedService};

        // Use old scan method for versions older than lollipop
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mLeDevice == null) {
                        //noinspection deprecation
                        mBluetoothAdapter.stopLeScan(mLeScanCallback);
                        broadcastUpdate(ACTION_DEVICE_NOT_FOUND);
                    }
                }
            }, SCAN_PERIOD);
            //noinspection deprecation
            mBluetoothAdapter.startLeScan(capsenseLedServiceArray, mLeScanCallback);
        } else { // New BLE scanning introduced in LOLLIPOP
            ScanSettings settings;
            List<ScanFilter> filters;
            mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            filters = new ArrayList<>();
            // We will scan just for the CAR's UUID
            ParcelUuid PUuid = new ParcelUuid(capsenseLedService);
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(PUuid).build();
            filters.add(filter);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mLeDevice == null) {
                        //noinspection deprecation
                        mLEScanner.stopScan(mScanCallback);
                        broadcastUpdate(ACTION_DEVICE_NOT_FOUND);
                    }
                }
            }, SCAN_PERIOD);
            mLEScanner.startScan(filters, settings, mScanCallback);
        }
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            return mBluetoothGatt.connect();
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = mLeDevice.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");

        return true;
    }

    /**
     * Runs service discovery on the connected device.
     */
    public void discoverServices() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.discoverServices();
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
/*    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }
*/
    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * This method is used to read the state of the LED from the device
     */
     public void readLedCharacteristic() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(mLedCharacterisitc);
     }

    /**
     * This method is used to turn the LED on or off
     *
     * @param value Turns the LED on (1) or off (0)
     */
    public void writeLedCharacteristic(boolean value) {
        byte[] byteVal = new byte[1];
        if (value) {
            byteVal[0] = (byte) (1);
        } else {
            byteVal[0] = (byte) (0);
        }
        Log.i(TAG, "LED " + value);
        mLedSwitchState = value;
        mLedCharacterisitc.setValue(byteVal);
        mBluetoothGatt.writeCharacteristic(mLedCharacterisitc);
    }

    /**
     * This method enables or disables notifications for the CapSense slider
     *
     * @param value Turns notifications on (1) or off (0)
     */
    public void writeCapSenseNotification(boolean value) {
        // Set notifications locally in the CCCD
        mBluetoothGatt.setCharacteristicNotification(mCapsenseCharacteristic, value);
        byte[] byteVal = new byte[1];
        if (value) {
            byteVal[0] = 1;
        } else {
            byteVal[0] = 0;
        }
        // Write Notification value to the device
        Log.i(TAG, "CapSense Notification " + value);

        mCapSenseCccd.setValue(byteVal);
        mBluetoothGatt.writeDescriptor(mCapSenseCccd);
    }

    /**
     * This method is used to configure the vibration intensity
     *
     * @param intensityId Configures the vibration intensity to one of Mute (0), Low (1),
     *                    Medium (2), and High (3)
     */
    public void writeVibIntensityCharacteristic(int intensityId) {
        byte[] byteVal = new byte[1];
        byteVal[0] = (byte)(intensityId);

        if (mBluetoothGatt != null) {
            Log.i(TAG, "Writing vibration intensity; new intensity = " + intensityId);
            mVibIntensityId = intensityId;
            mVibIntensityCharacteristic.setValue(byteVal);
            mBluetoothGatt.writeCharacteristic(mVibIntensityCharacteristic);
        }
    }

    /**
     * This method returns the state of the LED switch
     *
     * @return the value of the LED swtich state
     */
    public boolean getLedSwitchState() {
        return mLedSwitchState;
    }

    /**
     * This method returns the value of th CapSense Slider
     *
     * @return the value of the CapSense Slider
     */
    public String getCapSenseValue() {
        return mCapSenseValue;
    }


    /**
     * Implements the callback for when scanning for devices has found a device with
     * the service we are looking for.
     *
     * This is the callback for BLE scanning on versions prior to Lollipop
     */
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    mLeDevice = device;
                    //noinspection deprecation
                    mBluetoothAdapter.stopLeScan(mLeScanCallback); // Stop scanning after the first device is found
                    broadcastUpdate(ACTION_BLESCAN_CALLBACK); // Tell the main activity that a device has been found
                }
            };

    /**
     * Implements the callback for when scanning for devices has faound a device with
     * the service we are looking for.
     *
     * This is the callback for BLE scanning for LOLLIPOP and later
     */
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mLeDevice = result.getDevice();
            mLEScanner.stopScan(mScanCallback); // Stop scanning after the first device is found
            broadcastUpdate(ACTION_BLESCAN_CALLBACK); // Tell the main activity that a device has been found
        }
    };


    /**
     * Implements callback methods for GATT events that the app cares about.  For example,
     * connection change and services discovered.
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mBleConnected = true;
                broadcastUpdate(ACTION_CONNECTED);
                Log.i(TAG, "Connected to GATT server.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Insert a new notification here
                mBleConnected = false;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(ACTION_DISCONNECTED);
            }
        }

        /**
         * This is called when a service discovery has completed.
         *
         * It gets the characteristics we are interested in and then
         * broadcasts an update to the main activity.
         *
         * @param gatt The GATT database object
         * @param status Status of whether the write was successful.
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            // Get just the service that we are looking for
            BluetoothGattService mService = gatt.getService(UUID.fromString(capsenseLedServiceUUID));

            /* Get characteristics from our desired service */
            mLedCharacterisitc = mService.getCharacteristic(UUID.fromString(ledCharacteristicUUID));
            mCapsenseCharacteristic = mService.getCharacteristic(UUID.fromString(capsenseCharacteristicUUID));
            mVibIntensityCharacteristic = mService.getCharacteristic(
                    UUID.fromString(vibIntensityCharacteristicUUID));
            mAlarmCharacteristic = mService.getCharacteristic(UUID.fromString(alarmCharacteristicUUID));
            mAlarmCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            /* Get the CapSense CCCD */
            mCapSenseCccd = mCapsenseCharacteristic.getDescriptor(UUID.fromString(CccdUUID));

            mAlarmCccd = mAlarmCharacteristic.getDescriptor(UUID.fromString(CccdUUID));

            // Enable notification of alarm
            byte[] byteVal = new byte[1];
            byteVal[0] = (byte)(1);
            mBluetoothGatt.setCharacteristicNotification(mAlarmCharacteristic,true);
            mAlarmCccd.setValue(byteVal);
            mBluetoothGatt.writeDescriptor(mAlarmCccd);

            // Read the current state of the LED from the device
            readLedCharacteristic();

            // Broadcast that service/characteristic/descriptor discovery is done
            broadcastUpdate(ACTION_SERVICES_DISCOVERED);
        }

        /**
         * This is called when a read completes
         *
         * @param gatt the GATT database object
         * @param characteristic the GATT characteristic that was read
         * @param status the status of the transaction
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Verify that the read was the LED state
                String uuid = characteristic.getUuid().toString();
                // In this case, the only read the app does is the LED state.
                // If the application had additional characteristics to read we could
                // use a switch statement here to operate on each one separately.
                if(uuid.equals(ledCharacteristicUUID)) {
                    final byte[] data = characteristic.getValue();
                    // Set the LED switch state variable based on the characteristic value ttat was read
                    mLedSwitchState = ((data[0] & 0xff) != 0x00);
                }
                // Notify the main activity that new data is available
                broadcastUpdate(ACTION_DATA_RECEIVED);
            }
        }

        /**
         * This is called when a characteristic with notify set changes.
         * It broadcasts an update to the main activity with the changed data.
         *
         * @param gatt The GATT database object
         * @param characteristic The characteristic that was changed
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            String uuid = characteristic.getUuid().toString();
            Log.d(TAG,"Received something");

            // In this case, the only notification the apps gets is the CapSense value.
            // If the application had additional notifications we could
            // use a switch statement here to operate on each one separately.
            if(uuid.equals(capsenseCharacteristicUUID)) {
                mCapSenseValue = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16,0).toString();
            }

            //
            if(uuid.equals(alarmCharacteristicUUID)) {
                int alarmId = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8,0);

                if (alarmId != 0) {
                    String alarmType = "";
                    if (alarmId == 1) {
                        alarmType = "A fire Alarm";
                    } else if (alarmId == 2) {
                        alarmType = "An ambulance";
                    }
                    Log.d(TAG,"Attempted notification");

                    Intent intent = new Intent(ACTION_ALARM_NOTIF_DISMISSED);

                    PendingIntent pIntent = PendingIntent.getBroadcast(mBleService.this,
                                                                        0,intent,0);
                    registerReceiver(mNotifReceiver,new IntentFilter(ACTION_ALARM_NOTIF_DISMISSED));

                    NotificationCompat.Builder alarmNotifBuilder =
                            new NotificationCompat.Builder(mBleService.this)
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setContentTitle("Alarm Detected")
                                    .setContentText(alarmType + " alarm just went off.")
                                    .setContentIntent(pIntent)
                                    .setPriority(Notification.PRIORITY_MAX)
                                    .setDefaults(Notification.DEFAULT_ALL);

                    alarmNotifBuilder.setAutoCancel(true);

                    NotificationManagerCompat.from(mBleService.this).notify(0,alarmNotifBuilder.build());
                }

            }

            // Notify the main activity that new data is available
            broadcastUpdate(ACTION_DATA_RECEIVED);
        }
    }; // End of GATT event callback methods

    /**
     * Sends a broadcast to the listener in the main activity.
     *
     * @param action The type of action that occurred.
     */
    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

}