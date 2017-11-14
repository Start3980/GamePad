package com.ginkage.gamepad.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.util.Log;

import com.ginkage.gamepad.R;
import com.ginkage.gamepad.bluetooth.HidDataSender;
import com.ginkage.gamepad.bluetooth.HidHostProfile;
import com.google.android.clockwork.bluetooth.BluetoothUtils;
import com.google.common.base.Preconditions;

import javax.annotation.Nonnull;

/**
 * Bluetooth device discovery scan for available devices.
 *
 * <p>When this fragment is created a scan will automatically be initiated. There is also a
 * preference to initiate a BT re-scan from this fragment once the initial scan has terminated due
 * to limited discovery time.
 *
 * <p>Once a scan has initiated, each BT device discovered is bound to a preference indexed by the
 * BT mac address. These preferences are then shown in an available preference catagory presented to
 * the user.
 *
 * <p>Users click on a preference in a the available preference catagory to intiate a bonding
 * sequence with the device. Should the bond be successful, the device will disappear from the
 * available preference catagory and appear in the previous fragment bonded preference list. If the
 * bond fails the device will remain in the available preference catagory.
 *
 * <p>This class is largely based on the one from
 * https://cs.corp.google.com/android/vendor/google_clockwork/packages/Settings/src/com/google/android/clockwork/settings/connectivity/bluetooth/BluetoothScanFragment.java
 * with a few modifications.
 */
public class BluetoothScanFragment extends PreferenceFragment {
    private static final String TAG = "BluetoothScan";

    private static final String KEY_PREF_BLUETOOTH_SCAN = "pref_bluetoothScan";
    private static final String KEY_PREF_BLUETOOTH_AVAILABLE = "pref_bluetoothAvailable";

    private static final int PERMISSION_REQUEST = 1;

    private BluetoothAdapter bluetoothAdapter;
    private HidHostProfile hidHostProfile;
    private HidDataSender hidDataSender;

    private BluetoothStateReceiver stateReceiver;
    private BluetoothScanReceiver scanReceiver;

    private Preference initiateScanDevices;
    private PreferenceGroup availableDevices;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            addPreferencesFromResource(R.xml.prefs_bluetooth_scan);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }

        Context context = getContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        hidDataSender = HidDataSender.getInstance(context);
        hidHostProfile = hidDataSender.register(profileListener);

        initiateScanDevices = findPreference(KEY_PREF_BLUETOOTH_SCAN);
        availableDevices = (PreferenceGroup) findPreference(KEY_PREF_BLUETOOTH_AVAILABLE);
        availableDevices.setLayoutResource(R.layout.preference_group_no_title);

        initScanDevices(initiateScanDevices);
        initAvailDevices();

        registerStateReceiver();

        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @Nonnull String[] permissions, @Nonnull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            getActivity().finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkBluetoothState();
    }

    @Override
    public void onPause() {
        stopDiscovery();
        unregisterScanReceiver();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        unregisterStateReceiver();
        hidDataSender.unregister(profileListener);
        super.onDestroy();
    }

    protected void initScanDevices(Preference pref) {
        if (bluetoothAdapter.isDiscovering()) {
            pref.setEnabled(false);
        }

        pref.setOnPreferenceClickListener(
                (p) -> {
                    clearAvailDevices();
                    startDiscovery();
                    return true;
                });
    }

    protected void initAvailDevices() {
        clearAvailDevices();
    }

    protected BluetoothDevicePreference addAvailableDevice(BluetoothDevice device) {
        final BluetoothDevicePreference pref = findOrAllocateDevicePreference(device);
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            availableDevices.addPreference(pref);
            pref.setEnabled(true);
        }
        return pref;
    }

    /** Re-examine the device and update if necessary. */
    protected void updateAvailableDevice(BluetoothDevice device) {
        final BluetoothDevicePreference pref = findDevicePreference(device);
        if (pref != null) {
            pref.updateBondState();
            switch (device.getBondState()) {
                case BluetoothDevice.BOND_BONDED:
                    pref.setEnabled(false);
                    availableDevices.removePreference(pref);
                    break;
                case BluetoothDevice.BOND_BONDING:
                    pref.setEnabled(false);
                    break;
                case BluetoothDevice.BOND_NONE:
                    pref.setEnabled(true);
                    addAvailableDevice(device);
                    break;
                default: // fall out
            }
        }
    }

    protected void clearAvailDevices() {
        availableDevices.removeAll();
    }

    /** Handles changes in the bluetooth adapter state. */
    protected void checkBluetoothState() {
        switch (bluetoothAdapter.getState()) {
            case BluetoothAdapter.STATE_OFF:
                initiateScanDevices.setTitle(R.string.generic_disabled);
                initiateScanDevices.setEnabled(false);
                clearAvailDevices();
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
            case BluetoothAdapter.STATE_TURNING_OFF:
                initiateScanDevices.setEnabled(false);
                clearAvailDevices();
                startActivity(new Intent(getActivity(), BluetoothStateActivity.class));
                break;
            case BluetoothAdapter.STATE_ON:
                initiateScanDevices.setTitle(R.string.pref_bluetoothScan);
                initiateScanDevices.setEnabled(true);
                registerScanReceiver();
                startDiscovery();
                break;
            default: // fall out
        }
    }

    private void startDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
        initiateScanDevices.setEnabled(false);
    }

    private void stopDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void registerScanReceiver() {
        if (scanReceiver != null) {
            return;
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        getContext().registerReceiver(scanReceiver = new BluetoothScanReceiver(), intentFilter);

        BluetoothUtils.setDiscoverableTimeout(bluetoothAdapter, 0);
        BluetoothUtils.setScanMode(
                bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
    }

    private void unregisterScanReceiver() {
        if (scanReceiver != null) {
            getContext().unregisterReceiver(scanReceiver);
            scanReceiver = null;
            BluetoothUtils.setScanMode(bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE);
        }
    }

    private void registerStateReceiver() {
        Preconditions.checkArgument(stateReceiver == null);
        final IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        getContext().registerReceiver(stateReceiver = new BluetoothStateReceiver(), intentFilter);
    }

    private void unregisterStateReceiver() {
        if (stateReceiver != null) {
            getContext().unregisterReceiver(stateReceiver);
            stateReceiver = null;
        }
    }

    /** Handles bluetooth scan responses and other indicators. */
    protected class BluetoothScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getContext() == null) {
                Log.w(TAG, "BluetoothScanReceiver context disappeared");
                return;
            }

            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            switch (action == null ? "" : action) {
                case BluetoothDevice.ACTION_FOUND:
                    if (hidHostProfile.isProfileSupported(device)) {
                        addAvailableDevice(device);
                    }
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    initiateScanDevices.setEnabled(false);
                    initiateScanDevices.setTitle(R.string.pref_bluetoothScan_scanning);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    initiateScanDevices.setEnabled(true);
                    initiateScanDevices.setTitle(R.string.pref_bluetoothScan);
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    updateAvailableDevice(device);
                    break;
                case BluetoothDevice.ACTION_NAME_CHANGED:
                    BluetoothDevicePreference pref = findDevicePreference(device);
                    if (pref != null) {
                        pref.updateName();
                    }
                    break;
                default: // fall out
            }
        }
    }

    /** Receiver to listen for changes in the bluetooth adapter state. */
    protected class BluetoothStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                checkBluetoothState();
            }
        }
    }

    private final HidDataSender.ProfileListener profileListener =
            new HidDataSender.ProfileListener() {
                @Override
                public void onServiceStateChanged(BluetoothProfile proxy) {}

                @Override
                public void onDeviceStateChanged(BluetoothDevice device, int state) {
                    final BluetoothDevicePreference pref = findOrAllocateDevicePreference(device);
                    pref.updateProfileConnectionState();
                }
            };

    /**
     * Looks for a preference in the preference group.
     *
     * <p>Returns null if no preference available.
     */
    private BluetoothDevicePreference findDevicePreference(final BluetoothDevice device) {
        return (BluetoothDevicePreference) findPreference(device.getAddress());
    }

    /**
     * Looks for a preference in the preference group.
     *
     * <p>Allocates a new preference if none found.
     */
    private BluetoothDevicePreference findOrAllocateDevicePreference(final BluetoothDevice device) {
        BluetoothDevicePreference pref = findDevicePreference(device);
        if (pref == null) {
            pref = new BluetoothDevicePreference(getContext(), device, hidHostProfile);
        }
        return pref;
    }
}
