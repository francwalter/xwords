/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
/*
 * Copyright 2009 - 2020 by Eric House (xwords@eehouse.org).  All rights
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface;

import java.io.Serializable;

import org.eehouse.android.xw4.DBUtils.SentInvitesInfo;
import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.Perms23.Perm;
import org.eehouse.android.xw4.loc.LocUtils;

class InvitesNeededAlert implements DialogInterface.OnDismissListener {
    private static final String TAG = InvitesNeededAlert.class.getSimpleName();

    private DelegateBase mDelegate;
    private State mState;
    private DBAlert mAlert;

    static class Wrapper {
        private Callbacks mCallbacks;
        private InvitesNeededAlert mSelf;

        Wrapper( Callbacks callbacks ) { mCallbacks = callbacks; }

        void showOrHide( int nDevsSeen, int nPlayersMissing, boolean isRematch )
        {
            DbgUtils.assertOnUIThread();
            Log.d( TAG, "showOnceIf(nDevsSeen=%d, nPlayersMissing=%d); self: %s",
                   nDevsSeen, nPlayersMissing, mSelf );

            if ( null == mSelf && 0 == nPlayersMissing ) {
                // cool: need and have nothing, so do nothing
            } else if ( 0 < nPlayersMissing && null == mSelf ) { // Need but don't have
                makeNew( nDevsSeen, nPlayersMissing, isRematch );
            } else if ( 0 == nPlayersMissing && null != mSelf ) { // Have and need to close
                mSelf.close();
            } else if ( null != mSelf && nPlayersMissing != mSelf.mState.mNPlayersMissing ) {
                mSelf.close();
                makeNew( nDevsSeen, nPlayersMissing, isRematch );
            } else if ( null != mSelf && nPlayersMissing == mSelf.mState.mNPlayersMissing ) {
                // nothing to do
            } else {
                Assert.failDbg();
            }
        }

        Dialog make( DBAlert alert, Object[] params )
        {
            DbgUtils.assertOnUIThread();
            return mSelf.makeImpl( mCallbacks, alert, params );
        }

        void dismiss()
        {
            Log.d( TAG, "dismiss()" );
            DbgUtils.assertOnUIThread();
            if ( null != mSelf ) {
                mSelf.close();
                mSelf = null;
            }
        }

        private void makeNew( int nDevsSeen, int nPlayersMissing, boolean isRematch )
        {
            Log.d( TAG, "makeNew(nDevsSeen=%d, nPlayersMissing=%d)", nDevsSeen, nPlayersMissing );
            State state = new State( nDevsSeen, nPlayersMissing, isRematch );
            mSelf = new InvitesNeededAlert( mCallbacks.getDelegate(), state );
            mCallbacks.getDelegate().showDialogFragment( DlgID.DLG_INVITE, state );
        }
    }

    // Must be kept separate from this because gets passed as param to
    // showDialogFragment()
    private static class State implements Serializable {
        private int mNDevsSeen;
        private int mNPlayersMissing;
        private boolean mIsRematch;

        State( int nDevs, int nPlayers, boolean rematch )
        {
            mNDevsSeen = nDevs;
            mNPlayersMissing = nPlayers;
            mIsRematch = rematch;
        }
    }

    interface Callbacks {
        DelegateBase getDelegate();
        long getRowID();
        void onCloseClicked();
        void onInviteClicked();
        void onInfoClicked();
    }

    ////////////////////////////////////////
    // DialogInterface.OnDismissListener
    ////////////////////////////////////////
    @Override
    public void onDismiss( DialogInterface dialog )
    {
        Log.d( TAG, "onDismiss()" );
        close();
    }

    private void close()
    {
        DbgUtils.assertOnUIThread();
        if ( null != mAlert ) {
            InviteChoicesAlert.dismissAny();
            mAlert.dismiss();
        }
    }

    private InvitesNeededAlert( DelegateBase delegate, State state )
    {
        DbgUtils.assertOnUIThread();
        mDelegate = delegate;
        mState = state;
    }

    private Dialog makeImpl( final Callbacks callbacks, final DBAlert alert,
                             Object[] params )
    {
        Dialog result = null;
        State state = (State)params[0];
        mAlert = alert;

        Context context = mDelegate.getActivity();
        String title;

        boolean isRematch = state.mIsRematch;
        final int nPlayersMissing = state.mNPlayersMissing;
        if ( isRematch ) {
            title = LocUtils.getString( context, R.string.waiting_rematch_title );
        } else {
            title = LocUtils
                .getQuantityString( context, R.plurals.waiting_title_fmt,
                                    nPlayersMissing, nPlayersMissing );
        }

        String message = LocUtils
            .getQuantityString( context, R.plurals.invite_msg_fmt,
                                nPlayersMissing, nPlayersMissing );
        message += "\n\n"
            + LocUtils.getString( context, R.string.invite_msg_extra );

        if ( isRematch ) {
            message += "\n\n"
                + LocUtils.getString( context, R.string.invite_msg_extra_rematch );
        }

        AlertDialog.Builder ab = mDelegate.makeAlertBuilder()
            .setTitle( title )
            .setMessage( message );
        
        alert.setNoDismissListenerPos( ab, R.string.newgame_invite,
                                       new OnClickListener() {
                                           @Override
                                           public void onClick( DialogInterface dlg, int item ) {
                                               callbacks.onInviteClicked();
                                           }
                                       } );

        if ( BuildConfig.NON_RELEASE ) {
            long rowid = callbacks.getRowID();
            SentInvitesInfo sentInfo = DBUtils.getInvitesFor( context, rowid );
            int nSent = sentInfo.getMinPlayerCount();
            boolean invitesSent = nSent >= nPlayersMissing;
            if ( invitesSent ) {
                alert.setNoDismissListenerNeut( ab, R.string.newgame_invite_more,
                                                new OnClickListener() {
                                                    @Override
                                                    public void onClick( DialogInterface dlg, int item ) {
                                                        callbacks.onInfoClicked();
                                                    }
                                                } );
            }
        }

        alert.setNoDismissListenerNeg( ab, R.string.button_close,
                                       new OnClickListener() {
                                           @Override
                                           public void onClick( DialogInterface dlg, int item ) {
                                               callbacks.onCloseClicked();
                                           }
                                       } );

        result = ab.create();
        result.setOnDismissListener( this );
        return result;
    }
}
