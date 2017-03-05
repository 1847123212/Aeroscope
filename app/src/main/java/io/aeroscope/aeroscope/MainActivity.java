package io.aeroscope.aeroscope;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.polidea.rxandroidble.RxBleClient;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import io.aeroscope.aeroscope.AeroscopeConstants.*;


// MainActivity is defined as the LAUNCH activity in the Manifest
public class MainActivity extends AppCompatActivity implements ServiceConnection {

    private final static String LOG_TAG = "MainActivity           "; // tag for logging (23 chars max)

    // Set by onServiceConnected()
    private RxBleClient asBleClientRef;                   // reference to the BLE Client set by onServiceConnected() ****** need?
    private AeroscopeBluetoothService asBleServiceRef;    // reference to the BLE Service instance set by onServiceConnected()

    AtomicBoolean asBleBound = new AtomicBoolean( false );   // whether Bluetooth Service is bound (set true; false on disconnect)


    // GUI elements
    Button scanButton;
    ListView aeroscopeScanResults;
    //List<String> dummyList = new ArrayList<String>("Item 1", "Item 2", "Item 3");
    Vector<AeroscopeDevice> foundDevices = new Vector<>();

/*
    If using a Client/Service architecture to handle Bluetooth:

    1. As the Client, this Activity implements ServiceConnection interface,
       overriding onServiceConnected() and onServiceDisconnected()

    2. Client calls bindService() [typically in onStart()] passing an Intent
       (identifying Service class), the ServiceConnection instance (this), and flags

    3. System will call our onServiceConnected(), returning an IBinder. We cast this IBinder to
       our LocalBinder (in Service class)

    4. Then we can call the Service using methods provided in IBinder.
       In this case, the IBinder provides a asBleBinder.getService() call
       that returns the instance of AeroscopeBluetoothService (stored in asBleServiceRef)
       So we can call any service methods with asBleServiceRef.methodName()
       (Note that you can call static methods with an object reference)

    5. Call unbindService() to disconnect from it

*/

/*---------------------------------SERVICE CONNECTION INTERFACE-----------------------------------*/

    // Created & used by onStart() in call to bindService()
    Intent bindAsBleIntent; // can pass extra data to the Service if we need to
    // e.g. myIntent.putExtra( "Name", <value> ); where <value> can be a whole range of <type>s
    // retrieve with <type> = myIntent.get<typeindicator>Extra( "Name" );

    // Implementation of ServiceConnection Interface: 2 methods
    @Override
    // called by system when connection with service is established, with object to interact with it.
    // Here, the IBinder has a getService() method that returns a reference to the Service instance
    // (If we connect to multiple services, probably have to test parameters to see which)
    public void onServiceConnected( ComponentName className, IBinder service ) {
        Log.d( LOG_TAG, "Entering onServiceConnected()" ); // [0090]
        // We've bound to Service, cast the IBinder and get Service instance
        AeroscopeBluetoothService.LocalBinder asBleBinder = (AeroscopeBluetoothService.LocalBinder) service;
        // (casting it makes compiler aware of existence of getService() method)
        asBleServiceRef = asBleBinder.getService();        // returns the instance of the Service
        if( asBleServiceRef == null ) throw new RuntimeException( LOG_TAG
                + ": onServiceConnected returned null from getService()" );

        asBleClientRef = asBleServiceRef.getRxBleClient(); // static member accessed by instance reference--OK!
        if( asBleClientRef == null ) throw new RuntimeException( LOG_TAG
                + ": onServiceConnected returned null from getRxBleClient()" );

        asBleBound.set( true ) ; // success: set "bound" flag TODO: need?
        Log.d( LOG_TAG, "Finished onServiceConnected()" ); // reached here OK [0110]
    }

    @Override // called when the connection with the service has been unexpectedly disconnected
    public void onServiceDisconnected( ComponentName name ) {
        asBleBound.set( false ) ; // no longer bound
        Log.d( LOG_TAG, "Finished onServiceDisconnected()" );
    }

/*------------------------------------------------------------------------------------------------*/


/*--------------------------------------LIFECYCLE METHODS-----------------------------------------*/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d( LOG_TAG, "Returned from super.onCreate()" ); // [0030]

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        scanButton = (Button) findViewById(R.id.scanButton);
        scanButton.setText("Scan for Aeroscopes");
        aeroscopeScanResults = (ListView) findViewById(R.id.FoundAeroscopes); // need layout for this to compile
        

        Log.d( LOG_TAG, "Finished onCreate()" ); // [0040]
    } // onCreate


    @Override
    protected void onStart() {
        super.onStart();
        Log.d( LOG_TAG, "Returned from super.onStart()" ); // [0050]
        // Bluetooth stuff (was in onCreate() but sample code had it here)
        bindAsBleIntent = new Intent( getApplicationContext(), AeroscopeBluetoothService.class ); // intent to enable service

        if( !bindService( bindAsBleIntent, /*ServiceConnection*/ this, Context.BIND_AUTO_CREATE ) ) // flag: create the service if it's bound
            throw new RuntimeException( LOG_TAG + ": bindService() call in onStart() failed" );
        // can't do much else until we get the onServiceConnected() callback

        Log.d( LOG_TAG, "Finished onStart()" ); // seems to be successfully reached [0060]
    }

    @Override // To receive Vector of discovered devices
    public void onResume() { // TODO ****** do we need this? // may want to unsubscribe/resubscribe to things?
        super.onResume();
        // Register mMessageReceiver to receive messages  // or register in Manifest(?)
        LocalBroadcastManager.getInstance( this ).registerReceiver( mMessageReceiver, new IntentFilter( "scanResults" ) );
    }

    @Override
    protected void onPause() {
        // Unregister since the activity is not visible
        LocalBroadcastManager.getInstance( this ).unregisterReceiver( mMessageReceiver );
        super.onPause();
    }

    @Override
    protected void onStop() {
        unbindService(this);
        super.onStop();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unsubscribe from all subscriptions to avoid memory leaks!
    }
    
    // handler for received Intents for the "scanResults" event
    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Extract data included in the Intent
            // String message = intent.getStringExtra("message”);
            foundDevices = (Vector<AeroscopeDevice>) intent.getSerializableExtra( "io.aeroscope.aeroscope.DeviceVector" );
            Log.d( LOG_TAG, "Got foundDevices: " + foundDevices.toString() );
            // Do stuff
            ArrayAdapter<AeroscopeDevice> aeroscopeArrayAdapter = new ArrayAdapter<>(getBaseContext(), android.R.layout.simple_list_item_1, foundDevices);
            aeroscopeScanResults.setAdapter(aeroscopeArrayAdapter);

            aeroscopeScanResults.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                    Intent switchToDeviceView = new Intent(getApplicationContext(), AeroscopeDisplay.class);
                    //switchToDeviceView.putExtra("selectedDevice", foundDevices.get(position).toString());
                    switchToDeviceView.putExtra( "selectedScopeIndex", position);
                    Log.d(LOG_TAG, "Position value of ListView press: " + String.valueOf(position));
                    startActivity(switchToDeviceView);
                }
            });

            scanButton.setText("Scan Again?");
            Log.d(LOG_TAG, "We are done scanning.");
        }
    };


/*------------------------------------------------------------------------------------------------*/



    // When user clicks the Scan button
    @SuppressWarnings( "static-access" ) // it is permissible to access static members with an instance reference
    public void startScan(View userPress) {


        Log.d( LOG_TAG, "Entered startScan()" ); // reached here OK [0120]
        asBleServiceRef.scanForAeroscopes(); // fills the rxBleDeviceVector and asDeviceVector with detected Aeroscopes (tests, connects)
        Log.d( LOG_TAG, "Finished startScan()" ); // reached here in initial scan [0160]
        scanButton.setText("Scanning...");


    }

    // When user clicks "stop scanning" call asBleServiceRef.stopScanning();
    // Then call & display asBleServiceRef.getFoundDeviceCount() and asBleServiceRef.getFoundDeviceVector().
    
// How the sample app scans for devices:
//    scanSubscription = rxBleClient.scanBleDevices() // note no Service ID supplied
//            .observeOn( AndroidSchedulers.mainThread())
//            .doOnUnsubscribe(this::clearSubscription)
//            .subscribe(resultsAdapter::addScanResult, this::onScanFailure);
//private void clearSubscription() {
//    scanSubscription = null;
//    resultsAdapter.clearScanResults();
//    updateButtonUIState();
//}
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    
    
    
    
}
