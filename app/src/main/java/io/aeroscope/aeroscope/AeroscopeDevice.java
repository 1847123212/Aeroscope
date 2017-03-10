package io.aeroscope.aeroscope;

import android.util.Log;

import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.internal.RxBleLog;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.Timestamped;
import rx.subscriptions.CompositeSubscription;

import static io.aeroscope.aeroscope.AeroscopeConstants.*;


/**
 * Created on 2017-02-03.
 */

/*
Dictionary
    Constants
        LOG_TAG ("AeroscopeDevice        ")
    References
        asBleServiceRef (to AeroscopeBluetoothService)
        
    Methods
        void initializeFrame()           resets packet timestamps, sets incoming = false
        String toString()                combines Name and MAC Address (primarily for Scan result display)
        static void setServiceRef()      called by AeroscopeBluetoothService to pass a reference to its instance (asBleServiceRef)
        Subscription connectBle()        connects to device with connSubscriber
        RxBleConnection.RxBleConnectionState getConnectionState() returns current Connection State
        boolean isConnected()            checks device's connection state, returns true only if CONNECTED
        Observable<RxBleConnection> getConnectionObservable() returns Observable of existing connection, if any, or makes a new one
        Observable<RxBleConnection> bleConnectObs (in Dev) uses .share() to wait for Subscriber(s), and unsubscribe when last one unsubscribes
        Observable<DataFrame> frameObservable (in Dev--probably be abandoned) attempts to convert frame Queue into Observable
        Observable<DataFrame> packets2Frames (in Dev--probably best approach) transforms packet Observable into frame Observable
        boolean writeCommand( byte[] commandBytes ) true if able to start a Write to Aeroscope Input Characteristic, else false
        boolean writeFpgaRegister( int address, int data ) calls writeCommand to write a byte into an FPGA register
        INPUT OPERATIONS TO AEROSCOPE
        void writeCommand(byte[])        queues a message to the Aeroscope's Input Characteristic, returns inputSubscription
        void writeFpgaRegister(int address, int data) calls writeCommand() to set a single register in FPGA
        void writeState(byte[])          queues a 20-byte write to the State Characteristic (FPGA register block)
        OUTPUT OPERATIONS FROM AEROSCOPE
        void subscribeToOutput()         sets up subscriber that adds received messages to outputQueue. Do we need?
        void enableOutputNotification()  subscribes to Output notifications, timestamps packet, adds to outNotifQueue
        SAMPLE DATA OPERATIONS FROM AEROSCOPE
        void enableDataNotification()    subscribes to Data notifications via dataSubscriber, which calls handleDataPacket (MAY CHANGE)
        void disableDataNotification()   unsubscribes
        boolean dataNotificationIsEnabled() yes or no
        Observable<DataFrame> getFrameObservable() NEW: returns an Observable that emits DataFrames from the scope
        HELPER METHODS ETC.
        void initializeFrame()           clears running timestamps, sets incoming = false
        void handleDataPacket(Timestamped<byte[]> called by onNext() notifications from Data subscriber
        void processOutput()             handle Output Characteristic message queue from Aeroscope
        void processFrame()              handle display of a frame of data (Jack)
        void run()                       Main Loop (? not positive we need one)
        int getBatteryCondition()        convert battery condition to 1 of 4 "pseudo-enum" conditions (Jack)
        
        
        
       
        void enableDataNotification()       Sets up notifications for Data packets; handleDataPacket() called for each

*/

public class AeroscopeDevice /* implements Channel */ implements Serializable {

    private final static String LOG_TAG = "AeroscopeDevice        "; // tag for logging (23 chars max)

    private static AeroscopeBluetoothService asBleServiceRef; // ref to the Service class instance, passed by constructor (TODO: or not)

    // instance variables
    RxBleDevice bleDevice;                          // the Bluetooth hardware
    volatile RxBleConnection bleConnection;         // when this becomes non-null, we have connection (?)
    private volatile RxBleConnection.RxBleConnectionState connectionState; // or when this becomes CONNECTED (better?) (updated by Conn State Subs)

    // Subscription variables for connection, connection state, data, I/O, state
    private AsConnSubscriber connSubscriber;        // connection Subscriber for BLE connection to Aeroscope
    private Subscription connSubscription;                  // connection Subscription

    private AsStateChangeSubscriber stateChangeSubscriber;   // connection state change Subscriber to monitor state of BLE connection
    private Subscription stateChangeSubscription; // connection state change Subscription

    private AsDataSubscriber dataSubscriber;        // Data Characteristic Subscriber for frame data (read/notify)
    private Subscription dataSubscription;          // Data Characteristic Subscription

    private AsOutputSubscriber outputSubscriber;    // Output Characteristic Subscriber for responses to commands, etc. (read/notify)
    private Subscription outputSubscription;        // Output Characteristic Subscription

    private AsOutNotificationSubscriber outNotifSubscriber;
    private Subscription outNotifSubscription;

    private AsInputSubscriber inputSubscriber;      // Input Characteristic Subscriber to send commands (read/write)
    private Subscription inputSubscription;         // Input Characteristic Subscription

    private AsStateSubscriber stateSubscriber;      // State Characteristic Subscriber to access FPGA (write only)
    private Subscription stateSubscription;         // State Characteristic Subscription

    private CompositeSubscription allSubscriptions;

    private DataOps asDataOps;                      // reference to the DataOps class


    Queue<Timestamped<byte[]>> dataQueue = new ConcurrentLinkedQueue<>( );   // queue for receiving timestamped Aeroscope data packets
    // Note it's thread safe
    Queue<DataOps.DataFrame> frameQueue = new ConcurrentLinkedQueue<>( ); // assembled frames from Aeroscope

    Queue<byte[]> outputQueue = new ConcurrentLinkedQueue<>( ); // queue for receiving Aeroscope Output Characteristic messages
    Queue<Timestamped<byte[]>> outNotifQueue = new ConcurrentLinkedQueue<>( ); // queue for receiving timestamped Output messages

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // New (maybe): vector of commands sent/responses received (for test etc.)
    // highest index is most recent message
    // Called by AsInputSubscriber's onNext() with true (=command)
    //        by
    static class ControlMessage {
        Timestamped<byte[]> message;
        boolean sentToAeroscope;  // true if a command to scope, false if a response from it
        ControlMessage( Timestamped<byte[]> content, boolean trueIfCommand ) {
            message = content;
            sentToAeroscope = trueIfCommand;
        }
    }
    static Vector<ControlMessage> ioHistory = new Vector<>( IO_HISTORY_LENGTH );  // holds last several messages sent to & received from Aeroscope
    synchronized static void updateIoHistory( Timestamped<byte[]> content, boolean trueIfCommand ) {
        ioHistory.add( new ControlMessage( content, trueIfCommand ) );            // add latest to end of Vector
        while( ioHistory.size() > IO_HISTORY_LENGTH ) ioHistory.remove( 0 );      // trim the Vector if needed, discarding oldest
        Log.d( LOG_TAG, "Updated I/O history with " + (trueIfCommand? "command to" : "response from") + " Aeroscope" );
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////


    AtomicBoolean writeCommandInProgress; // currently sending/executing a command on Aeroscope Input Characteristic TODO: need?

    // Hardware Parameters
    volatile short batteryVoltage, temperature, acceleration; // added volatile
    byte hardwareId, fpgaRev, firmwareRev;
    int serialNo;
    short cal1, cal2, cal3, cal4;
    short cal10V, cal5V, cal2V, cal1V, cal500mV, cal200mV, cal100mV, cal50mV, cal20mV;
    volatile String debugMsg;
    volatile boolean buttonDown;
    short buttonDownTime;      // -1 if unknown
    long lastOutputTimestamp;  // ms value from last Output message timestamp

    byte[] fpgaRegisterBlock;  // the first 20 8-bit registers

    int batteryState;          // CRITICAL, LOW, MEDIUM, or FULL (0, 1, 2, 3)
    boolean chargerConnected;  // bit 15 of battery register
    boolean chargingNow;       // bit 14

/*
    In the MainActivity, the user presses the Scan button
    This calls asBleServiceRef.scanForAeroscopes(), which scans for up to N Aeroscopes or T seconds. (new)
    Discovered Aeroscopes are put in a Vector<RxBleDevice>
    They are used to construct new Aeroscope objects, which go in a separate Vector<AeroscopeDevice>
    AeroscopeDevice constructor has a CONNECT_ON_DISCOVERY boolean, which we will make false for now
    Aeroscope constructor:
        stores static reference to AeroscopeBluetoothService instance in asBleServiceRef
        sets instance variables
            bleDevice is the underlying Bluetooth device object
            initializes a new AsConnSubscriber connSubscriber to handle connection
            initializes connSubscription to null (since CONNECT_ON_DISCOVERY is false)
            initializes a new AsDataSubscriber dataSubscriber
            leaves Subscription dataSubscription null
            initializes a new AsOutputSubscriber outputSubscriber
            sets asStateChangeSubscription and a new AsStateChangeSubscriber to keep connectionState updated
*/


    // constructor (may eventually want to pass in a data Object of Aeroscope initialization parameters)
    // TODO: see if we can eliminate the service parameter (replaced by Service's onCreate calling our setServiceRef)
    public AeroscopeDevice ( RxBleDevice device, AeroscopeBluetoothService service ) {
        Log.d( LOG_TAG, "Entering constructor with device " + device.getName() );
        asBleServiceRef = service; // save ref to the Service // shouldn't really need to be set here every time ******
        bleDevice = device;
        connSubscriber = new AsConnSubscriber( );
        stateChangeSubscriber = new AsStateChangeSubscriber( device.getName() );
        //connSubscription = CONNECT_ON_DISCOVERY? this.connectBle() : null;  // subscribes to connSubscriber REMOVED in favor of AeroscopeDisplay
        dataSubscriber = new AsDataSubscriber();
        outputSubscriber = new AsOutputSubscriber( device.getName() );   // added device name TODO: may eliminate in favor of Notifications
        outNotifSubscriber = new AsOutNotificationSubscriber( device.getName() );
        inputSubscriber = new AsInputSubscriber( device.getName() );     // added device name

        fpgaRegisterBlock = new byte[20]; // TODO: copy initial values into this array?

        //allSubscriptions = new CompositeSubscription( ); // TODO: check, test

/* Handled in AeroscopeDisplay
        stateChangeSubscription = bleDevice.observeConnectionStateChanges() // (don't know why we'd ever unsubscribe)
                .subscribeOn( Schedulers.io( ) ) // try a separate I/O thread (should be pretty rare events, though)
                .observeOn( AndroidSchedulers.mainThread() ) // seems to be necessary to affect UI
                .subscribe( stateChangeSubscriber ); // just updates the connectionState variable
        //allSubscriptions.add( stateChangeSubscription ); // add to the composite TODO: right?
*/

        writeCommandInProgress = new AtomicBoolean( false );

        // May be better to add them at the time of subscribing
        // allSubscriptions.addAll( connSubscription, stateChangeSubscription, dataSubscription,
        //        outputSubscription, outNotifSubscription, inputSubscription, stateSubscription );

        asDataOps = new DataOps();  // instance of the Data Operations class


        Log.d( LOG_TAG, "Finished constructor with device " + device.getName() );
    }


    @Override // for display in ArrayAdapter view
    public String toString() { return bleDevice.getName() + " (" + bleDevice.getMacAddress() + ")"; }


    // Initialize ref to AeroscopeBluetoothService instance (called by its onCreate())
    public static void setServiceRef( AeroscopeBluetoothService myService ) {
        asBleServiceRef = myService;
    }


    // Connect to this device via BLE
    // Unsubscribe the returned Subscription to disconnect
    Subscription connectBle() {
        return this.bleDevice
                .establishConnection( asBleServiceRef, NO_AUTO_CONNECT ) // returns Observable<RxBleConnection>
                .subscribe( connSubscriber );
    }

    // return the Connection State
    RxBleConnection.RxBleConnectionState getConnectionState() { return connectionState; }


    private boolean isConnected() {
        return bleDevice.getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED;
    }


    // method to return an Observable<connection> if we're connected, or reconnect if necessary
    // TODO: BEWARE race conditions!
    Observable<RxBleConnection> getConnectionObservable() {
        if( isConnected() ) return Observable.just( bleConnection );
        else return bleDevice.establishConnection( asBleServiceRef, NO_AUTO_CONNECT ); // TODO: AUTO or NO?
    }


    // Rethinking some things

    // from the docs on Observable<RxBleConnection> establishConnection(Context context, boolean autoConnect);
    //      --when Observable is unsubscribed, connection is automatically disconnected (and released)
    //      --when the device or system interrupts the connection,
    //          --the Observable emits BleDisconnectedException or BleGattException
    //          --the Observable unsubscribes
    // at least I think that's what it says

    // This Observable does nothing until at least one Subscriber hops on. When the last one is gone, it unsubscribes.
//    public Observable<RxBleConnection> bleConnectObs = bleDevice
//            .establishConnection( asBleServiceRef, NO_AUTO_CONNECT ) // (context, autoconnect)
//            .share();
    // To connect: mySubscription = bleConnectObs.subscribe( mySubscriber );
    // Multiple subscribers can do this, and when the last one unsubscribes, connection is released
    // Presumably if several different classes of Subscribers are subscribed, they'll all get notified
    // of any error or completion. How to handle?
    //

    // Idea: instead of polling a queue of assembled frames, transform the packet Observable into a Frame Observable.
    // Then the UI just has to respond to onNext( Frame ).
//    Observable<DataFrame> frameObservable = Observable
//            .create( new Observable.OnSubscribe<DataFrame> () { // anon inner class implements OnSubscribe interface
//                @Override
//                public void call( Subscriber<? super DataFrame> frameSubscriber ) { // called when a Subscriber subscribes
//                    while( !frameSubscriber.isUnsubscribed() ) { // continue until unsubscribed
//                        try {
//                            if( !frameQueue.isEmpty() ) {  // any frames in the queue?
//                                frameSubscriber.onNext( frameQueue.poll() ); // move the head element to the Subscriber
//                            } else {
//                                try {
//                                    Thread.sleep( FRAMESUBSCRIBER_SLEEP_MS ); // try a 20ms sleep TODO: OK?
//                                } catch( InterruptedException i ) {
//                                    // ignore interrupt
//                                }
//                            }
//                        } catch( Exception e ) {
//                            frameSubscriber.onError( e ); // pass any error to the Subscriber
//                        }
//                    }
//                }
//            })
//            .subscribeOn( Schedulers.io() ) // run .create()'s callback on a separate nonblocking thread
//            ;

/*    // Better idea (StackOverflow)?
    Observable<DataFrame> packets2Frames =
            getConnectionObservable()
                    .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save connection back to the variable
                    .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) )
                    .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                    .doOnNext( notificationObservable -> {
                        // Notification has been set up
                        Log.d( LOG_TAG, "Set up notification for Data frames from device: " + bleDevice.getName() );
                    })
                    .flatMap( notificationObservable -> notificationObservable ) // Unwrap the Observable into Data events
                    .timestamp()
            .scan(  )
            ; // wrap each packet in a Timestamped object

    static class FrameAssembler {
        DataFrame frameUnderConstruction;
        public byte[] receivePacket( byte[] receivedPacket ) {
            // add the received packet to the buffer
            if( containsFullFrame( buffer ) ) {
                return extractFrameAndTrimBuffer();
            } else {
                return null;
            }
        }
    }*/






/*--------------------------------INPUT OPERATIONS (TO AEROSCOPE)---------------------------------*/


    // New effort: write a command to the Aeroscope's Input Characteristic
    // Change: timestamp the sent commands as they are echoed through the Observable TODO: good idea?
    public void writeCommand( byte[] commandBytes ) {
        inputSubscription = getConnectionObservable()  // returns Observable<RxBleConnection>
                .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save the connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.writeCharacteristic( asInputCharId, commandBytes ) ) // returns Observable<byte[]>
                .timestamp()    // returns Observable<Timestamped<byte[]>>
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( inputSubscriber );  // apparently just emits whatever was sent to the Aeroscope
    }


    // Write a value to an FPGA register (using writeCommand)
    public void writeFpgaRegister( int address, int data ) { // int allows casting values >127 to positive numbers
        byte addressByte, dataByte;
        if( address >= 0 && address < 256 ) addressByte = (byte) address;
        else throw new IllegalArgumentException( "writeFpgaRegister address value out of range: " + address );
        if( data >= 0 && data < 256 ) dataByte = (byte) data;
        else throw new IllegalArgumentException( "writeFpgaRegister data value out of range: " + data );
        byte[] fpgaCommand = new byte[3];
        fpgaCommand[0] = WRITE_FPGA_REG[0];
        fpgaCommand[1] = addressByte;
        fpgaCommand[2] = dataByte;
        writeCommand( fpgaCommand );
    }


    // Write the block of 20 FPGA registers to the State Characteristic
    public void writeState( byte[] stateBytes ) {
        stateSubscription = getConnectionObservable()
                .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save the connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.writeCharacteristic( asStateCharId, stateBytes ) ) // returns Observable<byte[]>
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                .subscribe( stateSubscriber );
        //allSubscriptions.add( stateSubscription ); // TODO: right?
    }

/*---------------------------------------/INPUT OPERATIONS----------------------------------------*/


/*-------------------------------OUTPUT OPERATIONS (FROM AEROSCOPE)-------------------------------*/

    // set up a single value? subscription to the Aeroscope's Output messages TODO: is this right? Should we get rid of?
    public void subscribeToOutput() { // messages from Aeroscope's Output Characteristic (which also supports notifications)
        outputSubscription = getConnectionObservable()
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save the connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.readCharacteristic( asOutputCharId ) )
                .timestamp()
                .subscribe( outputSubscriber ); // received messages are added to (confusingly) outputQueue
        //allSubscriptions.add( outputSubscription ); // TODO: right?
    }

    // set up subscription to Notifications on the Aeroscope's Output characteristic (with timestamping)
    public void enableOutputNotification() { // from Output characteristic
        outNotifSubscription = getConnectionObservable()
                .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save the connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asOutputCharId ) )
                // above emits Observable<Observable<byte[]>>
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread. Or does it?)
                .doOnNext( notificationObservable -> {  // emits Observable<byte[]>
                    // Notification has been set up
                    Log.d( LOG_TAG, "Set up Notification for Output messages from device: " + bleDevice.getName() );
                })
                .flatMap( notificationObservable -> notificationObservable ) // Unwrap the Observable<byte[]> into series of byte[]
                .observeOn( AndroidSchedulers.mainThread() )   // may in fact need this
                .timestamp() // wrap each packet in a Timestamped object TODO: may not be desirable
                .subscribe( outNotifSubscriber ); // received, timestamped messages--add to outNotifQueue
        //allSubscriptions.add( outNotifSubscription ); // TODO: right?
    }

/*---------------------------------------/OUTPUT OPERATIONS---------------------------------------*/


/*-----------------------------SAMPLE DATA OPERATIONS (FROM AEROSCOPE)----------------------------*/

    // set up a subscription to the Aeroscope's Data packets (via Notification)
    public void enableDataNotification() { // from Aeroscope's Data Characteristic
        dataSubscription = getConnectionObservable()
                .doOnNext( rxBleConnection -> bleConnection = rxBleConnection ) // save connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) )
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                .doOnNext( notificationObservable -> {
                    // Notification has been set up
                    Log.d( LOG_TAG, "Set up notification for Data frames from device: " + bleDevice.getName() );
                })
                .flatMap( notificationObservable -> notificationObservable ) // Unwrap the Observable into Data events
                .timestamp() // wrap each packet in a Timestamped object
                .subscribe( dataSubscriber ); // received messages are parsed & assembled into frames and queued
        //allSubscriptions.add( dataSubscription ); // TODO: right?
    }

    // turn off Data notifications
    public void disableDataNotification() {
        if( dataSubscription != null ) {
            dataSubscription.unsubscribe();
            // dataSubscription = null; // TODO: right?
            Log.d( LOG_TAG, "Canceled notification for Data frames from device: " + bleDevice.getName() );
        }
    }

    // check if Data notifications are active
    public boolean dataNotificationIsEnabled() {
        boolean enabled = !( dataSubscription.isUnsubscribed() || dataSubscription == null );
        Log.d( LOG_TAG, "dataSubscription indicates Data notifications " + (enabled? "are" : "are not")
                + " enabled for " + bleDevice.getName() );
        return enabled;
    }

    // Returns an Observable that emits DataFrames from the scope
    // Use: something like:
    // Subscription frameSubscription = getFrameObservable()
    //      .doOnNext( this::processFrame )
    //      .subscribe( frameSubscriber )
    Observable<DataOps.DataFrame> getFrameObservable( ) {
        Observable<DataOps.DataFrame> frameObs = this.getConnectionObservable()
                .doOnNext( rxBleConnection -> this.bleConnection = rxBleConnection ) // save connection back to the variable
                .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) )
                .subscribeOn( Schedulers.io() ) // try an I/O thread (doesn't affect UI so shouldn't need to observe on UI thread)
                .doOnNext( notificationObservable -> {
                    // Notification has been set up
                    Log.d( LOG_TAG, "Set up notification for Data frames from device: " + this.bleDevice.getName() );
                })
                .flatMap( notificationObservable -> notificationObservable ) // Unwrap the Observable into Data events
                .timestamp() // wrap each packet in a Timestamped object
                // not sure if casting null is of any use below (message says it's redundant)
                .scan( (ArrayList<Timestamped<byte[]>>) null, (  ArrayList<Timestamped<byte[]>> packetBuffer, Timestamped<byte[]> packet )
                        -> DataOps.addPacket( packetBuffer, packet ) ) // scan returns Observable<packetBuffer> or null
                .filter( pBuf -> pBuf != null ) // emit only non-null (full) packet buffers
                .map( pBuf -> DataOps.pBuf2dFrame( pBuf ) ); // convert packet buffer to frame
        return frameObs;
    }


/*------------------------------------/SAMPLE DATA OPERATIONS-------------------------------------*/


    // special Subscriber for connection to device
    // Note that connection is broken when Observable is unsubscribed, and vice versa
    class AsConnSubscriber extends TestSubscriber<RxBleConnection> { // ****** change to normal Subscriber when done
        public AsConnSubscriber() { // constructor
            super();
            Log.d( LOG_TAG, "Finished AsConnSubscriber constructor" );
        }
        @Override
        public void onNext( RxBleConnection connection ) { // called with emission from Observable<RxBleConnection>
            bleConnection = connection; // volatile
            Log.d( LOG_TAG, "AsConnSubscriber made connection " + connection.toString() );
            // TODO maybe a Toast? some assertions?
        }
        @Override
        public void onCompleted( ) { // called when connection Subscriber finishes (1 emission?)
            Log.d( LOG_TAG, "AsConnSubscriber called onCompleted() " );
            // TODO maybe a Toast? some assertions?
        }
        @Override
        public void onError( Throwable error ) { // called with notification from Observable<RxBleConnection>
            Log.d( LOG_TAG, "AsConnSubscriber error connecting: " + error.getMessage() );
            // presumably may generate BleDisconnectedException or BleGattException
            // maybe a Toast?
        }
    }


    // special Subscriber subclass that receives Aeroscope sample data packets
    // TODO: when using Notifications, does each packet cause a call to onComplete()?
    // Docs: Notification is automatically unregistered once this Observable<Observable<byte[]>> is unsubscribed.
    class AsDataSubscriber extends TestSubscriber<Timestamped<byte[]>> { // TODO ****** change for production(?)
        // an instance of this class is passed to the Observable's subscribe() call,
        // which returns a Subscription object. Calling unsubscribe() on this object should unregister notifications
        AsDataSubscriber() {
            super();
            Log.d( LOG_TAG, "Finished AsDataSubscriber constructor" );
        }
        @Override
        public void onStart() { // apparently not necessary to call super in a Subscriber
            Log.d( LOG_TAG, "AsDataSubscriber onStart() called");
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( Timestamped<byte[]> timestampedBytes ) {
            handleDataPacket( timestampedBytes );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // will this ever happen?
            // dataSubscription.unsubscribe(); // wait, onCompleted is said to automatically unsubscribe
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "AsDataSubscriber error in data feed from Aeroscope: " + e.getMessage(), (Object) null );
            throw new RuntimeException( "AsDataSubscriber error in data feed from Aeroscope: " + e.getMessage(), e );
        }
    }



    // special Subscriber subclass that receives connection state changes
    // added a string for device name
    class AsStateChangeSubscriber extends TestSubscriber<RxBleConnection.RxBleConnectionState> {
        String deviceName;
        public AsStateChangeSubscriber( String devName ) {
            super();
            deviceName = devName;
            Log.d( LOG_TAG, "Finished AsStateChangeSubscriber constructor for device " + deviceName );
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
        // but the Observable has not yet begun to emit items
        public void onStart( ) {
            Log.d( LOG_TAG, "AsStateChangeSubscriber onStart() called for device " + deviceName );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( RxBleConnection.RxBleConnectionState connState ) {
            connectionState = connState;
            if( connectionState != RxBleConnection.RxBleConnectionState.CONNECTED ) bleConnection = null;
            Log.d( LOG_TAG, "AsStateChangeSubscriber: new state of " + deviceName + " is " + connState.toString() );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // not sure this would ever complete
            // unsubscribe here? Need to re-subscribe in onResume? ****** TODO
            // asStateChangeSubscription.unsubscribe();
            this.unsubscribe();
            Log.d( LOG_TAG, "AsStateChangeSubscriber: onCompleted(); " + deviceName + " unsubscribed" );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "Error in Connection State Change from Aeroscope: " + e.getMessage(), (Object) null );
            Log.d( LOG_TAG, "AsStateChangeSubscriber: onError(); " + deviceName + " error: " + e.getMessage() );
            throw new RuntimeException( "Error in Connection State feed from Aeroscope " + deviceName + ": " + e.getMessage(), e );
        }
    }


    // special Subscriber subclass that receives data from Aeroscope's Output Characteristic
    // the way this is set up, it appears that this is a single-item Observable
    // Change: the new type of emission is Timestamped<byte[]>
    class AsOutputSubscriber extends TestSubscriber<Timestamped<byte[]>> {
        String deviceName;
        public AsOutputSubscriber( String devName ) {
            super();
            deviceName = devName;
            Log.d( LOG_TAG, "Finished AsOutputSubscriber constructor for " + deviceName );
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
        // but the Observable has not yet begun to emit items
        public void onStart( ) {
            // super.onStart();  // apparently not necessary to call super in a Subscriber
            Log.d( LOG_TAG, "AsOutputSubscriber onStart() called" );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( Timestamped<byte[]> outputMessage ) {
            //outputQueue.offer( outputMessage );
            updateIoHistory( outputMessage, MSG_FROM_AEROSCOPE );  // add this command to the history Vector
            Log.d( LOG_TAG, "Message from Aeroscope added to Output Queue: " + outputMessage.toString() );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // will we ever get this? I think after 1 emission, since it's readCharacteristic
            // unsubscribe here? Need to re-subscribe in onResume? TODO
            outputSubscription.unsubscribe(); // TODO: correct? (may be redundant)
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "Error in output subscription from Aeroscope: " + e.getMessage(), (Object) null );
            throw new RuntimeException( "Error in output subscription from Aeroscope " + deviceName + ": " + e.getMessage(), e );
        }
    }


    // special Subscriber subclass that receives timestamped Notifications from Aeroscope's Output Characteristic
    // TODO: do we want to timestamp these?
    class AsOutNotificationSubscriber extends TestSubscriber<Timestamped<byte[]>> {
        String deviceName;
        public AsOutNotificationSubscriber( String devName ) {
            super();
            deviceName = devName;
            Log.d( LOG_TAG, "Finished AsOutNotificationSubscriber constructor for " + deviceName );
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
        // but the Observable has not yet begun to emit items
        public void onStart( ) {
            Log.d( LOG_TAG, "AsOutNotificationSubscriber onStart() called for device: " + deviceName );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( Timestamped<byte[]> outputNotification ) {
            //outNotifQueue.offer( outputNotification ); // should we handle directly?
            //Log.d( LOG_TAG, "Message from Aeroscope added to Output Queue: " + outputNotification.toString() );
            //Log.d( LOG_TAG, "Output Queue length: " + outNotifQueue.size() );
            Log.d( LOG_TAG, "Got message from Aeroscope Output: " + outputNotification.toString() );
            updateIoHistory( outputNotification, MSG_FROM_AEROSCOPE );  // NEW: add this command to the history Vector TODO: OK?
            handleOutputFromAeroscope( outputNotification );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // will we ever get this? I think after 1 emission, since it's readCharacteristic
            // automatic unsubscribe here? Need to re-subscribe in onResume? TODO
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "Error in Output Notification subscription from Aeroscope: " + e.getMessage(), (Object) null );
            throw new RuntimeException( "Error in Output Notification subscription from Aeroscope "
                    + deviceName + ": " + e.getMessage(), e );
        }
    }


    // special Subscriber subclass that sends data to Aeroscope's Input Characteristic
    // Change: the new type of emission is Timestamped<byte[]>
    class AsInputSubscriber extends TestSubscriber<Timestamped<byte[]>> { // changed from <byte[]>
        String deviceName;
        public AsInputSubscriber( String devName ) {
            super();
            deviceName = devName;
            Log.d( LOG_TAG, "Finished AsInputSubscriber constructor for " + deviceName );
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
        // but the Observable has not yet begun to emit items. Override to add useful initialization
        public void onStart( ) {
            Log.d( LOG_TAG, "AsInputSubscriber onStart() called for device: " + deviceName );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( Timestamped<byte[]> inputMessage ) {  // just internally plays back the command sent to Aeroscope
            updateIoHistory( inputMessage, CMD_TO_AEROSCOPE );    // add this command to the history Vector
            Log.d( LOG_TAG, "onNext() invoked by AsInputSubscriber for device: " + deviceName
                    + " with data: " + inputMessage.toString() ); //TODO: instead of toString(), something that prints the contents
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // will we ever get this? Yes?
            // unsubscribe here? Need to re-subscribe in onResume? ******
            Log.d( LOG_TAG, "AsInputSubscriber onCompleted() called for device: " + deviceName
                    + "; unsubscribing now");
            inputSubscription.unsubscribe(); // TODO: correct?
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "Error in input subscription to Aeroscope: " + e.getMessage(), (Object) null );
            throw new RuntimeException( "Error in input subscription to Aeroscope: " + e.getMessage(), e );
        }
    }


    // special Subscriber subclass that sends data to Aeroscope's State Characteristic (FPGA registers 0-19)
    class AsStateSubscriber extends TestSubscriber<byte[]> {
        String deviceName;
        public AsStateSubscriber( String devName ) {
            super();
            deviceName = devName;
            Log.d( LOG_TAG, "Finished AsStateSubscriber constructor for " + deviceName );
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
        // but the Observable has not yet begun to emit items
        public void onStart( ) {
            Log.d( LOG_TAG, "AsStateSubscriber onStart() called for device: " + deviceName );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onNext( byte[] inputMessage ) { // just internally plays back the block sent to Aeroscope
            Log.d( LOG_TAG, "onNext() invoked by AsStateSubscriber for device: " + deviceName
                    + " with data: " + inputMessage.toString() );
        }
        @Override // from the Observer interface that Subscriber implements
        public void onCompleted() { // will we ever get this? Yes?
            // unsubscribe here? Need to re-subscribe in onResume? ******
            Log.d( LOG_TAG, "onCompleted() invoked by AsStateSubscriber for device: " + deviceName
                    + "; unsubscribing now" );
            stateSubscription.unsubscribe(); // TODO: correct?
        }
        @Override // from the Observer interface that Subscriber implements
        public void onError( Throwable e ) { // just punt on errors for now
            RxBleLog.d( "Error in State subscription to Aeroscope: " + e.getMessage(), (Object) null );
            throw new RuntimeException( "Error in State subscription to Aeroscope: " + e.getMessage(), e );
        }
    }



    //HELPER METHODS ETC.

    // Stuff for the Main Loop (processing queues, etc.)



    // Main loop(?) TODO: Need?
    private void run() {
        while( true ) {
            if( outputQueue.peek( ) != null )
                //processOutput( ); // if anything in the Output queue from the Aeroscope, handle it.
                if( frameQueue.peek( ) != null );
            //processFrame( ); // there is a waiting Data frame at head of queue
        }
    }

    // Convert battery voltage to one of the 4 conditions (see AeroscopeConstants)
    int getBatteryCondition() {
        //TODO RJL: implement
        chargerConnected = (batteryVoltage & 0x8000) != 0;
        chargingNow = (batteryVoltage & 0x4000) != 0;
        return batteryVoltage & 0x00FF; // for now, the raw voltage TODO: later: the battery condition pseudo-enum
    }

    // An Observable that fires at intervals of 5 seconds to update battery condition
    // Subscribe to it to get onNext() updates of battery condition
    // Don't forget to unsubscribe in onDestroy
    // TODO: should this be in AeroscopeDisplay?
    Observable<Integer> interval5secBattery = Observable
            .interval( 5, TimeUnit.SECONDS )  // returns Observable<Long>, emitting a number every 5 seconds
            .map( nextLong -> this.getBatteryCondition() )   // Battery Condition is an int pseudo-enum
            .observeOn( AndroidSchedulers.mainThread() )
            //.doOnNext( battCond -> onUpdateBattCond( battCond ) )
            ;


    void handleDataPacket( Timestamped<byte[]> timestampedBytes ) {
        // probably don't need this with new Observable<DataFrame>? (referenced by AsDataSubscriber)
    }


    // Got a message (or notification) from Aeroscope Output characteristic
    void handleOutputFromAeroscope( Timestamped<byte[]> outputMessage ) {

        lastOutputTimestamp = outputMessage.getTimestampMillis();
        ByteBuffer dataBuf = ByteBuffer.wrap( outputMessage.getValue() ); // default byte order is Big-Endian
        byte preamble1, preamble2, preamble3;

        preamble1 = dataBuf.get();
        switch ( preamble1 ) {
            case TELEMETRY:  // "T" next bytes are shorts
                batteryVoltage = dataBuf.getShort();
                temperature = dataBuf.getShort();
                acceleration = dataBuf.getShort();
                break;
            case VERSION: // "V" followed by HW ID, FPGA Rev, Firmware Rev, (unimplemented) int S/N
                hardwareId = dataBuf.get();
                fpgaRev = dataBuf.get();
                firmwareRev = dataBuf.get();
                serialNo = dataBuf.getInt();  // TODO: watch out for unimplemented/erroneous
                break;
            case ERROR:  // "E"
                preamble2 = dataBuf.get(); // should be "L" or "I"
                switch( preamble2 ) {
                    case LOG:  // "L"
                        Log.d( LOG_TAG, "Unimplemented Error Log message 'EL'" );
                        break;
                    case IMMEDIATE:  // "I"
                        Log.d( LOG_TAG, "Unimplemented Error Log message 'EI'" );
                        break;
                    default:
                        throw new IllegalArgumentException( "Unrecognized message from Aeroscope starting with E" + (char) preamble2 );
                } // inner switch on byte 2
                break;
            case CALIBRATE:  // "C"
                preamble2 = dataBuf.get();
                switch( preamble2 ) {
                    case QUICK:  // "A"
                        cal1 = dataBuf.getShort();
                        cal2 = dataBuf.getShort();
                        cal3 = dataBuf.getShort();
                        cal4 = dataBuf.getShort();
                        break;
                    case FORCE:
                        cal10V = dataBuf.getShort();
                        cal5V = dataBuf.getShort();
                        cal2V = dataBuf.getShort();
                        cal1V = dataBuf.getShort();
                        cal500mV = dataBuf.getShort();
                        cal200mV = dataBuf.getShort();
                        cal100mV = dataBuf.getShort();
                        cal50mV = dataBuf.getShort();
                        cal20mV = dataBuf.getShort();
                        break;
                    default:
                        throw new IllegalArgumentException( "Unrecognized message from Aeroscope starting with C" + (char) preamble2 );
                }
                break;
            case DEBUG_PRINT:  // "D" followed by null-terminated ASCII string
                StringBuilder sb = new StringBuilder( dataBuf.limit() );
                while (dataBuf.remaining() > 0)
                {
                    char c = (char) dataBuf.get();
                    if ( c == '\0' ) break;
                    sb.append( c );
                }
                debugMsg = sb.toString(); // global variable TODO: should we queue it?
                break;
            case BUTTON:  // "B"
                preamble2 = dataBuf.get();  // followed by D, DT and time H/L, or UT and time H/L (latter 2 are unimplemented, we think)
                switch( preamble2 ) {  // next char must be D or U, else error
                    case DOWN: // "D" can be end of message, or followed by T + time value
                        preamble3 = dataBuf.get();  // should be NULL or T
                        switch( preamble3 ) {
                            case NULL: // was a simple "BD" message
                                buttonDown = true;
                                buttonDownTime = -1;  // TODO: maybe revisit this
                                break;
                            case TIME_DOWN:  // 'T'
                                buttonDown = true;
                                buttonDownTime = dataBuf.getShort();
                                break;
                            default:
                                buttonDown = false;  // bad message format
                                Log.d( LOG_TAG, "Bad Button Down message format: BD" + (char) preamble3 );
                        } // switch on preamble3
                        break;  // done with case DOWN
                    case UP: // button up: should be followed by 'T' with down time (to be implemented)
                        preamble3 = dataBuf.get();  // should be T
                        if( preamble3 == TIME_DOWN ) {  // it is
                            buttonDownTime = dataBuf.getShort();
                            buttonDown = false;  // this was an UP message
                            break;
                        } else {  // appears to be badly-formatted message
                            Log.d( LOG_TAG, "Bad Button Up message format: BU" + (char) preamble3 );
                            break;
                        }
                    default: // second byte of BUTTON message was not UP or DOWN; error
                        Log.d( LOG_TAG, "Bad Button message format: B" + (char) preamble2 );
                } // switch on preamble2
                break;  // done with case BUTTON
            default: // don't recognize the command
                Log.d( LOG_TAG, "Unrecognized message received from Aeroscope Output Characteristic" + (char) preamble1 );
        } // switch on preamble1

    } // handleOutputFromAeroscope


} // class AeroscopeDevice
