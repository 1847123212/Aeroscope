package io.aeroscope.aeroscope;


import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleLog;

import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

import static io.aeroscope.aeroscope.AeroscopeConstants.MAX_AEROSCOPES;
import static io.aeroscope.aeroscope.AeroscopeConstants.SCAN_TIME;
import static io.aeroscope.aeroscope.AeroscopeConstants.asServiceIdArray;

/**
 * Created on 2017-02-01.
 */

/*
A place to put the Bluetooth-specific code for Aeroscope
Update 2017-02-25: moved all the constants into new class AeroscopeConstants and imported it

Stuff that lives here:
    Constants etc.
        Static
            LOG_TAG
    RxAndroidBle Variables
        Static
            asBleClient: set by onCreate()
            rxBleDeviceVector: populated by scanForAeroscopes
            asDeviceVector: populated by scanForAeroscopes
            nowScanning: AtomicBoolean
            errorScanning: Atomic Boolean
            scanSubscription: for device scan
            scanSubscriber:      "
    Note on absent Constructor
    
    Methods for Clients
        AeroscopeBluetoothService getService();      returns reference to instance of this class
        IBinder onBind(Intent intent);               returns asBleBinder to Clients
        void onRebind( Intent intent );              just logs
        boolean onUnbind(Intent intent);             returns true to have onRebind() called when clients bind
        static RxBleClient getRxBleClient();         returns asBleClient;
        static void setClient( RxBleClient client ); sets asBleClient = client
        void addRxBleScanResult( RxBleScanResult scanResult ); calls addRxBleDevice
        boolean addRxBleDevice( RxBleDevice device ); adds device to Vector if not present, calls addAeroscopeDevice
        boolean addAeroscopeDevice( AeroscopeDevice asDevice ); adds device to Vector if not present
        void scanForAeroscopes();                    clears both Vectors, starts scan, subscribes scanSubscriber, scanSubscription
        synchronized void stopScanning();            unsubscribes, clears nowScanning flag
        boolean deviceScanIsActive();                returns nowScanning flag
        boolean lastDeviceScanGotError();            returns errorScanning flag
        int getFoundDeviceCount();                   returns rxBleDeviceVector.size()
        int getFoundAeroscopeCount();                returns asDeviceVector.size() (redundant?)
        Vector<RxBleDevice> getFoundDeviceVector();  returns rxBleDeviceVector
        Vector<AeroscopeDevice> getFoundAeroscopeVector(); returns asDeviceVector
        Subscription connect( AeroscopeDevice device, Subscriber<RxBleConnection> connSubscriber, boolean autoConnect); connects
        static void disconnect( AeroscopeDevice asDevice); unsubscribes, sets connection to null
        
        static void handleScanError( Throwable scanError, Context context ); Toasts reasons for scan problems; called by AsScanSubscriber.onError() ******TEST
        static void setClient( RxBleClient client ); sets asBleClient = client
        
*/

    
// Must be declared public (and listed in Manifest) to be used as a Service
public class AeroscopeBluetoothService extends Service { // a Bound Service is an implementation of abstract Service class
    
    // Constants, Options
    private final static String LOG_TAG = "AeroscopeBluetoothServc"; // tag for logging (23 chars max)
    
    // RxAndroidBle static variables
    static RxBleClient asBleClient; // only one (set by this onCreate()); reference as AeroscopeBluetoothService.asBleClient
    static Vector<RxBleDevice> rxBleDeviceVector = new Vector<>();    // can hold multiple devices discovered during scan
    static Vector<AeroscopeDevice> asDeviceVector = new Vector<>();   // can hold multiple Aeroscopes created from the above
    static AtomicBoolean nowScanning   = new AtomicBoolean( false );  // indicates when device scan is active
    static AtomicBoolean errorScanning = new AtomicBoolean( false );  // indicates if last scan got an error
    static Subscription scanSubscription;     // for scanning for BLE devices
    static AsScanSubscriber<RxBleScanResult> scanSubscriber;
    
    // I THINK onCreate takes the place of a constructor
    
    @Override
    public void onCreate( ) {
        super.onCreate( ); // apparently a Bound Service's onCreate() isn't passed Bundle savedInstanceState
        // Try doing this here and not in App onCreate() Seems to work
        asBleClient = RxBleClient.create( /*context*/ this ); // we're only supposed to have 1 instance of Client
        RxBleClient.setLogLevel( RxBleLog.DEBUG );
        Log.i( LOG_TAG, "onCreate() created client and set log level."); //
        RxBleLog.d( "RxBleLog: ABS onCreate(): Created client and set log level.", (Object)null ); // works; seems to arrive here without incident
        //TODO: following line probably not wanted with the Service binding, right? (for Activities, yes, but prob. not for AeroscopeDevice)
        AeroscopeDevice.setServiceRef( this ); // pass ref to Service instance to AeroscopeDevice class // TODO: does this work? Seems to
    }
    
    //TODO: do we need onPause(), onResume()?
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unsubscribe from all subscriptions to avoid memory leaks!
        // This is the only possibly active subscription in the Service:
        if( !scanSubscription.isUnsubscribed() ) scanSubscription.unsubscribe(); // don't know if the test is necessary
    }
    
    
    
/*----------------------------------- SERVICE BINDING STUFF --------------------------------------*/
    
    // Binder given to clients for service--returns this instance of the Service class
    private final IBinder asBleBinder = new LocalBinder();
    
    // Class for binding Service
    public class LocalBinder extends Binder {
        AeroscopeBluetoothService getService() {
            Log.d( LOG_TAG, "Entering asBleBinder.getService()" ); // reached OK [0100]
            // return this instance of AeroscopeBluetoothService so Clients can call public methods
            // (note you can call static methods with an instance identifier)
            return AeroscopeBluetoothService.this; // include class name; otherwise "this" == LocalBinder?
        } // getService
    } // LocalBinder
    
    @Override // Service must implement this & return an IBinder
    public IBinder onBind(Intent intent) {
        Log.d( LOG_TAG, "Entering onBind()" ); // [0080]
        // This would appear to be where you can fetch "Extra" info sent with the Intent
        // e.g., String macAddress = intent.getStringExtra( "MAC_Address_Extra" );
        return asBleBinder; // can call the returned instance with .getService
    }
    
    @Override // do we want/need this?
    public void onRebind( Intent intent ) { Log.d( LOG_TAG, "onRebind() entered" ); } // TODO: anything here?
    
    // Of possible use:
    @Override
    public boolean onUnbind(Intent intent) {
        super.onUnbind( intent );
        Log.d( LOG_TAG, "Returned from super.onUnbind()" );
        // called when all clients have disconnected from the Intent interface
        // Return true to have the service's onRebind(Intent) method later called when new clients bind to it.
        // If Service returns true from onUnbind(), onRebind() will be called in the Service
        // when you call bindService() from the Activity.
        return true;
    }
    
/*----------------------------------- /SERVICE BINDING STUFF -------------------------------------*/

    
/*------------------------------------- BLUETOOTH UTILITIES --------------------------------------*/
    
    // get the client
    // this is how sample does it; necessary?
    //    public static RxBleClient getRxBleClient(Context context) {
    //        AeroscopeApp application = (AeroscopeApp) context.getApplicationContext();
    //        return application.rxBleClient;
    //    }
    public static RxBleClient getRxBleClient() {
        return asBleClient;
    } ///TODO: make sure this works
    // note static because there is only one client even if we have multiple devices
    
    public void addRxBleScanResult( RxBleScanResult scanResult ) {
        RxBleDevice device = scanResult.getBleDevice();
        Log.d( LOG_TAG, "addRxBleScanResult about to call addRxBleDevice with device " + device.toString() );
        addRxBleDevice( device );
    }
    
    // Add an RxBleDevice to the Vector (returns true if it was not already present)
    // note may not really need this separate vector
    public boolean addRxBleDevice( RxBleDevice device ) {
        Log.d( LOG_TAG, "Entering addRxBleDevice with device " + device.getName() );
        if( !rxBleDeviceVector.contains( device ) ) {
            Log.d( LOG_TAG, "Adding RxBleDevice " + device.getName() + " to Vector" );
            addAeroscopeDevice( new AeroscopeDevice( device, this ) ); // add an AeroscopeDevice using this (constructor connects)
            return rxBleDeviceVector.add( device ); // true
        } else return false; // device was already in vector
    }
    
    // Add an Aeroscope device to the Vector (returns true if it was not already present)
    // static because there's just 1 vector
    public boolean addAeroscopeDevice( AeroscopeDevice asDevice ) {
        Log.d( LOG_TAG, "Entering addAeroscopeDevice with device " + asDevice.bleDevice.getName() );
        if( !asDeviceVector.contains( asDevice ) ) {
            Log.d( LOG_TAG, "Adding AeroscopeDevice " + asDevice.bleDevice.getName( ) + " to Vector" );
            return asDeviceVector.add( asDevice ); // true
        } else return false; // device was already in vector
    }
    
    // method called when user hits Scan button
    // populates vectors of RxBleDevice & AeroscopeDevice found
    // note this is an "infinite" Observable (made finite by the .take() calls)
    // the Subscriber unsubscribes itself in its onCompleted() [but .take() unsubscribes implicitly]
    public void scanForAeroscopes() { // entered here OK
        Log.d( LOG_TAG, "Entered scanForAeroscopes()" ); // [0130]
        rxBleDeviceVector.clear( );
        asDeviceVector.clear( );
        // "scanning" and "error" flags are managed by scanSubscriber's lifecycle methods
        // since we added time and device count limits, we know this Observable will not run forever
        scanSubscriber = new AsScanSubscriber<>( ); // here, a subscriber to RxBleScanResult
        scanSubscription = asBleClient
                .scanBleDevices( asServiceIdArray )// returns infinite Observable<RxBleScanResult>, filtered by Service UUID --works
                .subscribeOn( Schedulers.io() )   // NEW: use low-demand I/O thread pool TODO: works? Seems to
                .observeOn( AndroidSchedulers.mainThread( ) )
                // with next 2 lines, Observable should finish after MAX_AEROSCOPES Aeroscopes or SCAN_TIME seconds, whichever comes first
                .take( MAX_AEROSCOPES ) // limit the number of Aeroscopes we detect (initially, 2) NOTE: read that take() implicitly unsubscribes.
                .take( SCAN_TIME, TimeUnit.SECONDS )  // limit the length of the scan (initially 60L--these values are now in AeroscopeConstants)
                .doOnNext( this::addRxBleScanResult ) // put a found device in the Vectors
                .subscribe( scanSubscriber ); // should call scanSubscriber.onStart() (which sets nowScanning true)
        
        Log.d( LOG_TAG, "Exiting scanForAeroscopes() after subscribing" ); // reached here during initial scan [150]
    }
    
    // In the sample app, unsubscribe() is only called when the user toggles the Scan button and in the onPause() method
    // Here, it appears that the .take() calls will implicitly unsubscribe.
    
    // method to stop a device scan
    public synchronized void stopScanning() {
        scanSubscription.unsubscribe(); // may be NOP if .take() has already implicitly unsubscribed (above)
        nowScanning.set( false );
        Log.d( LOG_TAG, "stopScanning() has run" );
    }
    
    // methods to test if scan of BLE devices is active or got an error
    public boolean deviceScanIsActive()     { return nowScanning.get(); }
    public boolean lastDeviceScanGotError() { return errorScanning.get(); }
    
    // method to see how many devices have been found (since last scan was started)
    public int getFoundDeviceCount() {
        return rxBleDeviceVector.size();
    }
    
    // method to see how many Aeroscopes have been found (since last scan was started)
    public int getFoundAeroscopeCount() {
        return asDeviceVector.size();
    }
    
    public Vector<RxBleDevice> getFoundDeviceVector() {
        return rxBleDeviceVector;
    }
    
    public Vector<AeroscopeDevice> getFoundAeroscopeVector() {
        return asDeviceVector;
    }
    
    
    
/*------------------------------------ /BLUETOOTH UTILITIES --------------------------------------*/


/*----------------------------------------- SUBSCRIBERS ------------------------------------------*/
    
    // A special Subscriber for testing the Scan operation
    // maintains the "nowScanning" state boolean, accessed by deviceScanIsActive()
    // and "errorScanning", accessed by lastDeviceScanGotError()
    // Apparently don't have to call super for the 3 methods. Not sure about onStart()
    // And don't forget, ALL Subscribers implement Subscription's 2 methods: isUnsubscribed() and unsubscribe()
    public class AsScanSubscriber<T> extends TestSubscriber<T> {
        @Override // from Interface Observer
        public void onNext( T emission ) {
            Log.d( LOG_TAG, "AsScanSubscriber onNext() result received: " + emission.toString() );
        }
        @Override // from Interface Observer
        public void onError( Throwable error ) { // presumably triggers unsubscribe(?)
            nowScanning.set( false );    // reset "scan active" flag (synchronized) (error ends scan)
            errorScanning.set( true );   // set the "scan error" flag
            Log.d( LOG_TAG, "AsScanSubscriber onError() received: " + error.getMessage(), error );
            handleScanError( error, getApplicationContext() ); // untested TODO: test
        }
        @Override // from Interface Observer
        public void onCompleted( ) {      // since scan Observable is infinite, should only be called on timeout or max devices discovered
            nowScanning.set( false );     // reset "scan active" flag (synchronized)
            errorScanning.set( false );   // set the "scan error" flag (probably redundant, but...)
            this.unsubscribe();           // NEW: once the scan is done, shut it off(?) TODO? (actually, the .take() calls implicitly unsubscribe)
            Log.d( LOG_TAG, "AsScanSubscriber onCompleted() called; unsubscribed" ); // [0170]
        
            // Broadcast the results (new)
            Intent scanIntent = new Intent( "scanResults" );
            scanIntent.putExtra( "io.aeroscope.aeroscope.DeviceVector", asDeviceVector ); // name must include package; Vector implements Serializable
            LocalBroadcastManager.getInstance( getApplicationContext() ).sendBroadcast( scanIntent ); // not sure this Context will work (seems to)
            Log.d( LOG_TAG, "AsScanSubscriber onCompleted() broadcast asDeviceVector" ); //
        }
        @Override // from Class Subscriber: invoked when the Subscriber and Observable have been connected
                  // but the Observable has not yet begun to emit items
        public synchronized void onStart( ) {
            super.onStart();              // may be necessary, not sure
            nowScanning.set( true );      // set "scan active" flag (synchronized)
            errorScanning.set( false );   // set the "scan error" flag (probably redundant, but...)
            Log.d( LOG_TAG, "AsScanSubscriber onStart() called" ); // reached here during initial scan [0140]
        }
    }
    
/*----------------------------------------- /SUBSCRIBERS -----------------------------------------*/
    
    
    // Handling an error in the initial scan for Bluetooth devices (e.g., Bluetooth not enabled) TODO: test (worked once, I think)
    // Called by the onError() method of AsScanSubscriber
    static void handleScanError( Throwable scanError, Context context ) {
        Log.d( LOG_TAG, "Entering handleScanError: " + scanError.getMessage() );
        if (scanError instanceof BleScanException ) {
            switch ( ((BleScanException) scanError).getReason() ) {
                case BleScanException.BLUETOOTH_NOT_AVAILABLE:
                    Log.d( LOG_TAG, "handleScanError: Bluetooth is not available" );
                    Toast.makeText( context, "Bluetooth is not available", Toast.LENGTH_SHORT ).show( );
                    break;
                case BleScanException.BLUETOOTH_DISABLED:
                    Log.d( LOG_TAG, "handleScanError: Bluetooth is disabled" );
                    Toast.makeText( context, "Enable bluetooth and try again", Toast.LENGTH_SHORT ).show( );
                    break;
                case BleScanException.LOCATION_PERMISSION_MISSING:
                    Log.d( LOG_TAG, "handleScanError: Location permission missing" );
                    Toast.makeText( context,
                            "On Android 6.0 location permission is required. Implement Runtime Permissions", Toast.LENGTH_SHORT ).show( );
                    break;
                case BleScanException.LOCATION_SERVICES_DISABLED:
                    Log.d( LOG_TAG, "handleScanError: Location services disabled" );
                    Toast.makeText( context, "Location services needs to be enabled on Android 6.0", Toast.LENGTH_SHORT ).show( );
                    break;
                case BleScanException.BLUETOOTH_CANNOT_START:
                default:
                    Log.d( LOG_TAG, "handleScanError: Bluetooth cannot start" );
                    Toast.makeText( context, "Unable to start scanning (reason unknown)", Toast.LENGTH_SHORT ).show( );
            } // switch
        } else { // exception was not a BleScanException
            Log.d( LOG_TAG, "handleScanError: Unknown scanning error" );
            Toast.makeText( context, "Unknown error scanning for Aeroscopes", Toast.LENGTH_SHORT ).show( );
        }
        Log.d( LOG_TAG, "handleScanError Finished" );
    }
}
