package io.aeroscope.aeroscope;

import android.app.Application;
import android.util.Log;

/**
 * Created on 2017-01-08.
 *
 * Note "Application" is base class for maintaining global application state.
 * Perhaps we need it because we can only have one (global) instance of the RxBleClient
 * (the sample application uses SampleApplication, extends Application)
 *
 * Inserted this name in Manifest <Application> tag.
 *
 * The Application class (or subclass) runs first at onCreate() time
 *
 */


/* OVERALL APP INITIALIZATION SEQUENCE
*
* 1. AeroscopeApp onCreate()
*       --Does nothing but call super.onCreate()
*
* 2. AeroscopeBluetoothService onCreate()
*       --Creates RxBleClient instance and saves in static variable asBleClient
*       --Sets log level to DEBUG
*       --Calls AeroscopeDevice.setServiceRef( this ) to pass a static reference to itself to asBleServiceRef
*       --Note this class also has static Vectors of RxBleDevice and AeroscopeDevice (may not need both)
*
* 3. MainActivity onCreate()
*       --Sets up initial UI
*
* 4. MainActivity onStart()
*       --Binds AeroscopeBluetoothService
*             --onServiceConnected() callback
*                   --Sets non-static variables asBleClientRef & asBleServiceRef
* 5. MainActivity onResume()
*       --Register Broadcast Receiver for Scan Results (broadcast by scanSubscriber's onCompleted() in AeroscopeBluetoothService)
*
* OPERATION
*
* 1. User clicks  button to call MainActivity's startScan()
*       --Calls asBleServiceRef.scanForAeroscopes()
*
* 2. scanForAeroscopes() in AeroscopeBluetoothService
*       --Clears the Device Vectors
*       --Creates new scanSubscriber (instance of AsScanSubscriber)
*       --Sets scanSubscription to scan for devices, taking a maximum of 2 (or 60? seconds)
*       --Calls addRxBleScanResult() for each discovered device
*             --Adds device to Vectors if it was not already there
*       --At completion of scan
*             --unsubscribes scanSubscription
*             --broadcasts asDeviceVector (received by MainActivity)
*
* 3. MainActivity: User selects an Aeroscope in the list of discovered devices
*       --Launch Activity AeroscopeDisplay, sending it the list index of the user-selected Aeroscope
*
* */

/* OTHER NOTES
*
*  --Use setSelection(int position) to make a Spinner go to a specific value. This should be part of
*  state that's saved in onPause() and restored in onResume(). Note "in default state a Spinner
*  shows its currently selected value".
*
*  --Keep a list of state items like this to save and restore. May also want to look at onSaveInstanceState()
*
*  --You may need separate TextViews to title the Spinners (Time/Div and Volts/Div). String resources.
*  Or you can try spinner.setPrompt("@string/Volts/Div");
*
*

Add
Subscription connSub; // to globals
connSub = selectedScope.connectBle(); // (change line) do in onStart()(?)
Then you can disconnect by calling
connSub.unsubscribe(); // maybe in onStop()


Declare in globals:
Observable<Long> heartbeat;

In onCreate()??
heartbeat = Observable
    .interval( HEARTBEAT_SECONDS, TimeInterval.SECONDS )
    .observeOn( AndroidSchedulers.mainThread() ) // probably
    .doOnNext( this::doHeartbeat )
    .sample( 60L ) // every minute(?)
    .doOnNext( this::countMinutes );
    
In onResume()??
    heartbeat.subscribe();
    
In onPause()??
    heartbeat.unsubscribe();
    
Create void methods doHeartbeat( Long probablyIgnore ), countMinutes( Long alsoIgnore )
--update battery condition, connection state

Sample app write characteristic code:
if (isConnected()) {
            connectionObservable  // declared private Observable<RxBleConnection> connectionObservable;
                                  // set in onCreate() by connectionObservable = prepareConnectionObservable();
                    .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(characteristicUuid, <a byte[]> ))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(bytes -> {
                        onWriteSuccess();       // just posts a "Write success" Snackbar
                    }, this::onWriteFailure);   // posts a "Write error: " + throwable (the argument) Snackbar
        }


// Set up notification
 if (isConnected()) {
            connectionObservable
                    .flatMap(rxBleConnection -> rxBleConnection.setupNotification(characteristicUuid))
                    .doOnNext(notificationObservable -> runOnUiThread(this::notificationHasBeenSetUp))
                    .flatMap(notificationObservable -> notificationObservable)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onNotificationReceived, this::onNotificationSetupFailure);
        }

* */




public class AeroscopeApp extends Application { // first class to be instantiated, provides global context
    
    // Bluetooth-specific stuff moved to new class AeroscopeBluetoothService
    
    private final static String LOG_TAG = "AeroscopeApp           "; // tag for logging (23 chars max)
    
    
    @Override
    public void onCreate() { // copied straight from RxAndroidBle sample app
        super.onCreate(); // call the Application onCreate()
        Log.i( LOG_TAG, "Returned from super.onCreate() in onCreate()" ); // Logs OK [0010]
        // Client setup etc. moved to AeroscopeBluetoothService
        Log.i( LOG_TAG, "Successfully ran AeroscopeApp's onCreate()" );   // Logs OK [0020]
    }
    

    
} // class AeroscopeApp
