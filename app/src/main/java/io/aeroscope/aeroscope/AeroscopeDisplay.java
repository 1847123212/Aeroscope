package io.aeroscope.aeroscope;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.schedulers.Timestamped;

public class AeroscopeDisplay extends AppCompatActivity implements ServiceConnection {


    Intent bindAsBleIntent; // can pass extra data to the Service if we need to
    private final static String LOG_TAG = "AeroscopeDisplay       "; // tag for logging (23 chars max)
    private AeroscopeBluetoothService asBleServiceRef;    // reference to the BLE Service instance set by onServiceConnected()
    private RxBleClient asBleClientRef;                   // reference to the BLE Client set by onServiceConnected() ****** need?
    AtomicBoolean asBleBound = new AtomicBoolean(false);   // whether Bluetooth Service is bound (set true; false on disconnect)
    AeroscopeDevice selectedScope;
    Intent receivingIntent;  // made global
    TextView chosenDevice;
    TextView connectionState;
    TextView outputCharacteristic;
    TextView deviceBatteryStatus;
    TextView deviceDataSamples;
    Spinner aeroscopeTimeBaseSpinner;
    Spinner aeroscopeVerticalSensSpinner;
    Spinner aeroscopeCommandSelectSpinner;
    int selectedSensitivityIndex;
    int selectedTimebaseIndex; //index in spinners for restoring in onResume
    int selectedCommandIndex;
    Subscription connSub, heartSub;
    Observable<Long> heartbeat;
    RxBleConnection.RxBleConnectionState bleConnectionState;
    byte[] userSelectedCommand;
    int heartbeatTick; // note an int, not a long


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aeroscope_display);

        chosenDevice = (TextView) findViewById(R.id.deviceName);
        connectionState = (TextView) findViewById(R.id.aeroscopeConnectionState);
        outputCharacteristic = (TextView) findViewById(R.id.outputChars);
        deviceBatteryStatus = (TextView) findViewById(R.id.batteryStatus);
        deviceDataSamples = (TextView) findViewById(R.id.dataSamples);
        aeroscopeTimeBaseSpinner = (Spinner) findViewById(R.id.timebaseSpinner);
        aeroscopeVerticalSensSpinner = (Spinner) findViewById(R.id.verticalSpinner);
        aeroscopeCommandSelectSpinner = (Spinner) findViewById(R.id.aeroscopeCommands);

        heartbeat = Observable
                .interval(AeroscopeConstants.HEARTBEAT_SECOND, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::doHeartbeat);

        //handles timeBase
        ArrayAdapter<CharSequence> timeBaseAdapter = ArrayAdapter.createFromResource(this, R.array.timePerDiv, android.R.layout.simple_spinner_item);
        timeBaseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeTimeBaseSpinner.setAdapter(timeBaseAdapter);
        aeroscopeTimeBaseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                Toast.makeText(getApplicationContext(), "You selected " + AeroscopeConstants.SAMPLER_CTRL_BYTE[i], Toast.LENGTH_LONG).show();
                writeFpgaRegister((byte) AeroscopeConstants.SAMPLER_CTRL, AeroscopeConstants.SAMPLER_CTRL_BYTE[i]);
                selectedTimebaseIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        //handles verticalSens
        ArrayAdapter<CharSequence> verticalSensAdapter = ArrayAdapter.createFromResource(this, R.array.voltsPerDiv, android.R.layout.simple_spinner_item);
        verticalSensAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeVerticalSensSpinner.setAdapter(verticalSensAdapter);
        aeroscopeVerticalSensSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                Toast.makeText(getApplicationContext(), "You selected " + AeroscopeConstants.FRONT_END_CTRL_BYTE[i], Toast.LENGTH_LONG).show();
                writeFpgaRegister((byte) AeroscopeConstants.FRONT_END_CTRL, AeroscopeConstants.FRONT_END_CTRL_BYTE[i]);
                selectedSensitivityIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        //handles aeroscopeCommands
        ArrayAdapter<CharSequence> commandsAdapter = ArrayAdapter.createFromResource(this, R.array.aeroscopeCommands, android.R.layout.simple_spinner_item);
        commandsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeCommandSelectSpinner.setAdapter(commandsAdapter);
        aeroscopeCommandSelectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                userSelectedCommand = AeroscopeConstants.COMMAND_ARRAY[i];  // get the byte[20]
                sendCommand(userSelectedCommand);                           // send the command
                String myString = new String(userSelectedCommand);          // convert to String for display (ignoring trailing NULLs(?))
                Toast.makeText(getApplicationContext(), "You selected " + myString, Toast.LENGTH_LONG).show();
                selectedCommandIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        receivingIntent = getIntent();
    }

    @Override
    protected void onStart() {


        super.onStart();

        int defaultValue = -1;
        int positionValue = receivingIntent.getIntExtra("selectedScopeIndex", defaultValue);

        selectedScope = asBleServiceRef.asDeviceVector.elementAt(positionValue); //Our reference to the user-selected scope

        chosenDevice.setText(selectedScope.bleDevice.getName());

        selectedScope.bleDevice.observeConnectionStateChanges()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connState -> {
                    bleConnectionState = connState; // new
                    connectionState.setText(connState.toString());
                    if( connState == RxBleConnection.RxBleConnectionState.CONNECTED ) setUpNotifications();
                });

        connSub = selectedScope.connectBle(); // returns a Subscription; subscriber does basically nothing but store the connection in bleconnection var

    }

    @Override
    protected void onPause() {
        super.onPause();
        heartSub.unsubscribe();
        //TODO: need to save current settings for vertical sensitivity, timebase
    }

    @Override
    protected void onResume() {
        super.onResume();
        // connSub = selectedScope.connectBle(); // try this to avoid errors on resuming app MADE IT WORSE?
        heartSub = heartbeat.subscribe();

        //TODO: restore vertical sensitivity, timebase
        aeroscopeTimeBaseSpinner.setSelection(selectedTimebaseIndex);
        aeroscopeVerticalSensSpinner.setSelection(selectedSensitivityIndex);
        aeroscopeCommandSelectSpinner.setSelection(selectedCommandIndex);
    }

    @Override
    protected void onStop() {
        super.onStop();
        //probably where we should disconnect the bluetooth
    }

    void doHeartbeat(Long interval) {

        heartbeatTick = interval.intValue();  // update
        //sendCommand(userSelectedCommand);   // don''t keep re-sending user command
        sendCommand(AeroscopeConstants.REFRESH_TELEMETRY);   // get Telemetry (for battery voltage)

        readOutputChar();

        float actualBattVolts = 4.16568F * (selectedScope.getBatteryCondition() / 255F);
        String battMessage = "Battery: " + String.valueOf(actualBattVolts) + "V";
        deviceBatteryStatus.setText(battMessage);
    }


    // Method to read the Output characteristic once
    void readOutputChar() {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection ->rxBleConnection.readCharacteristic(AeroscopeConstants.asOutputCharId))
                .timestamp() // NEW
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onOutputMessageReceived, this::onOutputError);
    }


    // Method to send a command to Aeroscope Input characteristic
    void sendCommand( byte[] command ) {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(AeroscopeConstants.asInputCharId, command))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onWriteSuccess, this::onWriteError );
    }

    // Method to write a value to an FPGA register
    void writeFpgaRegister( byte address, byte data ) {
        byte[] command = Arrays.copyOf( AeroscopeConstants.WRITE_FPGA_REG, 20);
        command[1] = address;
        command[2] = data;
        sendCommand(command);
    }



    // Method to set up Output (and Data) notifications (called when connection state becomes CONNECTED)
    void setUpNotifications() {  // NEW
        // reports error, I think because it's not connected
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(AeroscopeConstants.asOutputCharId))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .doOnError(setupThrowable -> runOnUiThread(this::notificationSetupError))  // NEW
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()  // NEW
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onOutputNotificationReceived, this::onNotificationSetupFailure);


        //This next block handles the probe's transmission of data back to the application/user
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(AeroscopeConstants.asDataCharId))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .doOnError(setupThrowable -> runOnUiThread(this::notificationSetupError))  // NEW
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()  // NEW
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDataNotificationReceived, this::onNotificationSetupFailure);

    }

    void notificationHasBeenSetUp() {
        Toast.makeText(getApplicationContext(), "Established Output Notifications", Toast.LENGTH_LONG).show();
        Log.d(LOG_TAG, "notificationHasBeenSetUp has finished.");
    }

    void notificationSetupError( ) {
        Toast.makeText(getApplicationContext(), "Error establishing Output Notifications", Toast.LENGTH_LONG).show();
        Log.d(LOG_TAG, "Error establishing Output Notifications" );
    }

    void onOutputNotificationReceived(Timestamped<byte[]> outputMessage) {  // NEW Timestamped
        //Snackbar.make(findViewById(R.id.main), "Change: " + HexString.bytesToHex(outputMessage.getValue()), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "onOutputNotificationReceived() about to call onOutputMessageReceived" );
        onOutputMessageReceived(outputMessage);
    }

    void onDataNotificationReceived(Timestamped<byte[]> outputMessage) {  // NEW Timestamped
        //Snackbar.make(findViewById(R.id.main), "Change: " + HexString.bytesToHex(outputMessage.getValue()), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "onOutputNotificationReceived() about to call onOutputMessageReceived" );
        deviceDataSamples.append(HexString.bytesToHex(outputMessage.getValue()) +  " "); //append our probe message to our textView
    }

    void onNotificationSetupFailure(Throwable error) { // getting "Nofication setup error null"
        Snackbar.make(findViewById(R.id.main), "Notification setup error " + error.getMessage(), Snackbar.LENGTH_SHORT).show();
    }

    void onOutputMessageReceived(Timestamped<byte[]> message ) {  // NEW: timestamped
        //Snackbar.make(findViewById(R.id.main), "Message: " + HexString.bytesToHex(message), Snackbar.LENGTH_SHORT).show();
        outputCharacteristic.setText(message.getTimestampMillis() + ": " + HexString.bytesToHex(message.getValue()));
        Log.d(LOG_TAG, "Output message from Aeroscope: " + HexString.bytesToHex(message.getValue()) );
        selectedScope.handleOutputFromAeroscope(message); // NEW: try this
        Log.d(LOG_TAG, "onOutputMessageReceived() returned from handleOutputFromAeroscope" );
    }

    void onOutputError( Throwable error ) {
        Snackbar.make(findViewById(R.id.main), "Error: " + error.getMessage(), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Error in Output message from Aeroscope: " + error.getMessage() );
    }

    void onWriteSuccess( byte[] bytesWritten ) {
        Snackbar.make(findViewById(R.id.main), "Message written to Aeroscope: " + HexString.bytesToHex(bytesWritten), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Message written to Aeroscope: " + HexString.bytesToHex(bytesWritten) );
    }

    void onWriteError( Throwable writeError ) {
        Snackbar.make(findViewById(R.id.main), "Write error: " + writeError.getMessage(), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Write error in Input message to Aeroscope: " + writeError.getMessage() );
    }



    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Log.d( LOG_TAG, "Entering onServiceConnected()" ); // [0090]
        // We've bound to Service, cast the IBinder and get Service instance
        AeroscopeBluetoothService.LocalBinder asBleBinder = (AeroscopeBluetoothService.LocalBinder) service;
        // (casting it makes compiler aware of existence of getService() method)
        asBleServiceRef = asBleBinder.getService(); // returns the instance of the Service
        if( asBleServiceRef == null ) throw new RuntimeException( LOG_TAG
                + ": onServiceConnected returned null from getService()" );

        asBleClientRef = asBleServiceRef.getRxBleClient(); // static member accessed by instance reference--OK!
        if( asBleClientRef == null ) throw new RuntimeException( LOG_TAG
                + ": onServiceConnected returned null from getRxBleClient()" );

        asBleBound.set( true ) ; // success: set "bound" flag
        Log.d( LOG_TAG, "Finished onServiceConnected()" ); // reached here OK [0110]
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {

    }
}
