package io.aeroscope.aeroscope;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.Timestamped;
import rx.subjects.BehaviorSubject;

import static io.aeroscope.aeroscope.AeroscopeConstants.DATA_RX_PRIORITY;
import static io.aeroscope.aeroscope.AeroscopeConstants.MAX_FRAME_SIZE;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_SIZE;
import static io.aeroscope.aeroscope.AeroscopeConstants.TRIGGER_CTRL;
import static io.aeroscope.aeroscope.AeroscopeConstants.TRIGGER_PT;
import static io.aeroscope.aeroscope.AeroscopeConstants.asDataCharId;

/*
* Subscriptions:
* connSub: selectedScope.connectBle(), which subscribes connSubscriber. MAYBE OK--it just logs, could probably eliminate
* heartSub: heartbeat.subscribe() -- nothing in AeroscopeDevice
* outNotifSub: selectedScope.getConnectionObservable... (CAUTION can also be with selectedScope.enableOutputNotification)
* dataNotifSub: superseded by frameNotifSub
* frameNotifSub: should be OK
* stateChangeSub: in onStart (commented out of AeroscopeDevice constructor)
* frameRelayerSub:
* */

public class AeroscopeDisplay extends AppCompatActivity implements ServiceConnection {

    private final static String LOG_TAG = "AeroscopeDisplay       "; // tag for logging (23 chars max)
    private AeroscopeBluetoothService asBleServiceRef;     // reference to the BLE Service instance set by onServiceConnected()
    private RxBleClient asBleClientRef;                    // reference to the BLE Client set by onServiceConnected() ****** need?
    AtomicBoolean asBleBound = new AtomicBoolean(false);   // whether Bluetooth Service is bound (set true; false on disconnect) TODO: need?
    AeroscopeDevice selectedScope;                         // set when user selects a device from scan results

    int heartbeatTick;                                     // "serial number" of heartbeat tick (note an int, not a long)
    byte[] currentFpgaState;  // in-memory copy of the first 20 FPGA registers TODO: save this & restore on activity transitions?
    // Maybe no need if the scope itself remembers its configuration


    // Android UI declarations
    Intent bindAsBleIntent; // can pass extra data to the Service if we need to
    Intent receivingIntent;  // made global
    TextView chosenDevice;
    TextView connectionState;
    TextView outputCharacteristic;
    TextView deviceBatteryStatus;
    TextView deviceDataFrames;
    Spinner aeroscopeTimeBaseSpinner;
    Spinner aeroscopeVerticalSensSpinner;
    Spinner aeroscopeCommandSelectSpinner;
    int selectedSensitivityIndex;    // array index of selected Vertical Sensitivity from Spinner
    int selectedTimebaseIndex;       // array index of selected Time Base from Spinner TODO: restore these in onResume(?)
    int selectedCommandIndex;        // array index of selected Command from Spinner
    byte[] userSelectedCommand;      // command string for user selection from command spinner

    // Observable BLE stuff
    Subscription connSub, heartSub, outNotifSub, dataNotifSub, frameNotifSub,
            stateChangeSub, frameRelayerSub; // (dataNotifSub superseded by frameNotifSub) TODO: which do we need?
    Observable<Long> heartbeat;
    RxBleConnection.RxBleConnectionState bleConnectionState;
    //FrameTestSubscriber frameTestSub;  // instance of FrameTestSubscriber (made in onCreate()) TODO: need?
    FrameTestSubscriber frameRelayerSubscriber;  // made in onCreate()
    BehaviorSubject<DataOps.DataFrame> frameRelayer;  // subscribe to this to get new Frames as they are made available (by calling its onNext())
    ArrayList<Timestamped<byte[]>> packetArrayList;   // where arriving packets are assembled into an ArrayList (not really)

    // Class for possibly debugging the Frame scheme -- onNext() calls processFrame()
    // Appears that at startup this receives a null frame: error is "tried to call toString() on null ref"
    // Oh, wait: creation of BehaviorSubject supplied a null initial value! Got rid of it.
    class FrameTestSubscriber extends TestSubscriber<DataOps.DataFrame> {
        @Override // when subscriber is activated but has not received anything
        public void onStart() {
            super.onStart( );
            Log.d( LOG_TAG, "FrameTestSubscriber onStart() invoked");
        }
        @Override
        public void onNext( DataOps.DataFrame frame ) {
            super.onNext( frame );
            //Log.d( LOG_TAG, "FrameTestSubscriber onNext() invoked with frame: " + frame.toString() );
            processFrame( frame );
        }
        @Override
        public void onError( Throwable e ) {
            super.onError( e );
            Log.d( LOG_TAG, "FrameTestSubscriber onError() invoked with error: " + e.getMessage());
            handleFrameError( e );  // TODO: processing of a Frame error (use special TestSubscriber method calls?)
        }
        @Override
        public void onCompleted() {
            super.onCompleted( );
            Log.d( LOG_TAG, "FrameTestSubscriber onCompleted() invoked");
        }
    }



    // LIFECYCLE METHODS
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aeroscope_display);
        chosenDevice = (TextView) findViewById(R.id.deviceName);
        connectionState = (TextView) findViewById(R.id.aeroscopeConnectionState);
        outputCharacteristic = (TextView) findViewById(R.id.outputChars);
        deviceBatteryStatus = (TextView) findViewById(R.id.batteryStatus);
        deviceDataFrames = (TextView) findViewById(R.id.dataFrames);  // name changed from Samples to Frames
        aeroscopeTimeBaseSpinner = (Spinner) findViewById(R.id.timebaseSpinner);
        aeroscopeVerticalSensSpinner = (Spinner) findViewById(R.id.verticalSpinner);
        aeroscopeCommandSelectSpinner = (Spinner) findViewById(R.id.aeroscopeCommands);

        deviceDataFrames.setText("Initial display - pre-frame");

        heartbeat = Observable  // subscribe to this (in onResume()) to start heartbeat; unsubscribe (in onPause()) to stop
                .interval(AeroscopeConstants.HEARTBEAT_SECOND, TimeUnit.SECONDS) // was 6 sec, changed to 15
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::doHeartbeat);

        // Initialize the FPGA register image to default values
        currentFpgaState = Arrays.copyOf( AeroscopeConstants.DEFAULT_FPGA_REGISTERS, 20 );
        // TODO: restore user preferences here? or maybe in onStart()?

        //frameTestSub = new FrameTestSubscriber( );  // may or may not use this
        frameRelayer = BehaviorSubject.create( /*(DataOps.DataFrame) null*/ );  // default value just caused trouble at startup
        frameRelayerSubscriber = new FrameTestSubscriber();  // its onNext() calls processFrame()

        packetArrayList = new ArrayList<>( MAX_FRAME_SIZE / PACKET_SIZE + 1 );  // initially empty, with enough room for largest frame

        // LISTENERS
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
                selectedCommandIndex = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        receivingIntent = getIntent();  // TODO: need this?
        //TODO: SharedPreferences ?

    } // onCreate

    @Override
    protected void onStart() {

        super.onStart();

        int defaultValue = -1;  // initialize to illegal index (must supply a defaultValue in next line)
        int positionValue = receivingIntent.getIntExtra("selectedScopeIndex", defaultValue);

        selectedScope = asBleServiceRef.asDeviceVector.elementAt(positionValue); //Our reference to the user-selected scope
        chosenDevice.setText(selectedScope.bleDevice.getName());  // name of chosen device to UI TextView

        // This was under stateChangeSub; moved it here
        connSub = selectedScope.connectBle(); // returns a Subscription; subscriber does basically nothing but store the connection in bleconnection var

        stateChangeSub = selectedScope.bleDevice.observeConnectionStateChanges()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connState -> {
                    bleConnectionState = connState; // new
                    connectionState.setText(connState.toString()); // update UI "connected" indicator
                    if( connState == RxBleConnection.RxBleConnectionState.CONNECTED ) setUpNotifications(); // TODO: right?
                });

    }

    @Override
    protected void onPause() {
        super.onPause();
        heartSub.unsubscribe();
        frameRelayerSub.unsubscribe();  // stop sending frames TODO: stop notifications
        //TODO: need to save current settings for vertical sensitivity, timebase, mode
    }

    @Override
    protected void onResume() {
        super.onResume();
        // connSub = selectedScope.connectBle(); // try this to avoid errors on resuming app MADE IT WORSE?
        // TODO: reestablish notifications etc.? Make sure connected?
        heartSub = heartbeat.subscribe();
        frameRelayerSub = frameRelayer.subscribe( frameRelayerSubscriber ); // resume frame processing

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

    // HEARTBEAT (called by the heartbeat Observable's doOnNext())
    void doHeartbeat(Long interval) {
        heartbeatTick = interval.intValue();  // update the sequential value just received
        sendCommand(AeroscopeConstants.REFRESH_TELEMETRY);   // get Telemetry (for battery voltage)
        //readOutputCharacteristic(); // NEW let notifications handle it
        float actualBattVolts = 4.16568F * (selectedScope.getBatteryCondition() / 255F);
        String battMessage = "Battery: " + String.valueOf(actualBattVolts) + "V";
        deviceBatteryStatus.setText(battMessage);
        // TODO: anything else on the 6 (or 15) second heartbeat?
    }


    // UTILITY METHODS

    // Method to read the Output characteristic once (seems to have been working OK; presume 1-item Observable)
    void readOutputCharacteristic() {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection ->rxBleConnection.readCharacteristic(AeroscopeConstants.asOutputCharId))
                .timestamp()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( response -> onOutputMessageReceived( response, false ),
                        this::onOutputError ); // NEW added boolean "isNotification" param to onOutputMessageReceived()
    }

    // Method to send a command to Aeroscope Input characteristic--must be 20 bytes (seems to work)
    void sendCommand( byte[] command ) {
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(AeroscopeConstants.asInputCharId, command))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onWriteInputSuccess, this::onWriteInputError );  // just log the results
    }

    // Method to write a value to an FPGA register (via Input characteristic-- need to update in-memory copy, too
    // TODO: verify that the "SPI addr" and "SPI data" for the "W" command in the spreadsheet refer to FPGA registers
    void writeFpgaRegister( byte address, byte data ) { // must be 20 bytes
        byte[] command = Arrays.copyOf( AeroscopeConstants.WRITE_FPGA_REG, 20); // "W" + 19 nulls
        command[1] = address;
        command[2] = data;
        sendCommand(command);
        currentFpgaState[(int) address] = data;  // update in-memory copy
    }

    // Method to block-write the Aeroscope State characteristic--must be 20 bytes corresponding to first FPGA registers
    // DOES NOT update in-memory copy!
    void sendState( final byte[] command ) {  // could use "final" in lots more places
        selectedScope.getConnectionObservable()
                .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(AeroscopeConstants.asStateCharId, command))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( this::onWriteStateSuccess, this::onWriteStateError );  // just log result
    }

    // Method to change a single byte in the in-memory State and send block to State input
    void sendChangedState( final byte regAddress, final byte regData ) {
        currentFpgaState[(int) regAddress] = regData;
        sendState( currentFpgaState );
    }

    // METHODS TO SET HARDWARE SETTINGS
    // Note since constants are array indexes, they must be cast to byte to specify a register address
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
                //.doOnNext(notificationObservable -> runOnUiThread(this::outputNotificationHasBeenSetUp))
                //.doOnError(setupThrowable -> runOnUiThread(this::outputNotificationSetupError))  // NEW -- logged
                // suspect we don't need the runOnUiThread; also, confusion about parameter
                // maybe if we want to Toast, use runOnUiThread in these handlers:
                .doOnNext( this::outputNotificationHasBeenSetUp )
                .doOnError( this::outputNotificationSetupError )  // error: setupNotification on a null ref (maybe not connected?)
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnUnsubscribe( () -> Log.d( LOG_TAG, "Output Notification was unsubscribed") )
                .subscribe(this::onOutputNotificationReceived, this::onOutputNotificationError);


        // This next block handles the probe's transmission of Data back to the application/user
        // This version receives and delivers individual packets of a Frame
        // TODO: experiment with threading to hopefully speed up
        dataNotifSub = selectedScope.getConnectionObservable()
                .subscribeOn( Schedulers.computation() )  // NEW try this to maybe dedicate 1 of the 4 cores to packet/frame handling
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) )
                .doOnNext(notificationObservable -> runOnUiThread(this::dataNotificationHasBeenSetUp))
                .doOnError(setupThrowable -> runOnUiThread(this::dataNotificationSetupError))
                .flatMap(notificationObservable -> notificationObservable)
                .timestamp()
                //.observeOn(AndroidSchedulers.mainThread())  // NEW try removing this (but beware of UI action downstream!)
                .doOnUnsubscribe( () -> Log.d(LOG_TAG, "doOnUnsubscribe() invoked by dataNotifSub") )
                .subscribe(this::onDataNotificationReceived, this::onDataNotificationError); // these should execute on selected thread
    }


    void outputNotificationHasBeenSetUp( Observable<byte[]> notifObs ) { // not running on UI thread any more
        Log.d( LOG_TAG, "outputNotificationHasBeenSetUp has finished for Output; result: " + notifObs.toString() );
    }

    void outputNotificationSetupError( Throwable setupError ) { // not running on UI thread any more
        Log.d(LOG_TAG, "Error setting up Output notifications: " + setupError.getMessage() ); // "attempt to setupNotification on a null ref"
        throw new RuntimeException( "Output notification setup error: ",  setupError );  // TODO: for now
    }

    void onOutputNotificationReceived(Timestamped<byte[]> outputMessage) {  // NEW Timestamped
        //Snackbar.make(findViewById(R.id.main), "Change: " + HexString.bytesToHex(outputMessage.getValue()), Snackbar.LENGTH_SHORT).show();
        Log.d(LOG_TAG, "onOutputNotificationReceived() about to call onOutputMessageReceived" );
        onOutputMessageReceived(outputMessage, true);  // handles both notifications and direct reads of Output
    }

    void onOutputNotificationError(Throwable error) { // "2 exceptions occurred"
        Log.d(LOG_TAG, "onOutputNotificationError: " + error.getMessage() );
    }


    //*****************************************
    // When Data packets are received w/o Frame assembly scheme
    // ALERT: this is no longer run on main thread; we're trying a computation Scheduler NEW
    void onDataNotificationReceived(Timestamped<byte[]> dataPacket) {  //
        //Log.d(LOG_TAG, "onDataNotificationReceived() invoked, appending to DataFrames window" ); // message seems OK
        //deviceDataFrames.append(HexString.bytesToHex(dataPacket.getValue()) +  " "); //append our probe message to our textView
        // empty packetArrayList is created in onCreate()
        Thread.currentThread().setPriority( DATA_RX_PRIORITY );  // NEW let's try 8 (of 10 max)
        packetArrayList = DataOps.addPacket( packetArrayList, dataPacket ); // returns null or a complete packet buffer
        if( packetArrayList != null ) {  // we have a new complete packet buffer
            //Log.d(LOG_TAG, "packet buffer #" + DataOps.getBuffersReturned() + " filled" ); // message looks OK
            DataOps.DataFrame frameReturned = DataOps.pBuf2dFrame( packetArrayList );
            frameRelayer.onNext( frameReturned );  // send the new frame to the BehaviorSubject NEW: still on computation thread, right?
            // TODO: maybe append to Data TextView (but note that frameRelayerSubscriber onNext() calls processFrame())
        }
    }
    // ALERT: this is no longer run on main thread; we're trying a computation Scheduler NEW
    void onDataNotificationError(Throwable error) {
        Log.d(LOG_TAG, "onDataNotificationError: " + error.getMessage() );
    }
    void dataNotificationHasBeenSetUp( ) { //
        Log.d( LOG_TAG, "dataNotificationHasBeenSetUp has finished for Data" );  // logged OK
    }
    void dataNotificationSetupError( ) { //
        Log.d(LOG_TAG, "Error setting up Data notifications" );
        throw new RuntimeException( "Data notification setup error");  // TODO: for now
    }
    //*****************************************



    // Boolean specifies whether message is an unsolicited Notification or a Response to a read command
    // called by both readOutputCharacteristic() subscribe and onOutputNotificationReceived() handler
    void onOutputMessageReceived( Timestamped<byte[]> message, boolean isNotification ) {  // NEW: boolean arg
        String msgType = isNotification? "Notification" : "Response    ";
        String fullMsg = message.getTimestampMillis() + " Output " + msgType + ": " + HexString.bytesToHex(message.getValue());
        outputCharacteristic.setText( fullMsg );  // post to UI TextView
        Log.d(LOG_TAG, fullMsg );
        selectedScope.handleOutputFromAeroscope(message); // seems to work
        Log.d(LOG_TAG, "onOutputMessageReceived() returned from handleOutputFromAeroscope" );
    }

    void onOutputError( Throwable error ) { // called by readOutputCharacteristic() subscribe
        Log.d(LOG_TAG, "Error in Output message from Aeroscope: " + error.getMessage() );
    }

    void onWriteInputSuccess( byte[] bytesWritten ) { // handler for write Input characteristic success
        Log.d(LOG_TAG, "Message written to Aeroscope Input: " + HexString.bytesToHex(bytesWritten) );
    }

    void onWriteInputError( Throwable writeError ) { // handler for write Input characteristic error
        Log.d(LOG_TAG, "Write error in Input message to Aeroscope: " + writeError.getMessage() );
    }

    void onWriteStateSuccess( byte[] bytesWritten ) {
        Log.d(LOG_TAG, "Message written to Aeroscope State: " + HexString.bytesToHex(bytesWritten) );
    }

    void onWriteStateError( Throwable writeError ) {
        Log.d(LOG_TAG, "Write error in State message to Aeroscope: " + writeError.getMessage() );
    }


    // NEW: think we are still running on the computation thread used for notification of new packet arrival
    // We might want to use another computation thread to turn frame data into a display (and display on UI thread)
    void processFrame( DataOps.DataFrame eachFrame ) { // we think this runs on UI thread NOT ANY MORE!
        // display the frame (see the DataFrame class for methods
        // suggest displaying sequence number, no of bytes in frame before frame data
        runOnUiThread(() -> {
            String displayText = "Seq no: " + eachFrame.getSequenceNo();
            deviceDataFrames.setText( displayText );
            //deviceDataFrames.setText("Seq No: " + eachFrame.getSequenceNo() /*+ " First time stamp: "
            //        + eachFrame.getFirstTimeStamp() + " Last Time Stamp: " + eachFrame.getLastTimeStamp() + "\n" */ );
            //deviceDataFrames.append(HexString.bytesToHex(eachFrame.getData()));
        });
        // Below works but remove for production, eh?
        Log.d( LOG_TAG, "Frame Seq No: " + eachFrame.getSequenceNo() + "  First T: " + eachFrame.getFirstTimeStamp()
                + "  Last T: " + eachFrame.getLastTimeStamp() + "  Expected Length: " + eachFrame.getExpectedLength()
                + "  Actual Length: " + eachFrame.getActualLength() + "  Header: " + eachFrame.getHeader() );

    }

    void handleFrameError( Throwable frameError ) { // we think this runs on UI thread
        Log.d( LOG_TAG, "Error in delivering completed frame: " + frameError.getMessage() ); // getMessage NEW
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
