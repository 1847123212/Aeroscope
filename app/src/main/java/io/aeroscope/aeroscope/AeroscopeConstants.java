package io.aeroscope.aeroscope;

import java.nio.charset.StandardCharsets;
import java.util.UUID;


/**
 * Created on 2017-02-25.
 */

final class AeroscopeConstants {

    /************************************ APPLICATION PARAMETERS **************************************/

    final static long SCAN_TIME = 60L;                  // limit BLE scan to this many seconds (if used)
    final static int MAX_AEROSCOPES = 2;                // limit BLE scan to this many scopes (if used)
    final static boolean CONNECT_ON_DISCOVERY = false;  // should we automatically connect to discovered Aeroscopes?
    final static long FRAMESUBSCRIBER_SLEEP_MS = 20L;   // sleep time to wait for a new Data frame TODO: eliminate?
    final static int IO_HISTORY_LENGTH = 10;
    final static boolean MSG_FROM_AEROSCOPE = false;    // describes origin of packets in history
    final static boolean CMD_TO_AEROSCOPE = true;       // describes origin of packets in history
    final static long HEARTBEAT_SECOND = 6;



    /*************************************** BLUETOOTH SECTION ****************************************/

    // Parameter for connecting to a BLE device
    final static boolean AUTO_CONNECT = true;
    final static boolean NO_AUTO_CONNECT = false;


    // UUID section
    // The standard Bluetooth Base UUID; replace the first group with "0000SSSS" for 16-bit or "LLLLLLLL" for 32-bit abbreviated values
    private static final String BASE_UUID_STRING = "0000XXXX-0000-1000-8000-00805F9B34FB"; // just for reference
    private static final String BASE_UUID_HEAD = "0000"; // for 16-bit UUIDs we replace digits 4-7 of the base string with the value
    private static final String BASE_UUID_TAIL = "-0000-1000-8000-00805F9B34FB"; //

    private static final String CLIENT_CHAR_CONFIG_STRING = "2902"; // GATT standard Client Characteristic Configuration UUID
    static final UUID clientCharConfigID = UUID.fromString( BASE_UUID_HEAD + CLIENT_CHAR_CONFIG_STRING + BASE_UUID_TAIL ); // for notifications/indications
    static final byte[] asDisableNotifications = { 0, 0 }; // 2 bytes of 0 = 16-bit Descriptor
    static final byte[] asEnableNotifications  = { 1, 0 }; // 2 bytes of 0 = 16-bit Descriptor ASSUMES LITTLE-ENDIAN

    // Aeroscope Service UUID
    private static final String AEROSCOPE_UUID_HEAD  =  "F954"; // next 4 characters are the short-form UUID
    private static final String AEROSCOPE_UUID_TAIL  =  "-91B3-BD9A-F077-80F2A6E57D00";
    private static final String asServiceIdString    =  AEROSCOPE_UUID_HEAD + "1234" + AEROSCOPE_UUID_TAIL; // Aeroscope Service UUID
    private static final UUID asServiceId = UUID.fromString( asServiceIdString ); // a Service is a collection of Characteristics
    static final UUID[] asServiceIdArray = { asServiceId }; // vararg argument to scanBleDevices is implicitly an array

    // Aeroscope Characteristic UUIDs (apparently verified by scanning the device)
    private static final String asDataCharIdString   =  BASE_UUID_HEAD + "1235" + BASE_UUID_TAIL; // Aeroscope Data UUID
    private static final String asInputCharIdString  =  BASE_UUID_HEAD + "1236" + BASE_UUID_TAIL; // Aeroscope Input UUID
    private static final String asStateCharIdString  =  BASE_UUID_HEAD + "1237" + BASE_UUID_TAIL; // Aeroscope State UUID
    private static final String asOutputCharIdString =  BASE_UUID_HEAD + "1239" + BASE_UUID_TAIL; // Aeroscope Output UUID
    static final UUID asDataCharId =   UUID.fromString( asDataCharIdString );
    static final UUID asInputCharId =  UUID.fromString( asInputCharIdString );
    static final UUID asStateCharId =  UUID.fromString( asStateCharIdString );
    static final UUID asOutputCharId = UUID.fromString( asOutputCharIdString );




    /**************************************** AEROSCOPE PROBE *****************************************/

    // Misc. parameters
    final static int MAX_FRAME_SIZE = 4096;

    // Default values of the first 20 FPGA registers, writable as a block
    final static byte[] DEFAULT_FPGA_REGISTERS = { // unallocated values are set to 0x00
            (byte)0x03, (byte)0x80, (byte)0xC5, (byte)0xE0, (byte)0x08,
            (byte)0x00, (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x07,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00 };
    // Register names with their array indexes
    final static int TRIGGER_CTRL = 0;
    final static int TRIGGER_PT = 1;
    final static int PLL_CTRL = 2;              // not currently used
    final static int FRONT_END_CTRL = 3;
    final static int TRIGGER_XPOS_HI = 4;       // bits 11:8
    final static int TRIGGER_XPOS_LO = 5;       // bits 7:0
    final static int WRITE_SAMPLE_DEPTH = 6;    // bits 1:0
    final static int READ_SAMPLE_DEPTH = 7;     // bits 1:0
    final static int SAMPLER_CTRL = 8;
    final static int READ_START_ADDRS_HI = 14;  // bits 11:8
    final static int READ_START_ADDRS_LO = 15;  // bits 7:0
    final static int DAC_CTRL_HI = 18;          // bits 15:8
    final static int DAC_CTRL_LO = 19;          // bits 7:0

    // Values to be written to FPGA register 8 (SAMPLER_CTRL) to set Time per Division
    final static byte DIVISOR_500ns      = (byte)0x08;  // 1 x 10^0  = 1
    final static byte DIVISOR_1us        = (byte)0x10;  // 2 x 10^0  = 2
    final static byte DIVISOR_2us        = (byte)0x20;  // 4 x 10^0  = 4
    final static byte DIVISOR_5us        = (byte)0x09;  // 1 x 10^1  = 10
    final static byte DIVISOR_10us       = (byte)0x11;  // 2 x 10^1  = 20
    final static byte DIVISOR_20us       = (byte)0x21;  // 4 x 10^1  = 40
    final static byte DIVISOR_50us       = (byte)0x0A;  // 1 x 10^2  = 100
    final static byte DIVISOR_100us      = (byte)0x12;  // 2 x 10^2  = 200
    final static byte DIVISOR_200us      = (byte)0x22;  // 4 x 10^2  = 400
    final static byte DIVISOR_500us      = (byte)0x0B;  // 1 x 10^3  = 1000
    final static byte DIVISOR_1ms        = (byte)0x13;  // 2 x 10^3  = 2000
    final static byte DIVISOR_2ms        = (byte)0x23;  // 4 x 10^3  = 4000
    final static byte DIVISOR_5ms        = (byte)0x0C;  // 1 x 10^4  = 10000
    final static byte DIVISOR_10ms       = (byte)0x14;  // 2 x 10^4  = 20000
    final static byte DIVISOR_20ms       = (byte)0x24;  // 4 x 10^4  = 40000
    final static byte DIVISOR_50ms       = (byte)0x0D;  // 1 x 10^5  = 100000
    final static byte DIVISOR_100ms      = (byte)0x15;  // 2 x 10^5  = 200000
    // final static byte DIVISOR_200ms      = (byte)0x25;  // 4 x 10^5  = 400000 TODO: not in specs; should it be?
    final static byte DIVISOR_500ms_ROLL = (byte)0xE7;  // special
    final static byte DIVISOR_1s_ROLL    = (byte)0xEF;  // special
    final static byte DIVISOR_2s_ROLL    = (byte)0xF7;  // special
    final static byte DIVISOR_5s_ROLL    = (byte)0xFF;  // special

    final static byte[] SAMPLER_CTRL_BYTE  // indexed by position in list
            = { DIVISOR_500ns, DIVISOR_1us, DIVISOR_2us, DIVISOR_5us, DIVISOR_10us, DIVISOR_20us,
            DIVISOR_50us, DIVISOR_100us, DIVISOR_200us, DIVISOR_500us, DIVISOR_1ms, DIVISOR_2ms,
            DIVISOR_5ms, DIVISOR_10ms, DIVISOR_20ms, DIVISOR_50ms, DIVISOR_100ms,
            DIVISOR_500ms_ROLL, DIVISOR_1s_ROLL, DIVISOR_2s_ROLL, DIVISOR_5s_ROLL };

    //final static java.time.Duration _500ns = Duration.of

    // Values to be written to FPGA register 3 (FRONT_END_CTRL) to set Volts per Division
    final static byte FRONT_END_100mV    = (byte)0x60;
    final static byte FRONT_END_200mV    = (byte)0x41;
    final static byte FRONT_END_500mV    = (byte)0x20;
    final static byte FRONT_END_1V       = (byte)0x22;
    final static byte FRONT_END_2V       = (byte)0x03;
    final static byte FRONT_END_5V       = (byte)0x04;
    final static byte FRONT_END_10V      = (byte)0x05;

    final static byte[] FRONT_END_CTRL_BYTE  // indexed by position in list
            = { FRONT_END_100mV, FRONT_END_200mV, FRONT_END_500mV,
            FRONT_END_1V, FRONT_END_2V, FRONT_END_5V, FRONT_END_10V };


    // 8-bit numeric values for battery state ranges (low-order byte of battery voltage register)
    // (bit 15 = charger present; bit 14 = now charging)
    final static byte BATT_FULL_MAX     = (byte)255;
    final static byte BATT_FULL_MIN     = (byte)238;
    final static byte BATT_MED_MAX      = (byte)237;
    final static byte BATT_MED_MIN      = (byte)226;
    final static byte BATT_LOW_MAX      = (byte)225;
    final static byte BATT_LOW_MIN      = (byte)220;
    final static byte BATT_CRITICAL_MAX = (byte)219;
    final static byte BATT_CRITICAL_MIN = (byte)212;
    final static byte BATT_SHUTDOWN     = (byte)211;

    final static int BATTERY_CRITICAL   = 0; // because enums are discouraged
    final static int BATTERY_LOW        = 1;
    final static int BATTERY_MEDIUM     = 2;
    final static int BATTERY_FULL       = 3;


    // Aeroscope commands (written to Input Characteristic)
    // RxAndroidBle wants them all to be byte[20]
    final static byte[] RUN_MODE             = "R\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] STOP_MODE            = "S\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] STOP_CAL_IMMED       = "CI\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] STOP_CLEAR_CAL       = "CC\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] GET_SINGLE_FRAME     = "F\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] GET_FULL_FRAME       = "L\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] STOP_SUSPEND_FPGA    = "ZS\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );   // not implemented yet
    final static byte[] DEEP_SLEEP           = "ZZ\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );   // not implemented yet
    final static byte[] WRITE_FPGA_REG       = "W\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );    // followed by byte of address, byte of data
    final static byte[] SET_NAME             = "N\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );    // not impl yet; presumably followed by a string
    final static byte[] SEND_TELEMETRY_IMMED = "QTI\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] REFRESH_TELEMETRY    = "QTR\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] SEND_CACHED_VERSION  = "QVI\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] REFRESH_VERSION      = "QVR\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );
    final static byte[] FETCH_ERROR_LOG      = "QE\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes( StandardCharsets.US_ASCII );  // not implemented yet

    final static byte[][] COMMAND_ARRAY      = { RUN_MODE, STOP_MODE, STOP_CAL_IMMED, STOP_CLEAR_CAL, GET_SINGLE_FRAME,
            GET_FULL_FRAME, STOP_SUSPEND_FPGA, DEEP_SLEEP, WRITE_FPGA_REG, SET_NAME, SEND_TELEMETRY_IMMED,
            REFRESH_TELEMETRY, SEND_CACHED_VERSION, REFRESH_VERSION, FETCH_ERROR_LOG};

    // Aeroscope messages (received from Output Characteristic)
    // These should probably be byte, not byte[]
    final static byte TELEMETRY            = (byte) 'T';   // followed by Battery Voltage (H), Battery Voltage (L),
    // Temperature (H), Temperature (L), Acceleration (H), Acceleration (L)
    final static byte VERSION              = (byte) 'V';   // followed by Hardware ID, FPGA Rev, Firmware Rev, Serial No. (4 bytes, big first, unimpl)

    final static byte ERROR                = (byte) 'E';   // first byte of Error message preamble
    final static byte LOG                  = (byte) 'L';   // second byte of Error preamble followed by ??? (format TBD?)
    final static byte IMMEDIATE            = (byte) 'I';   // second byte of Error preamble followed by ??? (format TBD?)

    final static byte CALIBRATE            = (byte) 'C';   // first byte of Calibrate preamble
    final static byte QUICK                = (byte) 'A';   // second byte of Calibrate preamble followed by Cal1 (H), Cal1 (L),
    // Cal2 (H), Cal2 (L), Cal3 (H), Cal3 (L), Cal4 (H), Cal4 (L)
    final static byte FORCE                = (byte) 'B';   // second byte of Calibrate preamble followed by 10V (H), 10V (L),
    // 5V (H), 5V (L), 2V (H), 2V (L), 1V (H), 1V (L),
    // 500mV (H), 500mV (L), 200mV (H), 200mV (L), 100mV (H), 100mV (L),
    // 50mV (H), 50mV (L), 20mV (H), 20mV (L) (18 data bytes)

    final static byte DEBUG_PRINT          = (byte) 'D';   // followed by null-terminated string (ASCII)

    final static byte BUTTON               = (byte) 'B';   // first byte of Button preamble
    final static byte DOWN                 = (byte) 'D';   // second byte of Button preamble, optionally followed by
    final static byte UP                   = (byte) 'U';   // second byte of Button preamble, followed by T for time down (future?)
    final static byte TIME_DOWN            = (byte) 'T';   // third byte of Button preamble, followed by Time (H), Time (L) (future?)

    // Packet Header Values
    static final byte PACKET_HEADER_COF      = (byte) 0;      // Continuation of Frame
    static final byte PACKET_HEADER_SOF_16   = (byte) 1;      // Start of 16-byte Frame
    static final byte PACKET_HEADER_SOF_256  = (byte) 2;      // Start of 256-byte Frame
    static final byte PACKET_HEADER_SOF_512  = (byte) 3;      // Start of 512-byte Frame
    static final byte PACKET_HEADER_SOF_4096 = (byte) 4;      // Start of 4K-byte Frame
    static final int  PACKET_SIZE = 20;                       // BLE standard, eh?


}
