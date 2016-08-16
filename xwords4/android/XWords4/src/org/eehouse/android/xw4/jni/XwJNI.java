/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009-2010 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4.jni;

import android.graphics.Rect;

import junit.framework.Assert;

import org.eehouse.android.xw4.DbgUtils;
import org.eehouse.android.xw4.NetLaunchInfo;
import org.eehouse.android.xw4.Utils;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;

// Collection of native methods and a bit of state
public class XwJNI {

    public static class GamePtr {
        private int m_ptr = 0;
        private int m_refCount = 0;
        private long m_rowid;

        private GamePtr( int ptr, long rowid ) {
            m_ptr = ptr;
            m_rowid = rowid;
        }

        public synchronized int ptr()
        {
            Assert.assertTrue( 0 != m_ptr );
            return m_ptr;
        }

        public synchronized GamePtr retain()
        {
            ++m_refCount;
            DbgUtils.logdf( "GamePtr.retain(this=%H, rowid=%d): refCount now %d",
                            this, m_rowid, m_refCount );
            return this;
        }

        // Force (via an assert in finalize() below) that this is called. It's
        // better if jni stuff isn't being done on the finalizer thread
        public synchronized void release()
        {
            --m_refCount;
            DbgUtils.logdf( "GamePtr.release(this=%H, rowid=%d): refCount now %d",
                            this, m_rowid, m_refCount );
            if ( 0 == m_refCount ) {
                if ( 0 != m_ptr ) {
                    if ( !haveEnv( getJNI().m_ptr ) ) {
                        Assert.fail();
                    }
                    game_dispose( this ); // will crash if haveEnv fails
                    m_ptr = 0;
                }
            }
        }

        // @Override
        public void finalize() throws java.lang.Throwable
        {
            Assert.assertTrue( 0 == m_ptr );
            super.finalize();
        }
    }

    private static XwJNI s_JNI = null;
    private static XwJNI getJNI()
    {
        if ( null == s_JNI ) {
            s_JNI = new XwJNI();
        }
        return s_JNI;
    }

    private int m_ptr;
    private XwJNI()
    {
        m_ptr = initGlobals();
    }

    public static void cleanGlobals()
    {
        synchronized( XwJNI.class ) { // let's be safe here
            XwJNI jni = getJNI();
            cleanGlobals( jni.m_ptr );
            jni.m_ptr = 0;
        }
    }

    // @Override
    public void finalize() throws java.lang.Throwable
    {
        cleanGlobals( m_ptr );
        super.finalize();
    }

    // This needs to be called before the first attempt to use the
    // jni.
    static {
        System.loadLibrary("xwjni");
    }

    /* XW_TrayVisState enum */
    public static final int TRAY_HIDDEN = 0;
    public static final int TRAY_REVERSED = 1;
    public static final int TRAY_REVEALED = 2;

    // Methods not part of the common interface but necessitated by
    // how java/jni work (or perhaps my limited understanding of it.)

    // callback into jni from java when timer set here fires.
    public static native boolean timerFired( GamePtr gamePtr, int why,
                                             int when, int handle );

    // Stateless methods
    public static native byte[] gi_to_stream( CurGameInfo gi );
    public static native void gi_from_stream( CurGameInfo gi, byte[] stream );
    public static byte[] nliToStream( NetLaunchInfo nli )
    {
        nli.freezeAddrs();
        return nli_to_stream( nli );
    }
    private static native byte[] nli_to_stream( NetLaunchInfo nli );
    public static NetLaunchInfo nliFromStream( byte[] stream )
    {
        NetLaunchInfo nli = new NetLaunchInfo();
        nli_from_stream( nli, stream );
        nli.unfreezeAddrs();
        return nli;
    }
    private static native void nli_from_stream( NetLaunchInfo nli, byte[] stream );
    public static native void comms_getInitialAddr( CommsAddrRec addr,
                                                    String relayHost,
                                                    int relayPort );
    public static native String comms_getUUID();

    // Game methods
    private static GamePtr initJNI( long rowid )
    {
        int seed = Utils.nextRandomInt();
        String tag = String.format( "%d", rowid );
        int ptr = initJNI( getJNI().m_ptr, seed, tag );
        GamePtr result = 0 == ptr ? null : new GamePtr( ptr, rowid );
        return result;
    }

    public static synchronized GamePtr
        initFromStream( long rowid, byte[] stream, CurGameInfo gi,
                        String[] dictNames, byte[][] dictBytes,
                        String[] dictPaths, String langName,
                        UtilCtxt util, JNIUtils jniu, DrawCtx draw,
                        CommonPrefs cp, TransportProcs procs )

    {
        GamePtr gamePtr = initJNI( rowid );
        if ( game_makeFromStream( gamePtr, stream, gi, dictNames, dictBytes,
                                  dictPaths, langName, util, jniu, draw,
                                  cp, procs ) ) {
            gamePtr.retain();
        } else {
            gamePtr = null;
        }

        return gamePtr;
    }

    public static synchronized GamePtr
        initNew( CurGameInfo gi, String[] dictNames, byte[][] dictBytes,
                 String[] dictPaths, String langName, UtilCtxt util,
                 JNIUtils jniu, DrawCtx draw, CommonPrefs cp,
                 TransportProcs procs )
    {
        GamePtr gamePtr = initJNI( 0 );
        game_makeNewGame( gamePtr, gi, dictNames, dictBytes, dictPaths,
                          langName, util, jniu, draw, cp, procs );
        return gamePtr.retain();
    }

    // hack to allow cleanup of env owned by thread that doesn't open game
    public static void threadDone()
    {
        envDone( getJNI().m_ptr );
    }

    private static native void game_makeNewGame( GamePtr gamePtr,
                                                 CurGameInfo gi,
                                                 String[] dictNames,
                                                 byte[][] dictBytes,
                                                 String[] dictPaths,
                                                 String langName,
                                                 UtilCtxt util,
                                                 JNIUtils jniu,
                                                 DrawCtx draw, CommonPrefs cp,
                                                 TransportProcs procs );

    private static native boolean game_makeFromStream( GamePtr gamePtr,
                                                      byte[] stream,
                                                      CurGameInfo gi,
                                                      String[] dictNames,
                                                      byte[][] dictBytes,
                                                      String[] dictPaths,
                                                      String langName,
                                                      UtilCtxt util,
                                                      JNIUtils jniu,
                                                      DrawCtx draw,
                                                      CommonPrefs cp,
                                                      TransportProcs procs );

    public static native boolean game_receiveMessage( GamePtr gamePtr,
                                                      byte[] stream,
                                                      CommsAddrRec retAddr );
    public static native void game_summarize( GamePtr gamePtr, GameSummary summary );
    public static native byte[] game_saveToStream( GamePtr gamePtr,
                                                   CurGameInfo gi  );
    public static native void game_saveSucceeded( GamePtr gamePtr );
    public static native void game_getGi( GamePtr gamePtr, CurGameInfo gi );
    public static native void game_getState( GamePtr gamePtr,
                                             JNIThread.GameStateInfo gsi );
    public static native boolean game_hasComms( GamePtr gamePtr );

    // Keep for historical purposes.  But threading issues make it
    // impossible to implement this without a ton of work.
    // public static native boolean game_changeDict( int gamePtr, CurGameInfo gi,
    //                                               String dictName,
    //                                               byte[] dictBytes,
    //                                               String dictPath );
    private static native void game_dispose( GamePtr gamePtr );

    // Board methods
    public static native void board_setDraw( GamePtr gamePtr, DrawCtx draw );
    public static native void board_invalAll( GamePtr gamePtr );
    public static native boolean board_draw( GamePtr gamePtr );
    public static native void board_drawSnapshot( GamePtr gamePtr, DrawCtx draw,
                                                  int width, int height );

    // Only if COMMON_LAYOUT defined
    public static native void board_figureLayout( GamePtr gamePtr, CurGameInfo gi,
                                                  int left, int top, int width,
                                                  int height, int scorePct,
                                                  int trayPct, int scoreWidth,
                                                  int fontWidth, int fontHt,
                                                  boolean squareTiles,
                                                  BoardDims dims );
    // Only if COMMON_LAYOUT defined
    public static native void board_applyLayout( GamePtr gamePtr, BoardDims dims );

    // public static native void board_setPos( int gamePtr, int left, int top,
                                            // int width, int height,
                                            // int maxCellHt, boolean lefty );
    // public static native void board_setScoreboardLoc( int gamePtr, int left,
    //                                                   int top, int width,
    //                                                   int height,
    //                                                   boolean divideHorizontally );
    // public static native void board_setTrayLoc( int gamePtr, int left,
    //                                             int top, int width,
    //                                             int height, int minDividerWidth );
    // public static native void board_setTimerLoc( int gamePtr,
    //                                              int timerLeft, int timerTop,
    //                                              int timerWidth,
    //                                              int timerHeight );
    public static native boolean board_zoom( GamePtr gamePtr, int zoomBy,
                                             boolean[] canZoom );
    public static native boolean board_getActiveRect( GamePtr gamePtr, Rect rect,
                                                      int[] dims );

    public static native boolean board_handlePenDown( GamePtr gamePtr,
                                                      int xx, int yy,
                                                      boolean[] handled );
    public static native boolean board_handlePenMove( GamePtr gamePtr,
                                                      int xx, int yy );
    public static native boolean board_handlePenUp( GamePtr gamePtr,
                                                    int xx, int yy );
    public static native boolean board_containsPt( GamePtr gamePtr,
                                                   int xx, int yy );

    public static native boolean board_juggleTray( GamePtr gamePtr );
    public static native int board_getTrayVisState( GamePtr gamePtr );
    public static native boolean board_hideTray( GamePtr gamePtr );
    public static native boolean board_showTray( GamePtr gamePtr );
    public static native boolean board_toggle_showValues( GamePtr gamePtr );
    public static native boolean board_commitTurn( GamePtr gamePtr );
    public static native boolean board_flip( GamePtr gamePtr );
    public static native boolean board_replaceTiles( GamePtr gamePtr );
    public static native int board_getSelPlayer( GamePtr gamePtr );
    public static native boolean board_redoReplacedTiles( GamePtr gamePtr );
    public static native void board_resetEngine( GamePtr gamePtr );
    public static native boolean board_requestHint( GamePtr gamePtr,
                                                    boolean useTileLimits,
                                                    boolean goBackwards,
                                                    boolean[] workRemains );
    public static native boolean board_beginTrade( GamePtr gamePtr );
    public static native boolean board_endTrade( GamePtr gamePtr );

    public static native String board_formatRemainingTiles( GamePtr gamePtr );
    public static native void board_sendChat( GamePtr gamePtr, String msg );

    public enum XP_Key {
        XP_KEY_NONE,
        XP_CURSOR_KEY_DOWN,
        XP_CURSOR_KEY_ALTDOWN,
        XP_CURSOR_KEY_RIGHT,
        XP_CURSOR_KEY_ALTRIGHT,
        XP_CURSOR_KEY_UP,
        XP_CURSOR_KEY_ALTUP,
        XP_CURSOR_KEY_LEFT,
        XP_CURSOR_KEY_ALTLEFT,

        XP_CURSOR_KEY_DEL,
        XP_RAISEFOCUS_KEY,
        XP_RETURN_KEY,

        XP_KEY_LAST
    };
    public static native boolean board_handleKey( GamePtr gamePtr, XP_Key key,
                                                  boolean up, boolean[] handled );
    // public static native boolean board_handleKeyDown( XP_Key key,
    //                                                   boolean[] handled );
    // public static native boolean board_handleKeyRepeat( XP_Key key,
    //                                                     boolean[] handled );

    // Model
    public static native String model_writeGameHistory( GamePtr gamePtr,
                                                        boolean gameOver );
    public static native int model_getNMoves( GamePtr gamePtr );
    public static native int model_getNumTilesInTray( GamePtr gamePtr, int player );
    public static native void model_getPlayersLastScore( GamePtr gamePtr,
                                                         int player,
                                                         LastMoveInfo lmi );
    // Server
    public static native void server_reset( GamePtr gamePtr );
    public static native void server_handleUndo( GamePtr gamePtr );
    public static native boolean server_do( GamePtr gamePtr );
    public static native String server_formatDictCounts( GamePtr gamePtr, int nCols );
    public static native boolean server_getGameIsOver( GamePtr gamePtr );
    public static native String server_writeFinalScores( GamePtr gamePtr );
    public static native boolean server_initClientConnection( GamePtr gamePtr );
    public static native void server_endGame( GamePtr gamePtr );

    // hybrid to save work
    public static native boolean board_server_prefsChanged( GamePtr gamePtr,
                                                            CommonPrefs cp );

    // Comms
    public static native void comms_start( GamePtr gamePtr );
    public static native void comms_stop( GamePtr gamePtr );
    public static native void comms_resetSame( GamePtr gamePtr );
    public static native void comms_getAddr( GamePtr gamePtr, CommsAddrRec addr );
    public static native CommsAddrRec[] comms_getAddrs( GamePtr gamePtr );
    public static native void comms_setAddr( GamePtr gamePtr, CommsAddrRec addr );
    public static native int comms_resendAll( GamePtr gamePtr, boolean force,
                                              CommsConnType filter,
                                              boolean andAck );
    public static int comms_resendAll( GamePtr gamePtr, boolean force,
                                       boolean andAck ) {
        return comms_resendAll( gamePtr, force, null, andAck );
    }
    public static native void comms_ackAny( GamePtr gamePtr );
    public static native void comms_transportFailed( GamePtr gamePtr,
                                                     CommsConnType failed );
    public static native boolean comms_isConnected( GamePtr gamePtr );
    public static native String comms_formatRelayID( GamePtr gamePtr, int indx );
    public static native String comms_getStats( GamePtr gamePtr );

    // Dicts
    public static class DictWrapper {
        private int m_dictPtr;

        public DictWrapper()
        {
            m_dictPtr = 0;
        }

        public DictWrapper( int dictPtr )
        {
            m_dictPtr = dictPtr;
            dict_ref( dictPtr );
        }

        public void release()
        {
            if ( 0 != m_dictPtr ) {
                dict_unref( m_dictPtr );
                m_dictPtr = 0;
            }
        }

        public int getDictPtr()
        {
            return m_dictPtr;
        }

        // @Override
        public void finalize() throws java.lang.Throwable
        {
            release();
            super.finalize();
        }

    }

    public static native boolean dict_tilesAreSame( int dict1, int dict2 );
    public static native String[] dict_getChars( int dict );
    public static boolean dict_getInfo( byte[] dict, String name,
                                        String path, JNIUtils jniu,
                                        boolean check, DictInfo info )
    {
        return dict_getInfo( getJNI().m_ptr, dict, name, path, jniu,
                             check, info );
    }

    public static native int dict_getTileValue( int dictPtr, int tile );

    // Dict iterator
    public final static int MAX_COLS_DICT = 15; // from dictiter.h
    public static int dict_iter_init( byte[] dict, String name,
                                      String path, JNIUtils jniu )
    {
        return dict_iter_init( getJNI().m_ptr, dict, name, path, jniu );
    }
    public static native void dict_iter_setMinMax( int closure,
                                                   int min, int max );
    public static native void dict_iter_destroy( int closure );
    public static native int dict_iter_wordCount( int closure );
    public static native int[] dict_iter_getCounts( int closure );
    public static native String dict_iter_nthWord( int closure, int nn );
    public static native String[] dict_iter_getPrefixes( int closure );
    public static native int[] dict_iter_getIndices( int closure );
    public static native int dict_iter_getStartsWith( int closure,
                                                      String prefix );
    public static native String dict_iter_getDesc( int closure );

    // base64 stuff since 2.1 doesn't support it in java
    public static native String base64Encode( byte[] in );
    public static native byte[] base64Decode( String in );


    // Private methods -- called only here
    private static native int initGlobals();
    private static native void cleanGlobals( int globals );
    private static native int initJNI( int jniState, int seed, String tag );
    private static native void envDone( int globals );
    private static native void dict_ref( int dictPtr );
    private static native void dict_unref( int dictPtr );
    private static native boolean dict_getInfo( int jniState, byte[] dict,
                                                String name, String path,
                                                JNIUtils jniu, boolean check,
                                                DictInfo info );
    private static native int dict_iter_init( int jniState, byte[] dict,
                                              String name, String path,
                                              JNIUtils jniu );

    private static native boolean haveEnv( int jniState );
}
