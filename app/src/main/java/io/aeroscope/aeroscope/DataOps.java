package io.aeroscope.aeroscope;

import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;

import rx.schedulers.Timestamped;

import static io.aeroscope.aeroscope.AeroscopeConstants.MAX_FRAME_SIZE;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_HEADER_COF;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_HEADER_SOF_16;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_HEADER_SOF_256;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_HEADER_SOF_4096;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_HEADER_SOF_512;
import static io.aeroscope.aeroscope.AeroscopeConstants.PACKET_SIZE;

/**
 * Created on 2017-02-26.
 */

// class for operations on data packets and frames
class DataOps {
    
    private final static String LOG_TAG = "DataOps                ";           // 23 characters
    private static ArrayList<Timestamped<byte[]>> packetBufUnderConstruction;  // holds accumulated packets for addPacket()
    
    // Constructor
    DataOps() {
        packetBufUnderConstruction = new ArrayList<>( MAX_FRAME_SIZE / PACKET_SIZE + 1 );
    }
    
    // Class for assembling Aeroscope Data Frames
    // TODO: this is the canonical Frame object; get rid of any others
    static class DataFrame {
        
        static long nextSeqNo = -1L;  // frame sequence no. counter
        
        // instance fields (we may wind up omitting some)
        // ** means 'set by constructor'; **** means set by pBuf2dFrame
        long sequenceNo;     // ** frame sequence number (starts at 0)
        long firstTimeStamp; // **** timestamp from first packet in the frame (milliseconds)
        long lastTimeStamp;  // **** timestamp from last packet in the frame
        int expectedLength;  // ** size of frame as specified by header byte
        int actualLength;    // **** # bytes actually received in frame
        boolean complete;    // **** complete, syntactically correct (only valid when completed or broken) (?)
        byte header;         // **** first byte of first packet in a frame // why do we need? TODO
        byte subtrigger;     // **** 2nd byte of first packet TODO: find out what we do with this
        byte[] data;         // **** frame data (excluding header & subtrigger, possibly including end padding)
        // considered a ByteBuffer but don't think it offers any advantages and prob slows things down
        
        // Constructors
        DataFrame( int size ) {
            sequenceNo = ++nextSeqNo;             // starts at 0
            firstTimeStamp = lastTimeStamp = -1L; // -1 for undefined
            expectedLength = size;                // data size value specified by header byte (may be changed by events)
            actualLength = 0;                     // running count of bytes received into frame
            complete = false;                     // set true when done receiving well-formed frame
            data = new byte[size];                // Java allows array size 0
            Log.d( LOG_TAG, "Frame #" + sequenceNo + " constructed" );
        }
    
        DataFrame() {
            this( 0 );
        }                // no-arg constructor starts data at zero-length
        
        byte[] getData() {
            return data;
        }
    
        long getSequenceNo() {
            return sequenceNo;
        }
    
        long getFirstTimeStamp() {
            return firstTimeStamp;
        }
    
        long getLastTimeStamp() {
            return lastTimeStamp;
        }
    
        int getExpectedLength() {
            return expectedLength;
        }
    
        int getActualLength() {
            return actualLength;
        }
    
        boolean isComplete() {
            return complete;
        }
    
        byte getHeader() {
            return header;
        }
    
        byte getSubtrigger() {
            return subtrigger;
        }
    
        int getTransmissionTimeMillis() {
            return ( int ) (lastTimeStamp - firstTimeStamp);
        } // ms to receive packets
    } // DataFrame object
    
    
    // OK, this is looking like .scan!  packetBuffer Func2( packetBuffer, packet ). It accumulates!
    // If we haven't completed a frame, return null
    // It's passed the buffer in its current state, along with a new packet. Returns the completed buffer (or null if not complete)
    static ArrayList<Timestamped<byte[]>> addPacket( ArrayList<Timestamped<byte[]>> buffer, Timestamped<byte[]> packet ) {
        if( packet.getValue()[0] == PACKET_HEADER_COF ) {    // this is a continuation packet
            packetBufUnderConstruction.add( packet );
            return ( ArrayList<Timestamped<byte[]>> ) null;  // cast to return type (may not be necessary)
        } else {                                             // this is start of a new frame
            ArrayList<Timestamped<byte[]>> returnedPacketBuf = new ArrayList<>( packetBufUnderConstruction ); // copy it
            packetBufUnderConstruction.clear( );             // empty it for next frame
            packetBufUnderConstruction.add( packet );        // add first packet of new frame
            return returnedPacketBuf;                        // and return previous buffer
        }
    } // addPacket
    
    
    // Function that maps a completed packet buffer to a frame
    static DataFrame pBuf2dFrame( ArrayList<Timestamped<byte[]>> pBuf ) {
        
        int frameSize;
        Iterator<Timestamped<byte[]>> packetIterator = pBuf.iterator();
        Timestamped<byte[]> currentPacket = packetIterator.next();      // get first packet
        byte[] packetPayload = currentPacket.getValue( );            // unwrap the Timestamp
        long packetTimestamp = currentPacket.getTimestampMillis( );
        
        byte packetHeader = packetPayload[0];
        byte subtrigger = packetPayload[1];
        
        switch( packetHeader ) {
            case PACKET_HEADER_SOF_16:
                frameSize = 16;
                break;
            case PACKET_HEADER_SOF_256:
                frameSize = 256;
                break;
            case PACKET_HEADER_SOF_512:
                frameSize = 512;
                break;
            case PACKET_HEADER_SOF_4096:
                frameSize = 4096;
                break;
            default:
                throw new IllegalArgumentException( "Illegal value in Start of Frame header: " + packetHeader );
        }
        
        DataFrame newFrame = new DataFrame( frameSize );
        newFrame.firstTimeStamp = packetTimestamp;
        newFrame.lastTimeStamp = packetTimestamp; // could be a 1-packet frame
        newFrame.header = packetHeader;
        newFrame.subtrigger = subtrigger;
        
        for( int i = 2; i < packetPayload.length; i++ ) { // data in first packet starts at index 2
            newFrame.data[i-2] = packetPayload[i];
            if( ++newFrame.actualLength >= newFrame.expectedLength ) break;  // done
        }
        // done with first packet, do rest (if any)
        gotAllBytes:
        while( packetIterator.hasNext() ) {
            currentPacket = packetIterator.next();   // get next packet data
            newFrame.lastTimeStamp = currentPacket.getTimestampMillis();
            packetPayload = currentPacket.getValue();
            for( int i = 1; i < packetPayload.length; i++ ) { // data in subsequent packets starts at index 1
                newFrame.data[newFrame.actualLength] = packetPayload[i];
                if( ++newFrame.actualLength >= newFrame.expectedLength ) break gotAllBytes;  // done
            }
            
        } // while there are more packets
        
        // did we get the expected number of bytes?
        if( newFrame.actualLength == newFrame.expectedLength ) newFrame.complete = true;
        
        return newFrame;
    } // pBuf2dFrame
} // class DataOps


