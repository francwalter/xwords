/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009-2015 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
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

package org.eehouse.android.xw4;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;

import android.text.Html;
import android.text.TextUtils;
import android.view.Display;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.concurrent.locks.Lock;
import org.json.JSONArray;
import org.json.JSONObject;

import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnTypeSet;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;
import org.eehouse.android.xw4.jni.LastMoveInfo;
import org.eehouse.android.xw4.jni.XwJNI.GamePtr;
import org.eehouse.android.xw4.loc.LocUtils;

public class GameUtils {

    public static final String INVITED = "invited";
    public static final String INTENT_KEY_ROWID = "rowid";

    private static Integer s_minScreen;
    // Used to determine whether to resend all messages on networking coming
    // back up.  The length of the array determines the number of times in the
    // interval we'll do a send.
    private static long[] s_sendTimes = {0,0,0,0};
    private static final long RESEND_INTERVAL_SECS = 60 * 5; // 5 minutes

    public static class NoSuchGameException extends RuntimeException {
        public NoSuchGameException() {
            super();            // superfluous
            DbgUtils.logf( "creating NoSuchGameException");
        }
    }

    public static class BackMoveResult {
        LastMoveInfo m_lmi;     // instantiated on demand
        String m_chat;
        String m_chatFrom;
    }

    private static Object s_syncObj = new Object();

    public static byte[] savedGame( Context context, long rowid )
    {
        GameLock lock = new GameLock( rowid, false ).lock();
        byte[] result = savedGame( context, lock );
        lock.unlock();

        if ( null == result ) {
            throw new NoSuchGameException();
        }

        return result;
    }

    public static byte[] savedGame( Context context, GameLock lock )
    {
        return DBUtils.loadGame( context, lock );
    } // savedGame

    /**
     * Open an existing game, and use its gi and comms addr as the
     * basis for a new one.
     */
    public static GameLock resetGame( Context context, GameLock lockSrc,
                                      GameLock lockDest, boolean juggle )
    {
        CurGameInfo gi = new CurGameInfo( context );
        CommsAddrRec addr = null;

        // loadMakeGame, if making a new game, will add comms as long
        // as DeviceRole.SERVER_STANDALONE != gi.serverRole
        GamePtr gamePtr = loadMakeGame( context, gi, lockSrc );
        String[] dictNames = gi.dictNames();
        DictUtils.DictPairs pairs = DictUtils.openDicts( context, dictNames );

        if ( XwJNI.game_hasComms( gamePtr ) ) {
            addr = new CommsAddrRec();
            XwJNI.comms_getAddr( gamePtr, addr );
            if ( 0 == addr.conTypes.size() ) {
                String relayName = XWPrefs.getDefaultRelayHost( context );
                int relayPort = XWPrefs.getDefaultRelayPort( context );
                XwJNI.comms_getInitialAddr( addr, relayName, relayPort );
            }
        }
        gamePtr.release();

        gamePtr = XwJNI.initJNI();
        XwJNI.game_makeNewGame( gamePtr, gi, JNIUtilsImpl.get( context ),
                                CommonPrefs.get( context ), dictNames,
                                pairs.m_bytes,  pairs.m_paths, gi.langName() );

        if ( juggle ) {
            gi.juggle();
        }

        if ( null != addr ) {
            XwJNI.comms_setAddr( gamePtr, addr );
        }

        if ( null == lockDest ) {
            long groupID = DBUtils.getGroupForGame( context, lockSrc.getRowid() );
            long rowid = saveNewGame( context, gamePtr, gi, groupID );
            lockDest = new GameLock( rowid, true ).lock();
        } else {
            saveGame( context, gamePtr, gi, lockDest, true );
        }
        summarizeAndClose( context, lockDest, gamePtr, gi );
        DBUtils.saveThumbnail( context, lockDest, null );

        return lockDest;
    } // resetGame

    public static void resetGame( Context context, long rowidIn )
    {
        GameLock lock = new GameLock( rowidIn, true ).lock( 500 );
        if ( null != lock ) {
            tellDied( context, lock, true );
            resetGame( context, lock, lock, false );
            lock.unlock();

            Utils.cancelNotification( context, (int)rowidIn );
        } else {
            DbgUtils.logf( "resetGame: unable to open rowid %d", rowidIn );
        }
    }

    private static int setFromFeedImpl( FeedUtilsImpl feedImpl )
    {
        int result = GameSummary.MSG_FLAGS_NONE;
        if ( feedImpl.m_gotChat ) {
            result |= GameSummary.MSG_FLAGS_CHAT;
        }
        if ( feedImpl.m_gotMsg ) {
            result |= GameSummary.MSG_FLAGS_TURN;
        }
        if ( feedImpl.m_gameOver ) {
            result |= GameSummary.MSG_FLAGS_GAMEOVER;
        }
        return result;
    }

    private static GameSummary summarizeAndClose( Context context,
                                                  GameLock lock,
                                                  GamePtr gamePtr,
                                                  CurGameInfo gi )
    {
        GameSummary summary = new GameSummary( context, gi );
        XwJNI.game_summarize( gamePtr, summary );

        DBUtils.saveSummary( context, lock, summary );

        gamePtr.release();
        return summary;
    }

    public static GameSummary summarize( Context context, GameLock lock )
    {
        GameSummary result = null;
        CurGameInfo gi = new CurGameInfo( context );
        GamePtr gamePtr = loadMakeGame( context, gi, lock );
        if ( null != gamePtr ) {
            result = summarizeAndClose( context, lock, gamePtr, gi );
        }
        return result;
    }

    public static long dupeGame( Context context, long rowidIn )
    {
        long rowid = DBUtils.ROWID_NOTFOUND;
        GameLock lockSrc = new GameLock( rowidIn, false ).lock( 300 );
        if ( null != lockSrc ) {
            boolean juggle = CommonPrefs.getAutoJuggle( context );
            GameLock lockDest = resetGame( context, lockSrc, null, juggle );
            rowid = lockDest.getRowid();
            lockDest.unlock();
            lockSrc.unlock();
        } else {
            DbgUtils.logf( "dupeGame: unable to open rowid %d", rowidIn );
        }
        return rowid;
    }

    public static void deleteGame( Context context, GameLock lock, boolean informNow )
    {
        tellDied( context, lock, informNow );
        Utils.cancelNotification( context, (int)lock.getRowid() );
        DBUtils.deleteGame( context, lock );
    }

    public static boolean deleteGame( Context context, long rowid,
                                      boolean informNow )
    {
        boolean success;
        // does this need to be synchronized?
        GameLock lock = new GameLock( rowid, true );
        if ( lock.tryLock() ) {
            deleteGame( context, lock, informNow );
            lock.unlock();
            success = true;
        } else {
            DbgUtils.logf( "deleteGame: unable to delete rowid %d", rowid );
            success = false;
        }
        return success;
    }

    public static void deleteGroup( Context context, long groupid )
    {
        int nSuccesses = 0;
        long[] rowids = DBUtils.getGroupGames( context, groupid );
        for ( int ii = rowids.length - 1; ii >= 0; --ii ) {
            if ( deleteGame( context, rowids[ii], ii == 0 ) ) {
                ++nSuccesses;
            }
        }
        if ( rowids.length == nSuccesses ) {
            DBUtils.deleteGroup( context, groupid );
        }
    }

    public static String getName( Context context, long rowid )
    {
        String result = DBUtils.getName( context, rowid );
        if ( null == result || 0 == result.length() ) {
            int visID = DBUtils.getVisID( context, rowid );
            result = LocUtils.getString( context, R.string.game_fmt, visID );
        }
        return result;
    }

    public static String makeDefaultName( Context context )
    {
        int count = DBUtils.getIncrementIntFor( context, DBUtils.KEY_NEWGAMECOUNT, 0, 1 );
        return LocUtils.getString( context, R.string.game_fmt, count );
    }

    public static GamePtr loadMakeGame( Context context, CurGameInfo gi,
                                        TransportProcs tp, GameLock lock )
    {
        return loadMakeGame( context, gi, null, tp, lock );
    }

    public static GamePtr loadMakeGame( Context context, CurGameInfo gi,
                                    GameLock lock )
    {
        return loadMakeGame( context, gi, null, null, lock );
    }

    public static GamePtr loadMakeGame( Context context, CurGameInfo gi,
                                        UtilCtxt util, TransportProcs tp,
                                        GameLock lock )
    {
        GamePtr gamePtr = null;

        byte[] stream = savedGame( context, lock );
        if ( null == stream ) {
            DbgUtils.logf( "loadMakeGame: no saved game!");
        } else {
            XwJNI.gi_from_stream( gi, stream );
            String[] dictNames = gi.dictNames();
            DictUtils.DictPairs pairs = DictUtils.openDicts( context, dictNames );
            if ( pairs.anyMissing( dictNames ) ) {
                DbgUtils.logf( "loadMakeGame() failing: dicts %s unavailable",
                               TextUtils.join( ",", dictNames ) );
            } else {
                gamePtr = XwJNI.initJNI( lock.getRowid() );

                String langName = gi.langName();
                boolean madeGame =
                    XwJNI.game_makeFromStream( gamePtr, stream, gi,
                                               dictNames, pairs.m_bytes,
                                               pairs.m_paths, langName,
                                               util, JNIUtilsImpl.get( context ),
                                               CommonPrefs.get(context),
                                               tp);
                if ( !madeGame ) {
                    XwJNI.game_makeNewGame( gamePtr, gi, JNIUtilsImpl.get(context),
                                            CommonPrefs.get(context), dictNames,
                                            pairs.m_bytes, pairs.m_paths,
                                            langName );
                }
            }
        }
        return gamePtr;
    }

    public static Bitmap loadMakeBitmap( Activity activity, long rowid )
    {
        Bitmap thumb = null;
        GameLock lock = new GameLock( rowid, false );
        if ( lock.tryLock() ) {
            CurGameInfo gi = new CurGameInfo( activity );
            GamePtr gamePtr = loadMakeGame( activity, gi, lock );
            if ( null != gamePtr ) {
                thumb = takeSnapshot( activity, gamePtr, gi );
                gamePtr.release();
                DBUtils.saveThumbnail( activity, lock, thumb );
            }
            lock.unlock();
        }
        return thumb;
    }

    public static Bitmap takeSnapshot( Context context, GamePtr gamePtr,
                                       CurGameInfo gi )
    {
        Bitmap thumb = null;
        if ( BuildConstants.THUMBNAIL_SUPPORTED ) {
            if ( XWPrefs.getThumbEnabled( context ) ) {
                int nCols = gi.boardSize;
                int pct = XWPrefs.getThumbPct( context );
                Assert.assertTrue( 0 < pct );

                if ( null == s_minScreen ) {
                    if ( context instanceof Activity ) {
                        Activity activity = (Activity)context;
                        Display display =
                            activity.getWindowManager().getDefaultDisplay();
                        int width = display.getWidth();
                        int height = display.getHeight();
                        s_minScreen = new Integer( Math.min( width, height ) );
                    }
                }
                if ( null != s_minScreen ) {
                    int dim = s_minScreen * pct / 100;
                    int size = dim - (dim % nCols);

                    thumb = Bitmap.createBitmap( size, size,
                                                 Bitmap.Config.ARGB_8888 );

                    XwJNI.board_figureLayout( gamePtr, gi, 0, 0, size, size,
                                              0, 0, 0, 20, 20, false, null );

                    ThumbCanvas canvas = new ThumbCanvas( context, thumb );
                    XwJNI.board_setDraw( gamePtr, canvas );
                    XwJNI.board_invalAll( gamePtr );
                    Assert.assertNotNull( gamePtr );
                    XwJNI.board_draw( gamePtr );
                }
            }
        }
        return thumb;
    }

    public static void resendAllIf( Context context, CommsConnType filter,
                                    boolean force )
    {
        final boolean showUI = force;
        long now = Utils.getCurSeconds();

        if ( !force ) {
            long oldest = s_sendTimes[s_sendTimes.length - 1];
            long age = now - oldest;
            force = RESEND_INTERVAL_SECS < age;
            DbgUtils.logdf( "resendAllIf(): based on last send age of %d sec, doit = %b",
                            age, force );
        }

        if ( force ) {
            HashMap<Long,CommsConnTypeSet> games =
                DBUtils.getGamesWithSendsPending( context );
            if ( 0 < games.size() ) {
                new ResendTask( context, games, filter, showUI ).execute();

                System.arraycopy( s_sendTimes, 0, /* src */
                                  s_sendTimes, 1, /* dest */
                                  s_sendTimes.length - 1 );
                s_sendTimes[0] = now;
            }
        }
    }

    public static long saveGame( Context context, GamePtr gamePtr,
                                 CurGameInfo gi, GameLock lock,
                                 boolean setCreate )
    {
        byte[] stream = XwJNI.game_saveToStream( gamePtr, gi );
        return saveGame( context, stream, lock, setCreate );
    }

    public static long saveNewGame( Context context, GamePtr gamePtr,
                                    CurGameInfo gi, long groupID )
    {
        byte[] stream = XwJNI.game_saveToStream( gamePtr, gi );
        GameLock lock = DBUtils.saveNewGame( context, stream, groupID, null );
        long rowid = lock.getRowid();
        lock.unlock();
        return rowid;
    }

    public static long saveGame( Context context, byte[] bytes,
                                 GameLock lock, boolean setCreate )
    {
        return DBUtils.saveGame( context, lock, bytes, setCreate );
    }

    public static GameLock saveNewGame( Context context, byte[] bytes )
    {
        return saveNewGame( context, bytes, DBUtils.GROUPID_UNSPEC );
    }

    public static GameLock saveNewGame( Context context, byte[] bytes,
                                        long groupID )
    {
        return DBUtils.saveNewGame( context, bytes, groupID, null );
    }

    public static long saveNew( Context context, CurGameInfo gi, long groupID,
                                String gameName )
    {
        if ( DBUtils.GROUPID_UNSPEC == groupID ) {
            groupID = XWPrefs.getDefaultNewGameGroup( context );
        }

        long rowid = DBUtils.ROWID_NOTFOUND;
        byte[] bytes = XwJNI.gi_to_stream( gi );
        if ( null != bytes ) {
            GameLock lock = DBUtils.saveNewGame( context, bytes, groupID,
                                                 gameName );
            rowid = lock.getRowid();
            lock.unlock();
        }
        return rowid;
    }

    public static long makeNewMultiGame( Context context, NetLaunchInfo nli )
    {
        return makeNewMultiGame( context, nli, (MultiMsgSink)null,
                                 (UtilCtxt)null );
    }

    public static long makeNewMultiGame( Context context, NetLaunchInfo nli,
                                         MultiMsgSink sink, UtilCtxt util )
    {
        DbgUtils.logdf( "makeNewMultiGame(nli=%s)", nli.toString() );
        CommsAddrRec addr = nli.makeAddrRec( context );

        return makeNewMultiGame( context, sink, util, DBUtils.GROUPID_UNSPEC,
                                 addr, new int[] {nli.lang},
                                 new String[] { nli.dict }, null, nli.nPlayersT,
                                 nli.nPlayersH, nli.forceChannel,
                                 nli.inviteID(), nli.gameID(),
                                 nli.gameName, false );
    }

    public static long makeNewMultiGame( Context context, long groupID,
                                         String gameName )
    {
        return makeNewMultiGame( context, groupID, null, 0, null,
                                 (CommsConnTypeSet)null, gameName );
    }

    public static long makeNewMultiGame( Context context, long groupID,
                                         String dict, int lang, String jsonData,
                                         CommsConnTypeSet addrSet,
                                         String gameName )
    {
        String inviteID = makeRandomID();
        return makeNewMultiGame( context, groupID, inviteID, dict, lang,
                                 jsonData, addrSet, gameName );
    }

    private static long makeNewMultiGame( Context context, long groupID,
                                          String inviteID, String dict,
                                          int lang, String jsonData,
                                          CommsConnTypeSet addrSet,
                                          String gameName )
    {
        int[] langArray = {lang};
        String[] dictArray = {dict};
        if ( null == addrSet ) {
            addrSet = XWPrefs.getAddrTypes( context );
        }
        CommsAddrRec addr = new CommsAddrRec( addrSet );
        addr.populate( context );
        int forceChannel = 0;
        return makeNewMultiGame( context, (MultiMsgSink)null, (UtilCtxt)null,
                                 groupID, addr, langArray, dictArray, jsonData,
                                 2, 1, forceChannel, inviteID, 0, gameName,
                                 true );
    }

    private static long makeNewMultiGame( Context context, MultiMsgSink sink,
                                          UtilCtxt util, long groupID,
                                          CommsAddrRec addr,
                                          int[] lang, String[] dict,
                                          String jsonData,
                                          int nPlayersT, int nPlayersH,
                                          int forceChannel, String inviteID,
                                          int gameID, String gameName,
                                          boolean isHost )
    {
        long rowid = -1;

        Assert.assertNotNull( inviteID );
        CurGameInfo gi = new CurGameInfo( context, inviteID );
        gi.setFrom( jsonData );
        gi.setLang( lang[0], dict[0] );
        gi.forceChannel = forceChannel;
        lang[0] = gi.dictLang;
        dict[0] = gi.dictName;
        gi.setNPlayers( nPlayersT, nPlayersH );
        gi.juggle();
        if ( 0 != gameID ) {
            gi.gameID = gameID;
        }
        if ( isHost ) {
            gi.serverRole = DeviceRole.SERVER_ISSERVER;
        }
        // Will need to add a setNPlayers() method to gi to make this
        // work
        Assert.assertTrue( gi.nPlayers == nPlayersT );
        rowid = saveNew( context, gi, groupID, gameName );
        if ( null != sink ) {
            sink.setRowID( rowid );
        }

        if ( DBUtils.ROWID_NOTFOUND != rowid ) {
            GameLock lock = new GameLock( rowid, true ).lock();
            applyChanges( context, sink, gi, util, addr, lock, false );
            lock.unlock();
        }

        return rowid;
    }

    public static long makeNewGame( Context context, MultiMsgSink sink,
                                    int gameID, CommsAddrRec addr, int lang,
                                    String dict, int nPlayersT,
                                    int nPlayersH, int forceChannel,
                                    String gameName )
    {
        return makeNewGame( context, sink, DBUtils.GROUPID_UNSPEC, gameID, addr,
                            lang, dict, nPlayersT, nPlayersH, forceChannel,
                            gameName );
    }

    public static long makeNewGame( Context context, int gameID,
                                    CommsAddrRec addr, int lang,
                                    String dict, int nPlayersT,
                                    int nPlayersH, int forceChannel,
                                    String gameName )
    {
        return makeNewGame( context, DBUtils.GROUPID_UNSPEC, gameID, addr,
                            lang, dict, nPlayersT, nPlayersH, forceChannel,
                            gameName );
    }

    public static long makeNewGame( Context context, long groupID,  int gameID,
                                    CommsAddrRec addr, int lang, String dict,
                                    int nPlayersT, int nPlayersH,
                                    int forceChannel, String gameName )
    {
        return makeNewGame( context, null, groupID, gameID, addr, lang, dict,
                            nPlayersT, nPlayersH, forceChannel, gameName );
    }

    public static long makeNewGame( Context context, MultiMsgSink sink,
                                    long groupID,  int gameID, CommsAddrRec addr,
                                    int lang, String dict,
                                    int nPlayersT, int nPlayersH,
                                    int forceChannel, String gameName )
    {
        long rowid = -1;
        int[] langa = { lang };
        String[] dicta = { dict };
        boolean isHost = null == addr;
        if ( isHost ) {
            addr = new CommsAddrRec( null, null );
        }
        String inviteID = GameUtils.formatGameID( gameID );
        return makeNewMultiGame( context, sink, (UtilCtxt)null, groupID, addr,
                                 langa, dicta, null, nPlayersT, nPlayersH,
                                 forceChannel, inviteID, gameID, gameName,
                                 isHost );
    }

    // @SuppressLint({ "NewApi", "NewApi", "NewApi", "NewApi" })
    // @SuppressWarnings("deprecation")
    // @TargetApi(11)
    public static void inviteURLToClip( Context context, NetLaunchInfo nli )
    {
        Uri gameUri = nli.makeLaunchUri( context );
        String asStr = gameUri.toString();

        int sdk = android.os.Build.VERSION.SDK_INT;
        if ( sdk < android.os.Build.VERSION_CODES.HONEYCOMB ) {
            android.text.ClipboardManager clipboard =
                (android.text.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setText( asStr );
        } else {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            String label = LocUtils.getString( context, R.string.clip_label );
            android.content.ClipData clip = android.content.ClipData
                .newPlainText( label, asStr );
            clipboard.setPrimaryClip( clip );
        }

        Utils.showToast( context, R.string.invite_copied );
    }

    public static void launchEmailInviteActivity( Activity activity, NetLaunchInfo nli )
    {
        // DbgUtils.logf( "launchEmailInviteActivity: nli=%s", nli.makeLaunchJSON() );
        Uri gameUri = nli.makeLaunchUri( activity );
        // DbgUtils.logf( "launchEmailInviteActivity: uri=%s", gameUri );

        String msgString = null == gameUri ? null : gameUri.toString();
        if ( null != msgString ) {
            int choiceID;
            String message = LocUtils.getString( activity, R.string.invite_htm_fmt, msgString );

            Intent intent = new Intent();
            intent.setAction( Intent.ACTION_SEND );
            String subject =
                LocUtils.getString( activity, R.string.invite_subject_fmt,
                                    nli.room );
            intent.putExtra( Intent.EXTRA_SUBJECT, subject );
            intent.putExtra( Intent.EXTRA_TEXT, Html.fromHtml(message) );

            File attach = null;
            File tmpdir = XWApp.ATTACH_SUPPORTED ?
                DictUtils.getDownloadDir( activity ) : null;
            if ( null != tmpdir ) { // no attachment
                attach = makeJsonFor( tmpdir, nli );
            }

            if ( null == attach ) { // no attachment
                intent.setType( "message/rfc822");
            } else {
                String mime = LocUtils.getString( activity, R.string.invite_mime );
                intent.setType( mime );
                Uri uri = Uri.fromFile( attach );
                intent.putExtra( Intent.EXTRA_STREAM, uri );
            }

            String choiceType = LocUtils.getString( activity, R.string.invite_chooser_email );
            String chooserMsg =
                LocUtils.getString( activity, R.string.invite_chooser_fmt,
                                    choiceType );
            activity.startActivity( Intent.createChooser( intent, chooserMsg ) );
        }
    }

    // public static void launchInviteActivity( Activity activity,
    //                                          InviteMeans means,
    //                                          String room, String inviteID,
    //                                          int lang, String dict,
    //                                          int nPlayers )
    // {
    //     Assert.assertNotNull( inviteID );

    //     if ( InviteMeans.NFC == means ) {
    //         Utils.showToast( activity, R.string.sms_ready_text );
    //     } else {
    //         // NetLaunchInfo nli = new NetLaunchInfo( 0, lang, dict, nPlayers );

    //         Uri gameUri = NetLaunchInfo.makeLaunchUri( activity, room, inviteID,
    //                                                    lang, dict, nPlayers );
    //         String msgString = null == gameUri ? null : gameUri.toString();

    //         if ( null != msgString ) {
    //             boolean choseEmail = InviteMeans.EMAIL == means;

    //             int fmtId = choseEmail? R.string.invite_htm_fmt : R.string.invite_txt_fmt;
    //             int choiceID;
    //             String message = LocUtils.getString( activity, fmtId, msgString );

    //             Intent intent = new Intent();
    //             if ( choseEmail ) {
    //                 intent.setAction( Intent.ACTION_SEND );
    //                 String subject =
    //                     LocUtils.getString( activity, R.string.invite_subject_fmt,
    //                                         room );
    //                 intent.putExtra( Intent.EXTRA_SUBJECT, subject );
    //                 intent.putExtra( Intent.EXTRA_TEXT, Html.fromHtml(message) );

    //                 File attach = null;
    //                 File tmpdir = XWApp.ATTACH_SUPPORTED ?
    //                     DictUtils.getDownloadDir( activity ) : null;
    //                 if ( null != tmpdir ) { // no attachment
    //                     attach = makeJsonFor( tmpdir, room, inviteID, lang,
    //                                           dict, nPlayers );
    //                 }

    //                 if ( null == attach ) { // no attachment
    //                     intent.setType( "message/rfc822");
    //                 } else {
    //                     String mime = LocUtils.getString( activity, R.string.invite_mime );
    //                     intent.setType( mime );
    //                     Uri uri = Uri.fromFile( attach );
    //                     intent.putExtra( Intent.EXTRA_STREAM, uri );
    //                 }

    //                 choiceID = R.string.invite_chooser_email;
    //             } else {
    //                 intent.setAction( Intent.ACTION_VIEW );
    //                 intent.setType( "vnd.android-dir/mms-sms" );
    //                 intent.putExtra( "sms_body", message );
    //                 choiceID = R.string.invite_chooser_sms;
    //             }

    //             String choiceType = LocUtils.getString( activity, choiceID );
    //             String chooserMsg =
    //                 LocUtils.getString( activity, R.string.invite_chooser_fmt,
    //                                     choiceType );
    //             activity.startActivity( Intent.createChooser( intent, chooserMsg ) );
    //         }
    //     }
    // }


    public static String[] dictNames( Context context, GameLock lock )
    {
        byte[] stream = savedGame( context, lock );
        CurGameInfo gi = new CurGameInfo( context );
        XwJNI.gi_from_stream( gi, stream );
        return gi.dictNames();
    }

    public static String[] dictNames( Context context, long rowid,
                                      int[] missingLang )
    {
        byte[] stream = savedGame( context, rowid );
        CurGameInfo gi = new CurGameInfo( context );
        XwJNI.gi_from_stream( gi, stream );
        if ( null != missingLang ) {
            missingLang[0] = gi.dictLang;
        }
        return gi.dictNames();
    }

    public static String[] dictNames( Context context, long rowid )
    {
        return dictNames( context, rowid, null );
    }

    public static boolean gameDictsHere( Context context, long rowid )
    {
        return gameDictsHere( context, rowid, null, null );
    }

    // Return true if all dicts present.  Return list of those that
    // are not.
    public static boolean gameDictsHere( Context context, long rowid,
                                         String[][] missingNames,
                                         int[] missingLang )
    {
        String[] gameDicts = dictNames( context, rowid, missingLang );
        HashSet<String> missingSet;
        DictUtils.DictAndLoc[] installed = DictUtils.dictList( context );

        missingSet = new HashSet<String>( Arrays.asList( gameDicts ) );
        missingSet.remove( null );
        boolean allHere = 0 != missingSet.size(); // need some non-null!
        if ( allHere ) {
            for ( DictUtils.DictAndLoc dal : installed ) {
                missingSet.remove( dal.name );
            }
            allHere = 0 == missingSet.size();
        } else {
            DbgUtils.logf( "gameDictsHere: game has no dicts!" );
        }
        if ( null != missingNames ) {
            missingNames[0] =
                missingSet.toArray( new String[missingSet.size()] );
        }

        return allHere;
    }

    public static String newName( Context context )
    {
        return "untitled";
        // String name = null;
        // Integer num = 1;
        // int ii;
        // long[] rowids = DBUtils.gamesList( context );
        // String fmt = context.getString( R.string.gamef );

        // while ( name == null ) {
        //     name = String.format( fmt + XWConstants.GAME_EXTN, num );
        //     for ( ii = 0; ii < files.length; ++ii ) {
        //         if ( files[ii].equals(name) ) {
        //             ++num;
        //             name = null;
        //         }
        //     }
        // }
        // return name;
    }

    private static boolean isGame( String file )
    {
        return file.endsWith( XWConstants.GAME_EXTN );
    }

    public static Bundle makeLaunchExtras( long rowid, boolean invited )
    {
        Bundle bundle = new Bundle();
        bundle.putLong( INTENT_KEY_ROWID, rowid );
        if ( invited ) {
            bundle.putBoolean( INVITED, true );
        }
        return bundle;
    }

    public static void launchGame( Delegator delegator, long rowid,
                                   boolean invited )
    {
        Bundle extras = makeLaunchExtras( rowid, invited );
        if ( delegator.inDPMode() ) {
            delegator.addFragment( new BoardFrag( delegator ), extras );
        } else {
            Activity activity = delegator.getActivity();
            Intent intent = new Intent( activity, BoardActivity.class );
            intent.putExtras( extras );
            activity.startActivity( intent );
        }
    }

    public static void launchGame( Delegator delegator, long rowid )
    {
        launchGame( delegator, rowid, false );
    }

    public static void launchGameAndFinish( Delegator delegator, long rowid )
    {
        launchGame( delegator, rowid );
        delegator.getActivity().finish();
    }

    private static class FeedUtilsImpl extends UtilCtxtImpl {
        private Context m_context;
        private long m_rowid;
        public String m_chat;
        public boolean m_gotMsg;
        public boolean m_gotChat;
        public String m_chatFrom;
        public boolean m_gameOver;

        public FeedUtilsImpl( Context context, long rowid )
        {
            super( context );
            m_context = context;
            m_rowid = rowid;
            m_gotMsg = false;
            m_gameOver = false;
        }
        @Override
        public void showChat( String msg, int fromIndx, String fromName )
        {
            DBUtils.appendChatHistory( m_context, m_rowid, msg, fromIndx );
            m_gotChat = true;
            m_chatFrom = fromName;
            m_chat = msg;
        }
        public void turnChanged( int newTurn )
        {
            m_gotMsg = true;
        }

        public void notifyGameOver()
        {
            m_gameOver = true;
        }
    }

    public static boolean feedMessages( Context context, long rowid,
                                        byte[][] msgs, CommsAddrRec ret,
                                        MultiMsgSink sink, BackMoveResult bmr,
                                        boolean[] isLocalOut )
    {
        boolean draw = false;
        Assert.assertTrue( -1 != rowid );
        if ( null != msgs ) {
            // timed lock: If a game is opened by BoardActivity just
            // as we're trying to deliver this message to it it'll
            // have the lock and we'll never get it.  Better to drop
            // the message than fire the hung-lock assert.  Messages
            // belong in local pre-delivery storage anyway.
            GameLock lock = new GameLock( rowid, true ).lock( 150 );
            if ( null != lock ) {
                CurGameInfo gi = new CurGameInfo( context );
                FeedUtilsImpl feedImpl = new FeedUtilsImpl( context, rowid );
                GamePtr gamePtr = loadMakeGame( context, gi, feedImpl, sink, lock );
                if ( null != gamePtr ) {
                    XwJNI.comms_resendAll( gamePtr, false, false );

                    for ( byte[] msg : msgs ) {
                        Assert.assertNotNull( ret );
                        draw = XwJNI.game_receiveMessage( gamePtr, msg, ret )
                            || draw;
                    }
                    XwJNI.comms_ackAny( gamePtr );

                    // update gi to reflect changes due to messages
                    XwJNI.game_getGi( gamePtr, gi );

                    if ( draw && XWPrefs.getThumbEnabled( context ) ) {
                        Bitmap bitmap = takeSnapshot( context, gamePtr, gi );
                        DBUtils.saveThumbnail( context, lock, bitmap );
                    }

                    if ( null != bmr ) {
                        if ( null != feedImpl.m_chat ) {
                            bmr.m_chat = feedImpl.m_chat;
                            bmr.m_chatFrom = feedImpl.m_chatFrom;
                        } else {
                            LastMoveInfo lmi = new LastMoveInfo();
                            XwJNI.model_getPlayersLastScore( gamePtr, -1, lmi );
                            bmr.m_lmi = lmi;
                        }
                    }

                    saveGame( context, gamePtr, gi, lock, false );
                    GameSummary summary = summarizeAndClose( context, lock,
                                                             gamePtr, gi );
                    if ( null != isLocalOut ) {
                        isLocalOut[0] = 0 <= summary.turn
                            && gi.players[summary.turn].isLocal;
                    }

                    int flags = setFromFeedImpl( feedImpl );
                    if ( GameSummary.MSG_FLAGS_NONE != flags ) {
                        draw = true;
                        int curFlags = DBUtils.getMsgFlags( context, rowid );
                        DBUtils.setMsgFlags( rowid, flags | curFlags );
                    }
                }
                lock.unlock();
            }
        }
        return draw;
    } // feedMessages

    public static boolean feedMessage( Context context, long rowid, byte[] msg,
                                       CommsAddrRec ret, MultiMsgSink sink,
                                       BackMoveResult bmr, boolean[] isLocalOut )
    {
        Assert.assertTrue( DBUtils.ROWID_NOTFOUND != rowid );
        byte[][] msgs = new byte[1][];
        msgs[0] = msg;
        return feedMessages( context, rowid, msgs, ret, sink, bmr, isLocalOut );
    }

    // This *must* involve a reset if the language is changing!!!
    // Which isn't possible right now, so make sure the old and new
    // dict have the same langauge code.
    public static boolean replaceDicts( Context context, long rowid,
                                        String oldDict, String newDict )
    {
        GameLock lock = new GameLock( rowid, true ).lock(300);
        boolean success = null != lock;
        if ( success ) {
            byte[] stream = savedGame( context, lock );
            CurGameInfo gi = new CurGameInfo( context );
            XwJNI.gi_from_stream( gi, stream );

            // first time required so dictNames() will work
            gi.replaceDicts( newDict );

            String[] dictNames = gi.dictNames();
            DictUtils.DictPairs pairs = DictUtils.openDicts( context,
                                                             dictNames );

            GamePtr gamePtr = XwJNI.initJNI( rowid );
            XwJNI.game_makeFromStream( gamePtr, stream, gi, dictNames,
                                       pairs.m_bytes, pairs.m_paths,
                                       gi.langName(),
                                       JNIUtilsImpl.get(context),
                                       CommonPrefs.get( context ) );
            // second time required as game_makeFromStream can overwrite
            gi.replaceDicts( newDict );

            saveGame( context, gamePtr, gi, lock, false );

            summarizeAndClose( context, lock, gamePtr, gi );

            lock.unlock();
        } else {
            DbgUtils.logf( "replaceDicts: unable to open rowid %d", rowid );
        }
        return success;
    } // replaceDicts

    public static void applyChanges( Context context, CurGameInfo gi,
                                     CommsAddrRec car, GameLock lock,
                                     boolean forceNew )
    {
        applyChanges( context, (MultiMsgSink)null, gi, (UtilCtxt)null, car,
                      lock, forceNew );
    }

    public static void applyChanges( Context context, MultiMsgSink sink,
                                     CurGameInfo gi, UtilCtxt util,
                                     CommsAddrRec car, GameLock lock,
                                     boolean forceNew )
    {
        // This should be a separate function, commitChanges() or
        // somesuch.  But: do we have a way to save changes to a gi
        // that don't reset the game, e.g. player name for standalone
        // games?
        String[] dictNames = gi.dictNames();
        DictUtils.DictPairs pairs = DictUtils.openDicts( context, dictNames );
        String langName = gi.langName();
        GamePtr gamePtr = XwJNI.initJNI( lock.getRowid() );
        boolean madeGame = false;
        CommonPrefs cp = CommonPrefs.get( context );

        if ( forceNew ) {
            tellDied( context, lock, true );
        } else {
            byte[] stream = savedGame( context, lock );
            // Will fail if there's nothing in the stream but a gi.
            madeGame = XwJNI.game_makeFromStream( gamePtr, stream,
                                                  new CurGameInfo(context),
                                                  dictNames, pairs.m_bytes,
                                                  pairs.m_paths, langName,
                                                  JNIUtilsImpl.get(context),
                                                  cp );
        }

        if ( forceNew || !madeGame ) {
            XwJNI.game_makeNewGame( gamePtr, gi, dictNames, pairs.m_bytes,
                                    pairs.m_paths, langName, util,
                                    JNIUtilsImpl.get(context), (DrawCtx)null,
                                    cp, sink );
        }

        if ( null != car ) {
            XwJNI.comms_setAddr( gamePtr, car );
        }

        if ( null != sink ) {
            JNIThread.tryConnectClient( gamePtr, gi );
        }

        saveGame( context, gamePtr, gi, lock, false );

        GameSummary summary = new GameSummary( context, gi );
        XwJNI.game_summarize( gamePtr, summary );
        gamePtr.release();
        DBUtils.saveSummary( context, lock, summary );
    } // applyChanges

    public static void doConfig( Delegator delegator, long rowid )
    {
        Bundle extras = new Bundle();
        extras.putLong( INTENT_KEY_ROWID, rowid );

        if ( delegator.inDPMode() ) {
            delegator.addFragment( new GameConfigFrag( delegator ), extras );
        } else {
            Activity activity = delegator.getActivity();
            Intent intent = new Intent( activity, GameConfigActivity.class );
            intent.setAction( Intent.ACTION_EDIT );
            intent.putExtras( extras );
            activity.startActivity( intent );
        }
    }

    public static String formatGameID( int gameID )
    {
        Assert.assertTrue( 0 != gameID );
        // substring: Keep it short so fits in SMS better
        return String.format( "%X", gameID ).substring( 0, 4 );
    }

    public static String makeRandomID()
    {
        int rint = newGameID();
        return formatGameID( rint );
    }

    public static int newGameID()
    {
        int rint;
        do {
            rint = Utils.nextRandomInt();
        } while ( 0 == rint );
        DbgUtils.logf( "newGameID()=>%X (%d)", rint, rint );
        return rint;
    }

    public static void postMoveNotification( Context context, long rowid,
                                             BackMoveResult bmr,
                                             boolean isTurnNow )
    {
        if ( null != bmr ) {
            Intent intent = GamesListDelegate.makeRowidIntent( context, rowid );
            String msg = null;
            int titleID = 0;
            if ( null != bmr.m_chat ) {
                titleID = R.string.notify_chat_title_fmt;
                if ( null != bmr.m_chatFrom ) {
                    msg = LocUtils
                        .getString( context, R.string.notify_chat_body_fmt,
                                    bmr.m_chatFrom, bmr.m_chat );
                } else {
                    msg = bmr.m_chat;
                }
            } else if ( null != bmr.m_lmi ) {
                if ( isTurnNow ) {
                    titleID = R.string.notify_title_turn_fmt;
                } else {
                    titleID = R.string.notify_title_fmt;
                }
                msg = bmr.m_lmi.format( context );
            }

            if ( 0 != titleID ) {
                String title = LocUtils.getString( context, titleID,
                                                   getName( context, rowid ) );
                Utils.postNotification( context, intent, title, msg, (int)rowid );
            }
        } else {
            DbgUtils.logdf( "postMoveNotification(): posting nothing for lack"
                            + " of brm" );
        }
    }

    public static void postInvitedNotification( Context context, int gameID,
                                                String body, long rowid )
    {
        Intent intent = GamesListDelegate.makeGameIDIntent( context, gameID );
        Utils.postNotification( context, intent, R.string.invite_notice_title,
                                body, (int)rowid );
    }

    private static void tellDied( Context context, GameLock lock,
                                  boolean informNow )
    {
        GameSummary summary = DBUtils.getSummary( context, lock );
        for ( Iterator<CommsConnType> iter = summary.conTypes.iterator();
              iter.hasNext(); ) {
            switch( iter.next() ) {
            case COMMS_CONN_RELAY:
                tellRelayDied( context, summary, informNow );
                break;
            case COMMS_CONN_BT:
                BTService.gameDied( context, summary.gameID );
                break;
            case COMMS_CONN_SMS:
                if ( null != summary.remoteDevs ) {
                    for ( String dev : summary.remoteDevs ) {
                        SMSService.gameDied( context, summary.gameID, dev );
                    }
                }
                break;
            }
        }
    }

    private static void tellRelayDied( Context context, GameSummary summary,
                                       boolean informNow )
    {
        if ( null != summary.relayID ) {
            DBUtils.addDeceased( context, summary.relayID, summary.seed );
            if ( informNow ) {
                NetUtils.informOfDeaths( context );
            }
        }
    }

    private static File makeJsonFor( File dir, NetLaunchInfo nli )
    {
        File result = null;
        if ( XWApp.ATTACH_SUPPORTED ) {
            byte[] data = nli.makeLaunchJSON().getBytes();

            File file = new File( dir, String.format("invite_%d", nli.gameID() ));
            try {
                FileOutputStream fos = new FileOutputStream( file );
                fos.write( data, 0, data.length );
                fos.close();
                result = file;
            } catch ( Exception ex ) {
                DbgUtils.loge( ex );
            }
        }
        return result;
    }

    private static class ResendTask extends AsyncTask<Void, Void, Void> {
        private Context m_context;
        private HashMap<Long,CommsConnTypeSet> m_games;
        private boolean m_showUI;
        private CommsConnType m_filter;
        private MultiMsgSink m_sink;

        public ResendTask( Context context, HashMap<Long,CommsConnTypeSet> games,
                           CommsConnType filter, boolean showUI )
        {
            m_context = context;
            m_games = games;
            m_filter = filter;
            m_showUI = showUI;
        }

        @Override
        protected Void doInBackground( Void... unused )
        {
            Iterator<Long> iter = m_games.keySet().iterator();
            while ( iter.hasNext() ) {
                long rowid = iter.next();

                // If we're looking for a specific type, check
                if ( null != m_filter ) {
                    CommsConnTypeSet gameSet = m_games.get( rowid );
                    if ( gameSet != null && ! gameSet.contains( m_filter ) ) {
                        continue;
                    }
                }

                GameLock lock = new GameLock( rowid, false );
                if ( lock.tryLock() ) {
                    CurGameInfo gi = new CurGameInfo( m_context );
                    m_sink = new MultiMsgSink( m_context, rowid );
                    GamePtr gamePtr = loadMakeGame( m_context, gi, m_sink, lock );
                    if ( null != gamePtr ) {
                        int nSent = XwJNI.comms_resendAll( gamePtr, true,
                                                           m_filter, false );
                        gamePtr.release();
                        DbgUtils.logdf( "ResendTask.doInBackground(): sent %d "
                                        + "messages for rowid %d", nSent, rowid );
                    } else {
                        DbgUtils.logdf( "ResendTask.doInBackground(): loadMakeGame()"
                                        + " failed for rowid %d", rowid );
                    }
                    lock.unlock();
                } else {
                    DbgUtils.logf( "ResendTask.doInBackground: unable to unlock %d",
                                   rowid );
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute( Void unused )
        {
            if ( m_showUI ) {
                int nSent = null == m_sink ? 0 : m_sink.numSent();
                String msg =
                    LocUtils.getQuantityString( m_context,
                                                R.plurals.resend_finished_fmt,
                                                nSent, nSent );
                DbgUtils.showf( m_context, msg );
            }
        }
    }
}
