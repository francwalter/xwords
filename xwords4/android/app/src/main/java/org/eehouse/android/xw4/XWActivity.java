/* -*- compile-command: "find-and-gradle.sh installXw4Debug"; -*- */
/*
 * Copyright 2014-2016 by Eric House (xwords@eehouse.org).  All rights
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
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

import org.eehouse.android.xw4.DlgDelegate.Action;

import junit.framework.Assert;

public class XWActivity extends FragmentActivity
    implements Delegator, DlgDelegate.DlgClickNotify {
    private static final String TAG = XWActivity.class.getSimpleName();

    private DelegateBase m_dlgt;

    protected void onCreate( Bundle savedInstanceState, DelegateBase dlgt )
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onCreate(this=%H)", this );
        }
        super.onCreate( savedInstanceState );
        m_dlgt = dlgt;

        Assert.assertTrue( getApplicationContext() == XWApp.getContext() );

        int layoutID = m_dlgt.getLayoutID();
        if ( 0 < layoutID ) {
            m_dlgt.setContentView( layoutID );
        }

        dlgt.init( savedInstanceState );
    }

    @Override
    protected void onSaveInstanceState( Bundle outState )
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onSaveInstanceState(this=%H)", this );
        }
        m_dlgt.onSaveInstanceState( outState );
        super.onSaveInstanceState( outState );
    }

    @Override
    protected void onPause()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onPause(this=%H)", this );
        }
        m_dlgt.onPause();
        super.onPause();
        WiDirWrapper.activityPaused( this );
    }

    @Override
    protected void onResume()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onResume(this=%H)", this );
        }
        super.onResume();
        WiDirWrapper.activityResumed( this );
        m_dlgt.onResume();
    }

    @Override
    protected void onPostResume()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onPostResume(this=%H)", this );
        }
        super.onPostResume();
    }

    @Override
    protected void onStart()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "%s.onStart(this=%H)", this );
        }
        super.onStart();
        m_dlgt.onStart();
    }

    @Override
    protected void onStop()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "%s.onStop(this=%H)", this );
        }
        m_dlgt.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        if ( XWApp.LOG_LIFECYLE ) {
            Log.i( TAG, "onDestroy(this=%H)", this );
        }
        m_dlgt.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String perms[], int[] rslts )
    {
        Perms23.gotPermissionResult( this, requestCode, perms, rslts );
        super.onRequestPermissionsResult( requestCode, perms, rslts );
    }

    @Override
    public void onWindowFocusChanged( boolean hasFocus )
    {
        super.onWindowFocusChanged( hasFocus );
        m_dlgt.onWindowFocusChanged( hasFocus );
    }

    @Override
    public void onBackPressed() {
        if ( !m_dlgt.handleBackPressed() ) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu )
    {
        return m_dlgt.onCreateOptionsMenu( menu );
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu )
    {
        return m_dlgt.onPrepareOptionsMenu( menu )
            || super.onPrepareOptionsMenu( menu );
    } // onPrepareOptionsMenu

    @Override
    public boolean onOptionsItemSelected( MenuItem item )
    {
        return m_dlgt.onOptionsItemSelected( item )
            || super.onOptionsItemSelected( item );
    }

    @Override
    public void onCreateContextMenu( ContextMenu menu, View view,
                                     ContextMenuInfo menuInfo )
    {
        m_dlgt.onCreateContextMenu( menu, view, menuInfo );
    }

    @Override
    public boolean onContextItemSelected( MenuItem item )
    {
        return m_dlgt.onContextItemSelected( item );
    }

    @Override
    protected Dialog onCreateDialog( int id )
    {
        Dialog dialog = super.onCreateDialog( id );
        Assert.assertNull( dialog );
        if ( null == dialog ) {
            dialog = m_dlgt.onCreateDialog( id );
        }
        return dialog;
    } // onCreateDialog

    @Override
    public void onPrepareDialog( int id, Dialog dialog )
    {
        super.onPrepareDialog( id, dialog );
        m_dlgt.prepareDialog( DlgID.values()[id], dialog );
    }

    @Override
    public void onConfigurationChanged( Configuration newConfig )
    {
        m_dlgt.orientationChanged();
        super.onConfigurationChanged( newConfig );
    }

    @Override
    protected void onActivityResult( int requestCode, int resultCode,
                                     Intent data )
    {
        RequestCode rc = RequestCode.values()[requestCode];
        m_dlgt.onActivityResult( rc, resultCode, data );
    }

    // This are a hack! I need some way to build fragment-based alerts from
    // inside fragment-based alerts.
    public DlgDelegate.NotAgainBuilder makeNotAgainBuilder( String msg, int keyId )
    {
        return m_dlgt.makeNotAgainBuilder( msg, keyId );
    }

    public DlgDelegate.ConfirmThenBuilder makeConfirmThenBuilder( int msgID,
                                                                  Action action )
    {
        return m_dlgt.makeConfirmThenBuilder( msgID, action );
    }

    //////////////////////////////////////////////////////////////////////
    // Delegator interface
    //////////////////////////////////////////////////////////////////////
    public Activity getActivity()
    {
        return this;
    }

    public Bundle getArguments()
    {
        return getIntent().getExtras();
    }

    public ListView getListView()
    {
        ListView view = (ListView)findViewById( android.R.id.list );
        return view;
    }

    public void setListAdapter( ListAdapter adapter )
    {
        getListView().setAdapter( adapter );
    }

    public ListAdapter getListAdapter()
    {
        return getListView().getAdapter();
    }

    public boolean inDPMode() {
        return false;
    }

    public void addFragment( XWFragment fragment, Bundle extras )
    {
        Assert.fail();
    }

    public void addFragmentForResult( XWFragment fragment, Bundle extras,
                                      RequestCode request  )
    {
        Assert.fail();
    }

    protected void show( DialogFragment df )
    {
        df.show( getSupportFragmentManager(), "dialog" );
    }

    protected Dialog makeDialog( DBAlert alert, Object[] params )
    {
        return m_dlgt.makeDialog( alert, params );
    }

    ////////////////////////////////////////////////////////////
    // DlgClickNotify interface
    ////////////////////////////////////////////////////////////
    @Override
    public boolean onPosButton( Action action, Object[] params )
    {
        return m_dlgt.onPosButton( action, params );
    }

    @Override
    public boolean onNegButton( Action action, Object[] params )
    {
        return m_dlgt.onNegButton( action, params );
    }

    @Override
    public boolean onDismissed( Action action, Object[] params )
    {
        return m_dlgt.onDismissed( action, params );
    }

    @Override
    public void inviteChoiceMade( Action action, InviteMeans means, Object[] params )
    {
        m_dlgt.inviteChoiceMade( action, means, params );
    }
}
