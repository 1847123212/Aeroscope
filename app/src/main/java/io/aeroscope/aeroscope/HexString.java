package io.aeroscope.aeroscope;

/**
 * Created on 2017-03-04.
 */

/*
* Utility methods for converting between hex nmbers as strings and as arrays of bytes
* (from RxAndroidBle sample app)
* */

public class HexString {
    
    private HexString() {
        // Utility class (all static methods, no need for a public constructor)
    }
    
    private final static char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    
    // Takes an array of bytes, returns a String of hex digits
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
    
    // Takes a String of hex digits, returns an array of bytes
    // As originally written, will produce array index out of bounds if hexRepresentation has an odd length
    public static byte[] hexToBytes(String hexRepresentation) {
        int len = hexRepresentation.length();
        if( len % 2 != 0 ) throw new IllegalArgumentException( "hexToBytes requires an even-length String parameter" ); // my fix
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexRepresentation.charAt(i), 16) << 4)
                    + Character.digit(hexRepresentation.charAt(i + 1), 16));
        }
        return data;
    }
}
