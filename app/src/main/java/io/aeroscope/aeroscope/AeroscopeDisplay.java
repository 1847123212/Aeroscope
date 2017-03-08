package io.aeroscope.aeroscope;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.schedulers.Timestamped;

import static io.aeroscope.aeroscope.AeroscopeConstants.TRIGGER_CTRL;
import static io.aeroscope.aeroscope.AeroscopeConstants.TRIGGER_PT;
import static io.aeroscope.aeroscope.AeroscopeConstants.asDataCharId;

public class AeroscopeDisplay extends AppCompatActivity implements ServiceConnection {

    Intent bindAsBleIntent; // can pass extra data to the Service if we need to
    private final static String LOG_TAG = "AeroscopeDisplay       "; // tag for logging (23 chars max)
    private AeroscopeBluetoothService asBleServiceRef;     // reference to the BLE Service instance set by onServiceConnected()
    private RxBleClient asBleClientRef;                    // reference to the BLE Client set by onServiceConnected() ****** need?
    AtomicBoolean asBleBound = new AtomicBoolean(false);   // whether Bluetooth Service is bound (set true; false on disconnect) TODO: need?
    AeroscopeDevice selectedScope;
    Intent receivingIntent;  // made global
    TextView chosenDevice;
    TextView connectionState;
    TextView outputCharacteristic;
    TextView deviceBatteryStatus;
    TextView deviceDataFrames;
    Spinner aeroscopeTimeBaseSpinner;
    Spinner aeroscopeVerticalSensSpinner;
    Spinner aeroscopeCommandSelectSpinner;
    int selectedSensitivityIndex;
    int selectedTimebaseIndex; //index in spinners for restoring in onResume
    int selectedCommandIndex;
    Subscription connSub, heartSub, outNotifSub, dataNotifSub, frameNotifSub; // (dataNotifSub superseded by frameNotifSub)
    Observable<Long> heartbeat;
    RxBleConnection.RxBleConnectionState bleConnectionState;
    byte[] userSelectedCommand;
    int heartbeatTick; // note an int, not a long

    byte[] currentFpgaState;  // in-memory copy of the first 20 FPGA registers TODO: save this & restore on activity transitions?
    // Maybe no need if the scope itself remembers its configuration


    // LIFECYCLE METHODS
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aeroscope_display);

        chosenDevice = (TextView) findViewById(R.id.deviceName);
        connectionState = (TextView) findViewById(R.id.aeroscopeConnectionState);
        outputCharacteristic = (TextView) findViewById(R.id.outputChars);
        deviceBatteryStatus = (TextView) findViewById(R.id.batteryStatus);
        deviceDataFrames = (TextView) findViewById(R.id.dataFrames);
        aeroscopeTimeBaseSpinner = (Spinner) findViewById(R.id.timebaseSpinner);
        aeroscopeVerticalSensSpinner = (Spinner) findViewById(R.id.verticalSpinner);
        aeroscopeCommandSelectSpinner = (Spinner) findViewById(R.id.aeroscopeCommands);

        heartbeat = Observable
                .interval(AeroscopeConstants.HEARTBEAT_SECOND, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::doHeartbeat);

        // Initialize the FPGA register image to default values
        currentFpgaState = Arrays.copyOf( AeroscopeConstants.DEFAULT_FPGA_REGISTERS, 20 );

        //handles timeBase TODO: set the default setting (which will eventually probably be in Preferences)
        ArrayAdapter<CharSequence> timeBaseAdapter = ArrayAdapter.createFromResource(this, R.array.timePerDiv, android.R.layout.simple_spinner_item);
        timeBaseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeTimeBaseSpinner.setAdapter(timeBaseAdapter);
        aeroscopeTimeBaseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
//                Toast.makeText(getApplicationContext(), "You selected " + AeroscopeConstants.SAMPLER_CTRL_BYTE[i], Toast.LENGTH_LONG).show();
                writeFpgaRegister((byte) AeroscopeConstants.SAMPLER_CTRL, AeroscopeConstants.SAMPLER_CTRL_BYTE[i]);
                selectedTimebaseIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        //handles verticalSens TODO: set the default setting (which will eventually probably be in Preferences)
        ArrayAdapter<CharSequence> verticalSensAdapter = ArrayAdapter.createFromResource(this, R.array.voltsPerDiv, android.R.layout.simple_spinner_item);
        verticalSensAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeVerticalSensSpinner.setAdapter(verticalSensAdapter);
        aeroscopeVerticalSensSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
//                Toast.makeText(getApplicationContext(), "You selected " + AeroscopeConstants.FRONT_END_CTRL_BYTE[i], Toast.LENGTH_LONG).show();
                writeFpgaRegister((byte) AeroscopeConstants.FRONT_END_CTRL, AeroscopeConstants.FRONT_END_CTRL_BYTE[i]);
                selectedSensitivityIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        //handles aeroscopeCommands TODO: set the default setting (which will eventually probably be in Preferences)
        ArrayAdapter<CharSequence> commandsAdapter = ArrayAdapter.createFromResource(this, R.array.aeroscopeCommands, android.R.layout.simple_spinner_item);
        commandsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aeroscopeCommandSelectSpinner.setAdapter(commandsAdapter);
        aeroscopeCommandSelectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                userSelectedCommand = AeroscopeConstants.COMMAND_ARRAY[i];  // get the byte[20]
                sendCommand(userSelectedCommand);                           // send the command
//                String myString = new String(userSelectedCommand);          // convert to String for display (ignoring trailing NULLs(?))
//                Toast.makeText(getApplicationContext(), "You selected " + myString, Toast.LENGTH_LONG).show();
                selectedCommandIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        receivingIntent = getIntent();
        //TODO: SharedPreferences ?

    } // onCreate

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
                    connectionState.setText(connState.toString()); // update UI "connected" indicator
                    if( connState == RxBleConnection.RxBleConnectionState.CONNECTED ) setUpNotifications();
                });

        connSub = selectedScope.connectBle(); // returns a Subscription; subscriber does basically nothing but store the connection in bleconnection var
    }

    @Override
    protected void onPause() {
        super.onPause();
        heartSub.unsubscribe();
        //TODO: need to save current settings for vertical sensitivity, timebase, mode
    }

    @Override
    protected void onResume() {
        super.onResume();
        // connSub = selectedScope.connectBle(); // try this to avoid errors on resuming app MADE IT WORSE?
        heartSub = heartbeat.subscribe();

        //TODO: restore vertical sensitivity, timebase, mode
        aeroscopeTimeBaseSpinner.setSelection(selectedTimebaseIndex);
        aeroscopeVerticalSensSpinner.setSelection(selectedSensitivityIndex);
        aeroscopeCommandSelectSpinner.setSelection(selectedCommandIndex);
    }

    @Override
    protected void onStop() {
        super.onStop();
        //probably where we should disconnect the bluetooth
        connSub.unsubscribe(); // TODO: make sure this doesn't cause any problems
    }

    void doHeartbeat(Long interval) {

        heartbeatTick = interval.intValue();  // update
        sendCommand(AeroscopeConstants.REFRESH_TELEMETRY);   // get Telemetry (for battery voltage)

        readOutputChar();

        float actualBattVolts = 4.16568F * (selectedScope.getBatteryCondition() / 255F);
        String battMessage = "Battery: " + String.valueOf(actualBattVolts) + "V";
        deviceBatteryStatus.setText(battMessage);
        // TODO: anything else on the 6second heartbeat?
    }


    // UTILITY METHODS

    // Method to read the Output characteristic once
    void readOutputChar() {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection ->rxBleConnection.readCharacteristic(AeroscopeConstants.asOutputCharId))
                .timestamp() // NEW
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onOutputMessageReceived, this::onOutputError);
    }


    // Method to send a command to Aeroscope Input characteristic--must be 20 bytes
    void sendCommand( byte[] command ) {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(AeroscopeConstants.asInputCharId, command))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onWriteSuccess, this::onWriteError );
    }

    // Method to write a value to an FPGA register (via Input characteristic-- need to update in-memory copy, too
    // TODO: verify that the "SPI addr" and "SPI data" for the "W" command in the spreadsheet refer to FPGA registers
    void writeFpgaRegister( byte address, byte data ) {
        byte[] command = Arrays.copyOf( AeroscopeConstants.WRITE_FPGA_REG, 20); // "W" + 19 nulls
        command[1] = address;
        command[2] = data;
        sendCommand(command);
        currentFpgaState[(int) address] = data;
    }


    // Added 3/7/17: writing the State characteristic
    // Method to send a command to Aeroscope State characteristic--must be 20 bytes corresponding to first FPGA registers
    void sendState( final byte[] command ) {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(AeroscopeConstants.asStateCharId, command))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onWriteStateSuccess, this::onWriteStateError );
    }
    // Method to change a single byte in the in-memory State and send block to State input
    void sendChangedState( byte regAddress, byte regData ) {
        currentFpgaState[(int) regAddress] = regData;
        sendState( currentFpgaState );
    }
    // Method to set "raw" (0-255) trigger level
    void setRawTriggerLevel( byte rawLevel ) {
        sendChangedState( (byte) TRIGGER_PT, rawLevel );
    }
    // Methods to set trigger modes
    void enableAutoTrigger( boolean enabled ) {
        byte trigCtrl = currentFpgaState[TRIGGER_CTRL];
        if( enabled ) trigCtrl = (byte) (trigCtrl | (byte)0x01);  // turn on bit 0
        else trigCtrl = (byte) (trigCtrl & (byte)0xFE);           // turn off bit 0
        sendChangedState( (byte) TRIGGER_CTRL, trigCtrl );        // updates in-memory copy, too
    }
    void enableRisingTrigger( boolean enabled ) {
        byte trigCtrl = currentFpgaState[TRIGGER_CTRL];
        if( enabled ) trigCtrl = (byte) (trigCtrl | (byte)0x02);  // turn on bit 1
        else trigCtrl = (byte) (trigCtrl & (byte)0xFD);           // turn off bit 1
        sendChangedState( (byte) TRIGGER_CTRL, trigCtrl );
    }
    void enableFallingTrigger( boolean enabled ) {
        byte trigCtrl = currentFpgaState[TRIGGER_CTRL];
        if( enabled ) trigCtrl = (byte) (trigCtrl | (byte)0x04);  // turn on bit 2
        else trigCtrl = (byte) (trigCtrl & (byte)0xFB);           // turn off bit 2
        sendChangedState( (byte) TRIGGER_CTRL, trigCtrl );        // updates in-memory copy, too
    }



    // Method to set up Output (and Data) notifications (called when connection state becomes CONNECTED)
    // Called from Connection State Observer in onStart()
    void setUpNotifications() {
        // Output characteristic notifications: was reporting error, apparently because wasn't connected
        outNotifSub = selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(AeroscopeConstants.asOutputCharId))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .doOnError(setupThrowable -> runOnUiThread(this::notificationSetupError))  // NEW
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onOutputNotificationReceived, this::onOutputNotificationError);


        // This next block handles the probe's transmission of Data back to the application/user
        // This first version receives and delivers individual packets of a Frame
        // Commented out to try the version just below it
/*
        dataNotifSub = selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification( asDataCharId))
                .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                .doOnError(setupThrowable -> runOnUiThread(this::notificationSetupError))  // NEW
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()  // NEW
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDataNotificationReceived, this::onOutputNotificationError);
*/

        // This version assembles (it is hoped) Data packets into Frames TODO: test
        frameNotifSub = selectedScope.getConnectionObservable()  //
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread???)
                .doOnNext( rxBleConnection -> selectedScope.bleConnection = rxBleConnection ) // save connection back to the variable TODO: NEED???
                .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) )
                .doOnNext( notificationObservable -> { // Notification has been set up
                    Log.d( LOG_TAG, "Set up notification for Data frames from device: " );
                })
                .doOnError( throwable -> Log.d( LOG_TAG, "Error setting up Data frame notification: " + throwable.getMessage() ) ) // inserted
                .flatMap( notificationObservable -> notificationObservable ) // Unwrap the Observable into Data events (packets)
                .timestamp() // wrap each packet in a Timestamped object
                // not sure if casting null is of any use below (message says it's redundant)
                .scan( (ArrayList<Timestamped<byte[]>> ) null, ( ArrayList<Timestamped<byte[]>> packetBuffer, Timestamped<byte[]> packet )
                        -> DataOps.addPacket( packetBuffer, packet ) ) // scan returns Observable<packetBuffer> or null
                .filter( pBuf -> pBuf != null ) // emit only non-null (full) packet buffers
                .map( pBuf -> DataOps.pBuf2dFrame( pBuf ) ) // convert packet buffer to data frame
                .observeOn( AndroidSchedulers.mainThread() ) //  can this affect UI? TODO: experiment
                .subscribe( this::processFrame, this::handleFrameError );  // methods to call for finished frame, or error


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
        deviceDataFrames.append(HexString.bytesToHex(outputMessage.getValue()) +  " "); //append our probe message to our textView
    }

    void onOutputNotificationError(Throwable error) { // getting "Nofication setup error null"
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
        Snackbar.make(findViewById(R.id.main), "Message written to Aeroscope Input: " + HexString.bytesToHex(bytesWritten), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Message written to Aeroscope Input: " + HexString.bytesToHex(bytesWritten) );
    }

    void onWriteError( Throwable writeError ) {
        Snackbar.make(findViewById(R.id.main), "Write error to Aeroscope Input: " + writeError.getMessage(), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Write error in Input message to Aeroscope: " + writeError.getMessage() );
    }

    void onWriteStateSuccess( byte[] bytesWritten ) {
        Snackbar.make(findViewById(R.id.main), "Message written to Aeroscope State: " + HexString.bytesToHex(bytesWritten), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Message written to Aeroscope State: " + HexString.bytesToHex(bytesWritten) );
    }

    void onWriteStateError( Throwable writeError ) {
        Snackbar.make(findViewById(R.id.main), "Write State error: " + writeError.getMessage(), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "Write error in State message to Aeroscope: " + writeError.getMessage() );
    }


    void processFrame( DataOps.DataFrame eachFrame ) { // we think this runs on UI thread
        // display the frame (see the DataFrame class for methods
        // suggest displaying sequence number, no of bytes in frame before frame data
        deviceDataFrames.setText("Sequence Number: " + eachFrame.getSequenceNo() + " First time stamp: " + eachFrame.getFirstTimeStamp() + " Last Time Stamp: " + eachFrame.getLastTimeStamp() + "\n");

        deviceDataFrames.append(HexString.bytesToHex(eachFrame.getData()));
    }

    void handleFrameError( Throwable frameError ) { // we think this runs on UI thread
        Log.d( LOG_TAG, "Error in delivering completed frame");
    }


    // SERVICE CONNECTION

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
