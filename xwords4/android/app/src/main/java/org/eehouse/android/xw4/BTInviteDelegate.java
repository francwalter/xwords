/* -*- compile-command: "find-and-gradle.sh insXw4Deb"; -*- */
/*
 * Copyright 2009 - 2016 by Eric House (xwords@eehouse.org).  All rights
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
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import org.eehouse.android.xw4.DBUtils.SentInvitesInfo;
import org.eehouse.android.xw4.DlgDelegate.Action;

import java.util.Iterator;
import java.util.Set;

public class BTInviteDelegate extends InviteDelegate {
    private static final String TAG = BTInviteDelegate.class.getSimpleName();
    private static final String KEY_PAIRS = TAG + "_pairs";
    private static final int[] BUTTONIDS = { R.id.button_scan,
                                             R.id.button_settings,
    };
    private Activity m_activity;
    private TwoStringPair[] m_pairs;
    private ProgressDialog m_progress;

    public static void launchForResult( Activity activity, int nMissing,
                                        SentInvitesInfo info,
                                        RequestCode requestCode )
    {
        Assert.assertTrue( 0 < nMissing ); // don't call if nMissing == 0
        Intent intent = new Intent( activity, BTInviteActivity.class );
        intent.putExtra( INTENT_KEY_NMISSING, nMissing );
        if ( null != info ) {
            String lastDev = info.getLastDev( InviteMeans.BLUETOOTH );
            if ( null != lastDev ) {
                intent.putExtra( INTENT_KEY_LASTDEV, lastDev );
            }
        }
        activity.startActivityForResult( intent, requestCode.ordinal() );
    }

    protected BTInviteDelegate( Delegator delegator, Bundle savedInstanceState )
    {
        super( delegator, savedInstanceState );
        m_activity = delegator.getActivity();
    }

    @Override
    protected void init( Bundle savedInstanceState )
    {
        String msg = getQuantityString( R.plurals.invite_bt_desc_fmt_2, m_nMissing,
                                        m_nMissing );
        super.init( msg, 0 );
        addButtonBar( R.layout.bt_buttons, BUTTONIDS );

        m_pairs = loadPairs();
        if ( m_pairs == null || m_pairs.length == 0 ) {
            scan();
        } else {
            updateListAdapter( m_pairs );
        }
    }

    @Override
    protected void onBarButtonClicked( int id )
    {
        switch( id ) {
        case R.id.button_scan:
            scan();
            break;
        case R.id.button_settings:
            BTService.openBTSettings( m_activity );
            break;
        }
    }

    // MultiService.MultiEventListener interface
    @Override
    public void eventOccurred( MultiService.MultiEvent event, final Object ... args )
    {
        switch( event ) {
        case SCAN_DONE:
            post( new Runnable() {
                    public void run() {
                        m_progress.cancel();

                        if ( null == m_pairs || m_pairs.length == 0 ) {
                            makeNotAgainBuilder( R.string.not_again_emptybtscan,
                                                 R.string.key_notagain_emptybtscan )
                                .show();
                        }
                    }
                } );
            break;
        case HOST_PONGED:
            post( new Runnable() {
                    @Override
                    public void run() {
                        processScanResult( (BluetoothDevice)args[0] );
                    }
                } );
            break;
        default:
            super.eventOccurred( event, args );
        }
    }

    @Override
    protected void onChildAdded( View child, InviterItem data )
    {
        TwoStrsItem item = (TwoStrsItem)child;
        TwoStringPair pair = (TwoStringPair)data;
        // null: we don't display mac address
        ((TwoStrsItem)child).setStrings( pair.str2, null );
    }

    @Override
    protected void tryEnable()
    {
        super.tryEnable();

        Button button = (Button)findViewById( R.id.button_clear );
        if ( null != button ) { // may not be there yet
            button.setEnabled( 0 < getChecked().size() );
        }
    }

    private void scan()
    {
        int count = BTService.getPairedCount( m_activity );
        if ( 0 < count ) {
            m_pairs = null;
            updateListAdapter( null );

            String msg = getQuantityString( R.plurals.bt_scan_progress_fmt, count, count );
            m_progress = ProgressDialog.show( m_activity, msg, null, true, true );

            BTService.scan( m_activity, 5000 );
        } else {
            makeConfirmThenBuilder( R.string.bt_no_devs,
                                    Action.OPEN_BT_PREFS_ACTION )
                .setPosButton( R.string.button_go_settings )
                .show();
        }
    }

    private void processScanResult( BluetoothDevice dev )
    {
        DbgUtils.assertOnUIThread();

        m_pairs = TwoStringPair.add( m_pairs, dev.getAddress(), dev.getName() );
        storePairs( m_pairs );

        updateListAdapter( m_pairs );
        tryEnable();
    }

    private TwoStringPair[] loadPairs()
    {
        TwoStringPair[] pairs = null;
        try {
            String str64 = DBUtils.getStringFor( m_activity, KEY_PAIRS, null );
            pairs = (TwoStringPair[])Utils.string64ToSerializable( str64 );
        } catch ( Exception ex ) {} // NPE, de-serialization problems, etc.
        return pairs;
    }

    private void storePairs( TwoStringPair[] pairs )
    {
        String str64 = pairs == null
            ? "" : Utils.serializableToString64( pairs );
        DBUtils.setStringFor( m_activity, KEY_PAIRS, str64 );
    }

    // DlgDelegate.DlgClickNotify interface
    @Override
    public boolean onPosButton( Action action, Object[] params )
    {
        boolean handled = true;
        switch( action ) {
        case OPEN_BT_PREFS_ACTION:
            BTService.openBTSettings( m_activity );
            break;
        default:
            handled = super.onPosButton( action, params );
        }
        return handled;
    }
}
