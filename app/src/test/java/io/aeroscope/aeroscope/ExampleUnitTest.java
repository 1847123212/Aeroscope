package io.aeroscope.aeroscope;

import android.app.Instrumentation;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.UUID;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.observers.TestSubscriber;

import static org.junit.Assert.assertEquals;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest extends Instrumentation {
    
    // mock values, may be changed?
    String macAddress = "01:23:45:67:89:0A";
    String deviceName = "Aeroscope Mock";
    byte[] scanRecordBytes = {00, 01, 02};
    int rssiValue = 66;
    
    
    // The standard Bluetooth Base UUID; replace the first group with "0000SSSS" for 16-bit or "LLLLLLLL" for 32-bit abbreviated values
    static final String BASE_UUID_STRING = "00000000-0000-1000-8000-00805F9B34FB"; //
    static final String BASE_UUID_HEAD = "0000"; // for 16-bit UUIDs we replace digits 4-7 of the base string with the value
    static final String BASE_UUID_TAIL = "-0000-1000-8000-00805F9B34FB"; //
    
    static final String CLIENT_CHAR_CONFIG_STRING = "2902"; // GATT standard Client Characteristic Configuration UUID
    static final UUID clientCharConfigID = UUID.fromString( BASE_UUID_HEAD + CLIENT_CHAR_CONFIG_STRING + BASE_UUID_TAIL ); // for notifications/indications
    static final byte[] asDisableNotifications = { 0, 0 }; // 2 bytes of 0 = 16-bit Descriptor
    static final byte[] asEnableNotifications  = { 1, 0 }; // 2 bytes of 0 = 16-bit Descriptor ASSUMES LITTLE-ENDIAN
    
    // Aeroscope Device constants
    static final String AEROSCOPE_UUID_HEAD  =  "F954"; // next 4 characters are the short-form UUID
    static final String AEROSCOPE_UUID_TAIL  =  "-91B3-BD9A-F077-80F2A6E57D00";
    static final String asServiceIdString    =  AEROSCOPE_UUID_HEAD + "1234" + AEROSCOPE_UUID_TAIL; // Aeroscope Service UUID
    static final String asDataCharIdString   =  BASE_UUID_HEAD + "1235" + BASE_UUID_TAIL; // Aeroscope Data UUID
    static final String asInputCharIdString  =  BASE_UUID_HEAD + "1236" + BASE_UUID_TAIL; // Aeroscope Input UUID
    static final String asStateCharIdString  =  BASE_UUID_HEAD + "1237" + BASE_UUID_TAIL; // Aeroscope State UUID
    static final String asOutputCharIdString =  BASE_UUID_HEAD + "1239" + BASE_UUID_TAIL; // Aeroscope Output UUID
    static final UUID asServiceId =    UUID.fromString( asServiceIdString ); // a Service is a collection of Characteristics
    static final UUID asDataCharId =   UUID.fromString( asDataCharIdString );
    static final UUID asInputCharId =  UUID.fromString( asInputCharIdString );
    static final UUID asStateCharId =  UUID.fromString( asStateCharIdString );
    static final UUID asOutputCharId = UUID.fromString( asOutputCharIdString );
    
    static final UUID[] asServiceIdArray = { asServiceId }; // vararg argument to scanBleDevices is implicitly an array
    
    
    // assuming a packet is 20 bytes, this is a 16-byte Aeroscope frame
    // the first 1 means Start Of 16-byte Frame, followed by 16 data bytes, followed by 3 pad bytes for a total of 20
    final byte[] asDataCharBytes = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, -1, -2, -3};
    
    final byte[] asInputCharBytes = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, -1, -2, -3};
    final byte[] asStateCharBytes = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, -1, -2, -3};
    final byte[] asOutputCharBytes = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, -1, -2, -3};
    
    RxBleClient rxBleClientMock;
    Subscription scanSubscription;
    Subscription connectSubscription;
    Subscription dataTestSubscription;
    TestSubscriber<RxBleScanResult> scanResultTestSubscriber;
    TestSubscriber<RxBleConnection> connectionTestSubscriber;
    TestSubscriber<byte[]> dataTestSubscriber;
    AsConnectionSubscriber asConnectionSubscriber; // maybe the TestSubscriber hands off to this?
    
    // AeroscopeApp asTestApp = (AeroscopeApp) getApplication(); // doesn't work
    Context appContext; // = this.getTargetContext();
    
    
    @Before // inserted by RL
    public void setUp() {
        appContext = this.getTargetContext();
        asConnectionSubscriber = new AsConnectionSubscriber( );
        //connectionTestSubscriber = TestSubscriber.create( asConnectionSubscriber ); // delegates events to asConnectionSubscriber
        connectionTestSubscriber = new TestSubscriber<>( asConnectionSubscriber ); // delegates events to asConnectionSubscriber
        
        rxBleClientMock = new RxBleClientMock.Builder( )
                .addDevice( new RxBleClientMock.DeviceBuilder( ) // <-- creating device mock, there can me multiple of them
                        .deviceMacAddress( macAddress )
                        .deviceName( deviceName )
                        .scanRecord( scanRecordBytes )
                        .rssi( rssiValue )
                        .addService( // <-- adding service mocks to the device, there can be multiple of them
                                asServiceId, // Service is a UUID and a List<BluetoothGattCharacteristic>
                                new RxBleClientMock.CharacteristicsBuilder( ) // Characteristic is a UUID, a byte[], and a List<BluetoothGattDescriptor>
                                        .addCharacteristic( // <-- adding characteristic mocks to the service, there can be multiple of them
                                                asDataCharId, asDataCharBytes,
                                                new RxBleClientMock.DescriptorsBuilder() // add Descriptor to Characteristic
                                                        .addDescriptor(clientCharConfigID, asDisableNotifications) // <-- adding descriptor mocks
                                                        .build() // the Descriptor
                                        )  // add Characteristic (first one)
                                        .addCharacteristic(
                                                asInputCharId, asInputCharBytes,
                                                new ArrayList<BluetoothGattDescriptor>( 0 ) ) // (empty List--no Descriptors)
                                        .addCharacteristic(
                                                asStateCharId, asStateCharBytes,
                                                new ArrayList<BluetoothGattDescriptor>( 0 ) ) // BluetoothGattDescriptor is a UUID and a byte[]
                                        .addCharacteristic(
                                                asOutputCharId, asOutputCharBytes,
                                                new RxBleClientMock.DescriptorsBuilder() // add Descriptor to Characteristic
                                                        .addDescriptor(clientCharConfigID, asDisableNotifications) // <-- adding descriptor mocks
                                                        .build() // the Descriptor
                                        ) // add Characteristic (last one)
                                        .build( ) // the Characteristics
                        ) // addService (the only one)
                        .build( ) // Device
                ) // addDevice
                .build(); // Client
    } // setUp
    
    @Test // works (finally) -- also OK after switching from Jack to Retrolambda
    public void addition_isCorrect() throws Exception {
        assertEquals(4, 2 + 2);
    }
    
    @Test // added by RL--works -- also OK after switching from Jack to Retrolambda
    public void macAddress_matches() throws Exception {
        scanSubscription = rxBleClientMock.scanBleDevices( )  // scan produces Observable of RxBleScanResult(s)
                .doOnNext( item -> System.out.println( "macAddress_matches' doOnNext item: " + item.toString() ) ) // Prints 1 RxBleScanResult
                .subscribe( rxBleScanResult -> {
                    // Process scan result here.
                    System.out.println( "macAddress_matches' onNext lambda: " + rxBleScanResult.toString() );   // Prints same result as above
                    String reportedMac = rxBleScanResult.getBleDevice().getMacAddress();
                    assertEquals( reportedMac, macAddress );  // OK
                    String reportedName = rxBleScanResult.getBleDevice().getName();
                    assertEquals( reportedName, deviceName ); // OK
                } );

    // When done, just unsubscribe.
        scanSubscription.unsubscribe();
        
    } // macAddress_matches
    
    
    
    // Scan Bluetooth devices looking for the Aeroscope Service UUID
    // Return a 1-item Observable<RxBleDevice>
    // approach suggested on StackOverflow--returns Observable<RxBleDevice> (with only 1 item)
    Observable<RxBleDevice> scanForAeroscope() { // approach suggested on StackOverflow
        System.out.println( "Entering scanForAeroscope" );
        return rxBleClientMock
                .scanBleDevices( asServiceIdArray ) // returns Observable<RxBleScanResult> NOTE: vararg argument is implicit array!
                //.scanBleDevices(  ) // returns Observable<RxBleScanResult>
                //.observeOn( AndroidSchedulers.mainThread() ) // from demo app; necessary? ****** (doesn't seem to hurt, but suspect for test)
                .doOnSubscribe( () -> System.out.println( "doOnSubscribe in scanForAeroscope" ) )      // OK
                .doOnNext( item -> System.out.println( "scanForAeroscope doOnNext item: " + item.toString() ) ) // works if scan isn't limited to asServiceID
                .doOnCompleted( () -> System.out.println( "doOnCompleted in scanForAeroscope" ) )      // OK or not
                .doOnUnsubscribe( this::clearScanSubscription ) // set the scan subscription to null
                .doOnError( this::handleScanError ) // calls with a Throwable parameter
                .first() // (just the first one and done; now have Observable emitting a single RxBleScanResult)
                .map( rxBleScanResult -> rxBleScanResult.getBleDevice() ); // returns 1-item Observable<RxBleDevice>
        // revisiting this: where is the 'subscribe()' statement that's supposed to kick everything off?
        // wait, this returns an Observable; subscribe elsewhere(?)--should be OK
    }
    
    void clearScanSubscription( ) {
        scanSubscription = null;
    }
    
    // Handling an error in the initial scan for Bluetooth device (e.g., Bluetooth not enabled)
    void handleScanError( Throwable scanError ) {
        if (scanError instanceof BleScanException ) {
            System.err.println( "Test Scan Exception: " + scanError.getMessage() );
        }
    }
    
    void connectToAeroscope( RxBleDevice theScope ) {
        System.out.println( "Entering connectToAeroscope"); // Prints if no filter in scan for services
        assertEquals( theScope.getName(), deviceName );
        assertEquals( theScope.getMacAddress(), macAddress );
        scanSubscription.unsubscribe();
        connectSubscription = theScope
                .establishConnection( appContext, true ) // returns (I think) Observable<RxBleConnection>
                .doOnSubscribe( () -> System.out.println( "doOnSubscribe in connectToAeroscope" ) ) // Nothing!
                .subscribe( connectionTestSubscriber );  // you're nobody until somebody subscribes to you
    
        connectionTestSubscriber.awaitTerminalEvent();   // wait until the Observable finishes
        connectionTestSubscriber.assertCompleted();
        connectionTestSubscriber.assertNoErrors();
        connectionTestSubscriber.assertValueCount( 1 );  // verify that only 1 item was emitted
        connectionTestSubscriber.unsubscribe();
    
        //assertThat( connectionTestSubscriber.getOnNextEvents(), hasItem( <item>) );
    }
    
    /* Use:
    asScanSubscription = scanForAeroscope() // returns 1-item Observable<RxBleDevice>
                            .subscribe(     // subscribe to it
                                this::connectToAeroscope, // argument is RxBleDevice (just 1)
                                this::handleScanError );  // argument is Throwable

    * */
    
    @Test // added by RL--works -- also OK after switching from Jack to Retrolambda
    public void connect_OK() throws Exception {
        
        System.out.println( "Entering Test connect_OK" ); // Prints
        
        scanSubscription = scanForAeroscope()     // returns 1-item Observable<RxBleDevice>
                .doOnSubscribe( () -> System.out.println( "doOnSubscribe in connect_OK") ) // Prints, so must be subscribing OK
                .doOnNext( item -> System.out.println("doOnNext in Test connect_OK item: " + item.toString() ) ) // Doesn't print!
                .doOnUnsubscribe( () -> System.out.println( "doOnUnsubscribe in connect_OK") ) //
                .subscribe(                       // subscribe to it
                        this::connectToAeroscope, // argument is RxBleDevice (just 1)
                        this::handleScanError )   // argument is Throwable
        ;  //
        
        scanSubscription.unsubscribe();
    }
    
    
    
    @Test // added by RL--unfinished but runs w/o error
    public void subscribeToData() throws Exception {
    
        scanSubscription = scanForAeroscope()     // returns 1-item Observable<RxBleDevice>
                .subscribe(                       // subscribe to it
                        this::connectForData,     // argument is RxBleDevice (just 1)
                        this::handleScanError );  // argument is Throwable
        scanSubscription.unsubscribe();
        
        
        
    }
    
    void connectForData( RxBleDevice theScope ) {
        
        dataTestSubscription = theScope
                .establishConnection( appContext, false ) // returns Observable<RxBleConnection>
                // setupNotification returns Observable emitting another Observable<byte[]> when the notification setup is complete
                .flatMap( rxBleConnection -> rxBleConnection.setupNotification( asDataCharId ) ) // returns Observable<Observable<byte[]>>
                .doOnNext( notificationObservable -> System.err.println( "Data notification has been set up") )
                .flatMap( notificationObservable -> notificationObservable ) // "flatten" this into just an Observable<byte[]>
                .subscribe( dataTestSubscriber ); // a TestSubscriber<byte[]>
        
        assertEquals( theScope.getName(), deviceName );
        assertEquals( theScope.getMacAddress(), macAddress );
        
    
        dataTestSubscriber.assertCompleted();
        dataTestSubscriber.assertNoErrors();
        dataTestSubscriber.assertValueCount( 1 );
        dataTestSubscriber.unsubscribe();
    
    
    }
    
    
    class AsConnectionSubscriber extends Subscriber<com.polidea.rxandroidble.RxBleConnection> {
    
        @Override // passed the (1) connection object
        public void onNext( com.polidea.rxandroidble.RxBleConnection rxBleConnection ) {
            System.out.println( "onNext of asConnectionSubscriber called" ); // why is nothing printed?
            //System.err.println( "onNext of asConnectionSubscriber called" );
            //Log.d( "AsConnectionSubscriber", "onNext of asConnectionSubscriber called" );
        }
        @Override
        public void onCompleted( ) {
            System.out.println( "onCompleted of asConnectionSubscriber called" );
        }
        @Override
        public void onError( Throwable theError ) {
            System.out.println( "onError of asConnectionSubscriber called" );
        }
    }
    
}