/* -*- compile-command: "find-and-gradle.sh insXw4Debug"; -*- */
/*
 * Copyright 2017 by Eric House (xwords@eehouse.org).  All rights reserved.
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
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.DlgDelegate.ActionPair;

import junit.framework.Assert;

import org.eehouse.android.xw4.loc.LocUtils;
import android.support.v4.app.DialogFragment;

/** Abstract superclass for Alerts that have moved from and are still created
 * inside DlgDelegate
 */
public class DlgDelegateAlert extends DialogFragment {
    private static final String TAG = DlgDelegateAlert.class.getSimpleName();
    private static final String STATE_KEY = "STATE_KEY";
    private DlgState m_state;

    public DlgDelegateAlert() {}

    protected final DlgState getState( Bundle sis )
    {
        if ( null != sis ) {
            m_state = (DlgState)sis.getParcelable( STATE_KEY );
        } else {
            Bundle args = getArguments();
            Assert.assertNotNull( args );
            m_state = DlgState.fromBundle( args );
        }
        return m_state;
    }

    protected void addStateArgument( DlgState state )
    {
        setArguments( state.toBundle() );
    }

    @Override
    public void onSaveInstanceState( Bundle bundle )
    {
        super.onSaveInstanceState( bundle );
        bundle.putParcelable( STATE_KEY, m_state );
    }

    protected void checkNotAgainCheck( DlgState state, NotAgainView naView )
    {
        if ( null != naView && naView.getChecked() ) {
            DbgUtils.logd( TAG, "is checked" );
            if ( 0 != state.m_prefsKey ) {
                XWPrefs.setPrefsBoolean( getActivity(), m_state.m_prefsKey,
                                         true );
            } else if ( null != state.m_onNAChecked ) {
                XWActivity activity = (XWActivity)getActivity();
                activity.onPosButton( m_state.m_onNAChecked, null );
            }
        }
    }

    protected OnClickListener mkCallbackClickListener( final NotAgainView naView )
    {
        OnClickListener cbkOnClickLstnr;
        cbkOnClickLstnr = new OnClickListener() {
                public void onClick( DialogInterface dlg, int button ) {
                    checkNotAgainCheck( m_state, naView );

                    Activity activity = getActivity();
                    if ( Action.SKIP_CALLBACK != m_state.m_action
                         && activity instanceof XWActivity ) {
                        XWActivity xwact = (XWActivity)activity;
                        switch ( button ) {
                        case AlertDialog.BUTTON_POSITIVE:
                            xwact.onPosButton( m_state.m_action, m_state.m_params );
                            break;
                        case AlertDialog.BUTTON_NEGATIVE:
                            xwact.onNegButton( m_state.m_action, m_state.m_params );
                            break;
                        default:
                            DbgUtils.loge( TAG, "unexpected button %d",
                                           button );
                            // ignore on release builds
                            Assert.assertFalse( BuildConfig.DEBUG );
                        }
                    }
                }
            };
        return cbkOnClickLstnr;
    }
}