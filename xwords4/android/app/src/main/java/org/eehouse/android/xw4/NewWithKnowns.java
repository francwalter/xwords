/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
/*
 * Copyright 2020 by Eric House (xwords@eehouse.org).  All rights reserved.
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

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import org.eehouse.android.xw4.jni.XwJNI;
import org.eehouse.android.xw4.loc.LocUtils;

public class NewWithKnowns extends LinearLayout
    implements OnItemSelectedListener, RadioGroup.OnCheckedChangeListener
{
    private static final String TAG = NewWithKnowns.class.getSimpleName();
    private static final String KP_NAME_KEY = TAG + "/kp_last_name";
    private static final String KP_PREVSOLO_KEY = TAG + "/kp_prev_solo";
    private static final String KP_PREVNET_KEY = TAG + "/kp_prev_net";

    public interface ButtonChangeListener {
        void onNewButtonText( String txt );
    }

    public interface ButtonCallbacks {
        void onUseKnown( String knownName, String gameName );
        void onStartGame( String gameName, boolean solo, boolean configFirst );
    }

    private ButtonChangeListener mListener;
    private String mCurKnown;
    private int mCurRadio;
    private Spinner mNamesSpinner;
    private boolean mStandalone;

    public NewWithKnowns( Context cx, AttributeSet as )
    {
        super( cx, as );
    }

    void setCallback( ButtonChangeListener listener )
    {
        Assert.assertTrueNR( null == mListener );
        mListener = listener;
    }

    void configure( boolean standalone, String gameName )
    {
        Context context = getContext();
        mStandalone = standalone;
        boolean hasKnowns = !standalone && XwJNI.hasKnownPlayers();
        int[] toHide;
        if ( hasKnowns ) {
            String[] knowns = XwJNI.kplr_getPlayers();
            mCurKnown = DBUtils.getStringFor( context, KP_NAME_KEY,
                                              knowns[0] );
            ArrayAdapter<String> adapter = new
                ArrayAdapter<String>( context,
                                      android.R.layout.simple_spinner_item,
                                      knowns );
            adapter.setDropDownViewResource( android.R.layout
                                             .simple_spinner_dropdown_item );
            mNamesSpinner = (Spinner)findViewById( R.id.names );
            mNamesSpinner.setAdapter( adapter );
            mNamesSpinner.setOnItemSelectedListener( this );
            Assert.assertTrueNR( !TextUtils.isEmpty( mCurKnown ) );
            for ( int ii = 0; ii < knowns.length; ++ii ) {
                if ( knowns[ii].equals( mCurKnown ) ) {
                    mNamesSpinner.setSelection( ii );
                    break;
                }
            }
            toHide = new int[]{ R.id.radio_default, R.id.choose_expl_default,
            };
        } else {
            toHide = new int[]{ R.id.radio_known, R.id.names, R.id.expl_known,
                                R.id.radio_unknown, R.id.choose_expl_new,
            };
        }

        for ( int resID : toHide ) {
            findViewById(resID).setVisibility(View.GONE);
        }

        EditWClear et = (EditWClear)findViewById( R.id.name_edit );
        et.setText( gameName );

        RadioGroup group = (RadioGroup)findViewById( R.id.group );
        group.setOnCheckedChangeListener( this );

        String key = standalone ? KP_PREVSOLO_KEY : KP_PREVNET_KEY;
        int lastSet = DBUtils.getIntFor( context, key, 0 );
        if ( lastSet != 0 ) {
            // Let's made sure it's still a RadioButton. Ids are generated by
            // the build system and can change. Passing a non-radiobutton id
            // to check() still calls onCheckedChanged() etc.
            View view = findViewById( lastSet );
            if ( null != view && view instanceof RadioButton ) {
                group.check( lastSet );
            }
        }
    }

    void onButtonPressed( ButtonCallbacks procs )
    {
        if ( 0 != mCurRadio ) {
            Context context = getContext();
            String gameName = gameName();
            switch ( mCurRadio ) {
            case R.id.radio_known:
                DBUtils.setStringFor( context, KP_NAME_KEY, mCurKnown );
                procs.onUseKnown( mCurKnown, gameName );
                break;
            case R.id.radio_unknown:
            case R.id.radio_default:
                procs.onStartGame( gameName, mStandalone, false );
                break;
            case R.id.radio_configure:
                procs.onStartGame( gameName, mStandalone, true );
                break;
            default:
                Assert.failDbg();   // fired
                break;
            }

            String key = mStandalone ? KP_PREVSOLO_KEY : KP_PREVNET_KEY;
            DBUtils.setIntFor( context, key, mCurRadio );
        }
    }

    private String gameName()
    {
        EditWClear et = (EditWClear)findViewById( R.id.name_edit );
        return et.getText().toString();
    }

    @Override
    public void onItemSelected( AdapterView<?> parent, View view,
                                int pos, long id )
    {
        if ( view instanceof TextView ) {
            TextView tv = (TextView)view;
            mCurKnown = tv.getText().toString();
            onRadioChanged();
        }
    }

    @Override
    public void onNothingSelected( AdapterView<?> parent ) {}

    @Override
    public void onCheckedChanged( RadioGroup group, int checkedId )
    {
        mCurRadio = checkedId;
        onRadioChanged();
    }

    private void onRadioChanged()
    {
        if ( null != mNamesSpinner ) {
            mNamesSpinner.setVisibility( mCurRadio == R.id.radio_known
                                         ? View.VISIBLE : View.GONE );
        }

        Context context = getContext();
        int resId = 0;
        String msg = null;
        switch ( mCurRadio ) {
        case R.id.radio_known:
            msg = LocUtils
                .getString( context, R.string.newgame_invite_fmt, mCurKnown );
            break;
        case R.id.radio_unknown:
        case R.id.radio_default:
            resId = R.string.newgame_open_game;
            break;
        case R.id.radio_configure:
            resId = R.string.newgame_configure_game;
            break;
        default:
            Assert.failDbg();
        }

        if ( 0 != resId ) {
            msg = LocUtils.getString( context, resId );
        }
        if ( null != msg ) {
            ButtonChangeListener listener = mListener;
            if ( null != listener ) {
                listener.onNewButtonText( msg );
            }
        }
    }
}
