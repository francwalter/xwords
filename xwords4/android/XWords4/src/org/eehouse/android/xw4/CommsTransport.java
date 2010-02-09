/* -*- compile-command: "cd ../../../../../; ant install"; -*- */

package org.eehouse.android.xw4;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.ClosedChannelException;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.util.Vector;
import java.util.Iterator;
import junit.framework.Assert;
import android.telephony.SmsManager;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.Context;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.JNIThread.*;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;

public class CommsTransport extends Thread implements TransportProcs {
    private Selector m_selector;
    private SocketChannel m_socketChannel;
    private int m_jniGamePtr;
    private boolean m_running = false;
    private CommsAddrRec m_addr;
    private JNIThread m_jniThread;
    private boolean m_done = false;

    private Vector<ByteBuffer> m_buffersOut;
    private ByteBuffer m_bytesOut;
    private ByteBuffer m_bytesIn;

    private Context m_context;
    

    // assembling inbound packet
    private byte[] m_packetIn;
    private int m_haveLen = -1;

    public CommsTransport( int jniGamePtr, Context context )
    {
        m_jniGamePtr = jniGamePtr;
        m_context = context;
        m_buffersOut = new Vector<ByteBuffer>();
        m_bytesIn = ByteBuffer.allocate( 2048 );
    }
    
    public void setReceiver( JNIThread jnit )
    {
        m_jniThread = jnit;
    }

    public void waitToStop()
    {
        m_done = true;
        if ( null != m_selector ) {
            m_selector.wakeup();
        }
        if ( m_running ) {      // synchronized this?  Or use Thread method
            try {
                join(100);          // wait up to 1/10 second
            } catch ( java.lang.InterruptedException ie ) {
                Utils.logf( "got InterruptedException: " + ie.toString() );
            }
        }
    }

    @Override
    public void run() 
    {
        try {
            m_selector = Selector.open();
            m_socketChannel = SocketChannel.open();
            m_socketChannel.configureBlocking( false );
            InetSocketAddress isa
                = new InetSocketAddress( m_addr.ip_relay_hostName, 
                                         m_addr.ip_relay_port );
            m_socketChannel.connect( isa );

            loop();

            m_socketChannel.close();
        } catch ( java.io.IOException ioe ) {
            Utils.logf( ioe.toString() );
        }

    }

    private void loop()
    {
        while ( !m_done ) {
            try {
                int ops = figureOps();
                Utils.logf( "calling with ops=" + ops );
                m_socketChannel.register( m_selector, ops );
                m_selector.select();
            } catch ( ClosedChannelException cce ) {
                Utils.logf( "exiting: " + cce.toString() );
                break;
            } catch ( java.io.IOException ioe ) {
                Utils.logf( "exiting: " + ioe.toString() );
                Utils.logf( ioe.toString() );
                break;
            }

            Iterator iter = m_selector.selectedKeys().iterator();
            while ( iter.hasNext() ) {
                SelectionKey key = (SelectionKey)iter.next();
                SocketChannel channel = (SocketChannel)key.channel();
                iter.remove();
                try { 
                    if (key.isValid() && key.isConnectable()) {
                        Utils.logf( "socket is connectable" );
                        if ( !channel.finishConnect() ) {
                            key.cancel(); 
                        }
                    }
                    if (key.isValid() && key.isReadable()) {
                        m_bytesIn.clear(); // will wipe any pending data!
                        Utils.logf( "socket is readable; buffer has space for "
                                    + m_bytesIn.remaining() );
                        int nRead = channel.read( m_bytesIn );
                        if ( nRead == -1 ) {
                            channel.close();
                        } else {
                            addIncoming();
                        }
                    }
                    if (key.isValid() && key.isWritable()) {
                        Utils.logf( "socket is writable" );
                        getOut();
                        if ( null != m_bytesOut ) {
                            int nWritten = channel.write( m_bytesOut );
                            Utils.logf( "wrote " + nWritten + " bytes" );
                        }
                    }
                } catch ( java.io.IOException ioe ) {
                    key.cancel(); 
                }
            }
        }
    } // loop

    private synchronized void putOut( final byte[] buf )
    {
        int len = buf.length;
        ByteBuffer netbuf = ByteBuffer.allocate( len + 2 );
        Utils.logf( "allocated outbound buffer of size " + len );
        netbuf.putShort( (short)len );
        netbuf.put( buf );
        m_buffersOut.add( netbuf );
        Assert.assertEquals( netbuf.remaining(), 0 );

        if ( null != m_selector ) {
            m_selector.wakeup();    // tell it it's got some writing to do
        }
    }

    private synchronized void getOut()
    {
        if ( null != m_bytesOut && m_bytesOut.remaining() == 0 ) {
            m_bytesOut = null;
        }

        if ( null == m_bytesOut && m_buffersOut.size() > 0 ) {
            m_bytesOut = m_buffersOut.remove(0);
            m_bytesOut.flip();
        }
    }

    private synchronized int figureOps() {
        int ops;
        if ( m_socketChannel.isConnected() ) {
            ops = SelectionKey.OP_READ;
            if ( (null != m_bytesOut && m_bytesOut.hasRemaining())
                 || m_buffersOut.size() > 0 ) {
                ops |= SelectionKey.OP_WRITE;
            }
        } else {
            ops = SelectionKey.OP_CONNECT;
        }
        return ops;
    }

    private void addIncoming( )
    {
        m_bytesIn.flip();
        Utils.logf( "got " + m_bytesIn.remaining() + " bytes" );
        
        for ( ; ; ) {
            int len = m_bytesIn.remaining();
            Utils.logf( "addIncoming(): remaining: " + len );
            if ( len <= 0 ) {
                break;
            }

            if ( null == m_packetIn ) { // we're not mid-packet
                Assert.assertTrue( len > 1 ); // tell me if I see this case
                if ( len == 1 ) {       // half a length byte...
                    break;              // can I leave it in the buffer?
                } else {                
                    m_packetIn = new byte[m_bytesIn.getShort()];
                    m_haveLen = 0;
                }
            } else {                    // we're mid-packet
                int wantLen = m_packetIn.length - m_haveLen;
                if ( wantLen > len ) {
                    wantLen = len;
                }
                m_bytesIn.get( m_packetIn, m_haveLen, wantLen );
                m_haveLen += wantLen;
                if ( m_haveLen == m_packetIn.length ) {
                    // send completed packet
                    Utils.logf( "got full packet!!" );
                    m_jniThread.handle( JNICmd.CMD_RECEIVE, m_packetIn );
                    m_packetIn = null;
                }
            }
        }
    }

    // TransportProcs interface
    public int transportSend( byte[] buf, final CommsAddrRec faddr )
    {
        int nSent = -1;
        Utils.logf( "CommsTransport::transportSend" );

        if ( null == m_addr ) {
            if ( null == faddr ) {
                m_addr = new CommsAddrRec();
                XwJNI.comms_getAddr( m_jniGamePtr, m_addr );
            } else {
                m_addr = new CommsAddrRec( faddr );
            }
        }

        switch ( m_addr.conType ) {
        case COMMS_CONN_RELAY:
            putOut( buf );      // add to queue
            if ( !m_running ) {
                m_running = true;
                start();
            }
            nSent = buf.length;
            break;
        case COMMS_CONN_SMS:
            Utils.logf( "sending via sms to " + m_addr.sms_phone + ":127" );

            try {
                Intent intent = new Intent( m_context, StatusReceiver.class);
                PendingIntent pi
                    = PendingIntent.getBroadcast( m_context, 0,
                                                  intent, 0 );
                // SmsManager.getDefault().sendTextMessage( m_addr.sms_phone,
                //                                          null, "Hello world",
                //                                          pi, pi );
                SmsManager.getDefault().sendDataMessage( m_addr.sms_phone, 
                                                         (String)null, (short)127,
                                                         //(short)m_addr.sms_port, 
                                                         buf, pi, pi );
                Utils.logf( "called sendDataMessage" );
                nSent = buf.length;
            } catch ( java.lang.IllegalArgumentException iae ) {
                Utils.logf( iae.toString() );
            }
            break;
        case COMMS_CONN_BT:
            break;
        }

        return nSent;
    } 

    public void relayStatus( int newState )
    {
    }

    public void relayConnd( boolean allHere, int nMissing )
    {
    }

    public void relayErrorProc( XWRELAY_ERROR relayErr )
    {
    }

}
