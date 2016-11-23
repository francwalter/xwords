/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2011 by Eric House (xwords@eehouse.org).  All rights
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
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import junit.framework.Assert;

import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.jni.JNIThread;

public class ChatDelegate extends DelegateBase {
    private static final String TAG = ChatDelegate.class.getSimpleName();

    private static final String INTENT_KEY_PLAYER = "intent_key_player";
    private static final String INTENT_KEY_NAMES = "intent_key_names";
    private static final String INTENT_KEY_LOCS = "intent_key_locs";

    private static ChatDelegate s_visibleThis;
    private long m_rowid;
    private int m_curPlayer;
    private String[] m_names;
    private Activity m_activity;
    private EditText m_edit;
    private TableLayout m_layout;
    private ScrollView m_scroll;
    private JNIThread m_jniThreadRef;

    public ChatDelegate( Delegator delegator, Bundle savedInstanceState )
    {
        super( delegator, savedInstanceState, R.layout.chat, R.menu.chat_menu );
        m_activity = delegator.getActivity();
    }

    @Override
    protected void init( Bundle savedInstanceState )
    {
        m_edit = (EditText)findViewById( R.id.chat_edit );
        m_edit.addTextChangedListener( new TextWatcher() {
                public void afterTextChanged( Editable s ) {
                    invalidateOptionsMenuIf();
                }
                public void beforeTextChanged( CharSequence s, int st,
                                               int cnt, int a ) {}
                public void onTextChanged( CharSequence s, int start,
                                           int before, int count ) {}
            } );

        Bundle args = getArguments();
        m_rowid = args.getLong( GameUtils.INTENT_KEY_ROWID, -1 );
        m_curPlayer = args.getInt( INTENT_KEY_PLAYER, -1 );
        m_names = args.getStringArray( INTENT_KEY_NAMES );
        boolean[] locals = args.getBooleanArray( INTENT_KEY_LOCS );

        m_scroll = (ScrollView)findViewById( R.id.scroll );
        m_layout = (TableLayout)findViewById( R.id.chat_history );

        // OnLayoutChangeListener added in API 11
        if ( 11 <= Integer.valueOf( android.os.Build.VERSION.SDK ) ) {
            m_layout.addOnLayoutChangeListener( new OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange( View vv, int ll, int tt, int rr,
                                                int bb, int ol, int ot,
                                                int or, int ob ) {
                        scrollDown();
                    }
                });
        }

        Button sendButton = (Button)findViewById( R.id.chat_send );
        if ( ABUtils.haveActionBar() ) {
            sendButton.setVisibility( View.GONE );
        } else {
            sendButton.setOnClickListener( new View.OnClickListener() {
                    public void onClick( View view ) {
                        handleSend();
                    }
                } );
        }

        DBUtils.HistoryPair[] pairs
            = DBUtils.getChatHistory( m_activity, m_rowid, locals );
        if ( null != pairs ) {
            for ( DBUtils.HistoryPair pair : pairs ) {
                addRow( pair.msg, pair.playerIndx );
            }
        }

        String title = getString( R.string.chat_title_fmt,
                                  GameUtils.getName( m_activity, m_rowid ) );
        setTitle( title );
    } // init

    @Override
    protected void onResume()
    {
        super.onResume();
        m_jniThreadRef = JNIThread.getRetained( m_rowid );
        if ( null == m_jniThreadRef ) {
            DbgUtils.logw( getClass(), "onResume(): m_jniThreadRef null; exiting" );
            finish();
        } else {
            s_visibleThis = this;
            int[] startAndEnd = new int[2];
            String curMsg = DBUtils.getCurChat( m_activity, m_rowid,
                                                m_curPlayer, startAndEnd );
            if ( null != curMsg && 0 < curMsg.length() ) {
                m_edit.setText( curMsg );
                m_edit.setSelection( startAndEnd[0], startAndEnd[1] );
            }
        }
    }

    @Override
    protected void onPause()
    {
        if ( null != m_jniThreadRef ) {
            m_jniThreadRef.release();
        }
        s_visibleThis = null;

        String curText = m_edit.getText().toString();
        DBUtils.setCurChat( m_activity, m_rowid, m_curPlayer, curText,
                            m_edit.getSelectionStart(),
                            m_edit.getSelectionEnd() );

        super.onPause();
    }

    private void addRow( String msg, int playerIndx )
    {
        TableRow row = (TableRow)inflate( R.layout.chat_row );
        if ( m_curPlayer == playerIndx ) {
            row.setBackgroundColor(0xFF202020);
        }
        TextView view = (TextView)row.findViewById( R.id.chat_row_text );
        view.setText( msg );
        view = (TextView)row.findViewById( R.id.chat_row_name );
        view.setText( getString( R.string.chat_sender_fmt,
                                 m_names[playerIndx] ) );
        m_layout.addView( row );

        scrollDown();
    }

    private void scrollDown()
    {
        m_scroll.post( new Runnable() {
                @Override
                public void run() {
                    m_scroll.fullScroll( View.FOCUS_DOWN );
                }
            });
    }

    private void handleSend() {
        String text = m_edit.getText().toString();
        DBUtils.appendChatHistory( m_activity, m_rowid, text, m_curPlayer );
        addRow( text, m_curPlayer );
        m_edit.setText( null );

        m_jniThreadRef.sendChat( text );
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu )
    {
        String text = m_edit.getText().toString();
        boolean haveText = null != text && 0 < text.length();
        Utils.setItemVisible( menu, R.id.chat_menu_send, haveText );
        return true;
    }

    @Override
    public boolean onOptionsItemSelected( MenuItem item )
    {
        boolean handled = true;
        switch ( item.getItemId() ) {
        case R.id.chat_menu_clear:
            if ( handled ) {
                makeConfirmThenBuilder( R.string.confirm_clear_chat, Action.CLEAR_ACTION )
                    .show();
            }
            break;
        case R.id.chat_menu_send:
            handleSend();
            break;
        default:
            handled = false;
            break;
        }
        return handled;
    }

    @Override
    public void dlgButtonClicked( Action action, int which, Object[] params )
    {
        switch ( action ) {
        case CLEAR_ACTION:
            if ( AlertDialog.BUTTON_POSITIVE == which ) {
                DBUtils.clearChatHistory( m_activity, m_rowid );
                TableLayout layout =
                    (TableLayout)findViewById( R.id.chat_history );
                layout.removeAllViews();
            }
            break;
        default:
            super.dlgButtonClicked( action, which, params );
        }
    }

    public static boolean append( long rowid, String msg, int fromIndx )
    {
        boolean handled = null != s_visibleThis
            && s_visibleThis.m_rowid == rowid;
        if ( handled ) {
            s_visibleThis.addRow( msg, fromIndx );
            Utils.playNotificationSound( s_visibleThis.m_activity );
        }
        return handled;
    }

    public static void start( Delegator delegator,
                              long rowID, int curPlayer,
                              String[] names, boolean[] locs )
    {
        Assert.assertFalse( -1 == curPlayer );
        Bundle bundle = new Bundle();
        bundle.putLong( GameUtils.INTENT_KEY_ROWID, rowID );
        bundle.putInt( INTENT_KEY_PLAYER, curPlayer );
        bundle.putStringArray( INTENT_KEY_NAMES, names );
        bundle.putBooleanArray( INTENT_KEY_LOCS, locs );

        if ( delegator.inDPMode() ) {
            delegator.addFragment( ChatFrag.newInstance( delegator ), bundle );
        } else {
            Activity activity = delegator.getActivity();
            Intent intent = new Intent( activity, ChatActivity.class );
            intent.putExtras( bundle );
            activity.startActivity( intent );
        }
    }
}
