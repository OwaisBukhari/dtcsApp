package com.example.dtcapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.pires.obd.commands.control.TroubleCodesCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.ObdRawCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.enums.ObdProtocols;
import com.github.pires.obd.exceptions.NoDataException;
import com.github.pires.obd.exceptions.UnableToConnectException;
import com.github.pires.obd.exceptions.UnsupportedCommandException;
import com.github.pires.obd.exceptions.UnknownErrorException;
//import com.github.pires.obd.exceptions.UnsupportedObdCommandException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int PERMISSION_REQUEST_LOCATION = 2;
    private static final String LOG_TAG = "DTCApp";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bluetoothDevice;
    private BluetoothSocket bluetoothSocket;

    private Button connectButton;
    private TextView dtcTextView;

    private Handler handler;

    private File logFile;
    private PrintWriter logWriter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectButton = findViewById(R.id.connect_button);
        dtcTextView = findViewById(R.id.dtc_text_view);

        handler = new Handler(Looper.getMainLooper());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showToast("Bluetooth is not supported on this device");
            finish();
        }

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!bluetoothAdapter.isEnabled()) {
                    Intent enableBluetoothIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBluetoothIntent, REQUEST_ENABLE_BT);
                } else {
                    checkLocationPermission();
                }
            }
        });

        setupLogFile();
    }

    private void setupLogFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String logFileName = "dtc_log_" + timestamp + ".txt";
        File logDir = new File(Environment.getExternalStorageDirectory().getPath() + "/DTCAppLogs");

        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                Log.e(LOG_TAG, "Failed to create log directory");
                return;
            }
        }

        logFile = new File(logDir, logFileName);
        try {
            logWriter = new PrintWriter(new FileWriter(logFile));
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to create log file: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logWriter != null) {
            logWriter.close();
        }
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_LOCATION);
        } else {
            connectToOBD();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectToOBD();
            } else {
                showToast("Location permission is required to connect");
            }
        }
    }

    private void connectToOBD() {
        showLoading(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    bluetoothDevice = findOBDDevice();
                    if (bluetoothDevice != null) {
                        connectToDevice();
                        try {
                            fetchDTC();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        showToast("No OBD-II device found");
                    }
                } catch (IOException | UnableToConnectException | UnsupportedCommandException |
                         NoDataException |
                         UnknownErrorException e) {
                    showToast("Failed to connect: " + e.getMessage());
                    logError(e);
                } finally {
                    showLoading(false);
                }
            }
        }).start();
    }

    private BluetoothDevice findOBDDevice() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return null;
        }
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (device.getName().startsWith("OBD")) {
                return device;
            }
        }
        return null;
    }

    private void connectToDevice() throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid);
        bluetoothSocket.connect();
    }

    private void fetchDTC() throws IOException, UnableToConnectException, UnsupportedCommandException,
            NoDataException, UnknownErrorException, InterruptedException {
        new EchoOffCommand().run(bluetoothSocket.getInputStream(), bluetoothSocket.getOutputStream());
        new LineFeedOffCommand().run(bluetoothSocket.getInputStream(), bluetoothSocket.getOutputStream());
        new TimeoutCommand(125).run(bluetoothSocket.getInputStream(), bluetoothSocket.getOutputStream());
        new SelectProtocolCommand(ObdProtocols.AUTO).run(bluetoothSocket.getInputStream(),
                bluetoothSocket.getOutputStream());

        TroubleCodesCommand troubleCodesCommand = new TroubleCodesCommand();
        troubleCodesCommand.run(bluetoothSocket.getInputStream(), bluetoothSocket.getOutputStream());
        final String dtcCodes = troubleCodesCommand.getFormattedResult();

        handler.post(new Runnable() {
            @Override
            public void run() {
                dtcTextView.setText(dtcCodes);
                Toast.makeText(MainActivity.this, "DTC Codes: " + dtcCodes, Toast.LENGTH_SHORT).show();
            }
        });
        logMessage("DTC Codes: " + dtcCodes);
    }

    private void showLoading(final boolean show) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                connectButton.setEnabled(!show);
                if (show) {
                    dtcTextView.setText("");
                    dtcTextView.setVisibility(View.INVISIBLE);
                    // Show a progress indicator if desired
                } else {
                    dtcTextView.setVisibility(View.VISIBLE);
                    // Hide the progress indicator if shown
                }
            }
        });
    }

    private void showToast(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        logMessage("Toast: " + message);
    }

    private void logMessage(String message) {
        Log.d(LOG_TAG, message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private void logError(Exception e) {
        Log.e(LOG_TAG, "Error: " + e.getMessage());
        if (logWriter != null) {
            e.printStackTrace(logWriter);
            logWriter.flush();
        }
    }
}
