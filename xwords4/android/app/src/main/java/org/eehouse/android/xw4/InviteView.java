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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.eehouse.android.xw4.DlgDelegate.DlgClickNotify.InviteMeans;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;
import org.eehouse.android.xw4.loc.LocUtils;

public class InviteView extends ScrollView
    implements RadioGroup.OnCheckedChangeListener {

    private static final String TAG = InviteView.class.getSimpleName();
    private static final String KEY_EXPANDED = TAG + ":expanded";
    private static final int QRCODE_SIZE_SMALL = 320;
    private static final int QRCODE_SIZE_LARGE = QRCODE_SIZE_SMALL * 2;
    
    public interface ItemClicked {
        public void meansClicked( InviteMeans means );
    }

    private ItemClicked mProcs;
    private boolean mIsWho;
    private RadioGroup mGroupTab;
    private RadioGroup mGroupWho;
    private RadioGroup mGroupHow;
    private Map<RadioButton, InviteMeans> mHowMeans = new HashMap<>();
    private Map<RadioButton, String> mWhoPlayers = new HashMap<>();
    private boolean mExpanded = false;
    private NetLaunchInfo mNli;

    public InviteView( Context context, AttributeSet as ) {
        super( context, as );
    }

    public InviteView setChoices( List<InviteMeans> meansList, int sel,
                                  String[] players )
    {
        final Context context = getContext();

        boolean haveWho = null != players && 0 < players.length;

        // top/horizontal group or title first
        if ( haveWho ) {
            mGroupTab = (RadioGroup)findViewById( R.id.group_tab );
            mGroupTab.check( R.id.radio_how );
            mGroupTab.setOnCheckedChangeListener( this );
            mGroupTab.setVisibility( View.VISIBLE );
        } else {
            findViewById( R.id.title_tab ).setVisibility( View.VISIBLE );
        }

        mGroupHow = (RadioGroup)findViewById( R.id.group_how );
        mGroupHow.setOnCheckedChangeListener( this );
        final View divider = mGroupHow.findViewById( R.id.local_divider );
        for ( InviteMeans means : meansList ) {
            Assert.assertNotNull( means );
            RadioButton button = (RadioButton)LocUtils
                .inflate( context, R.layout.invite_radio );
            button.setText( LocUtils.getString( context, means.getUserDescID() ) );
            int where = means.isForLocal()
                // -2: place before QR code and its explanatory text
                ? mGroupHow.getChildCount() - 2
                : mGroupHow.indexOfChild( divider );
            mGroupHow.addView( button, where );
            mHowMeans.put( button, means );
        }

        if ( haveWho ) {
            mGroupWho = (RadioGroup)findViewById( R.id.group_who );
            mGroupWho.setOnCheckedChangeListener( this );
            for ( String player : players ) {
                RadioButton button = (RadioButton)LocUtils
                    .inflate( context, R.layout.invite_radio );
                button.setText( player );
                mGroupWho.addView( button );
                mWhoPlayers.put( button, player );
            }
        }
        mIsWho = false;   // start with how
        showWhoOrHow();

        mExpanded = DBUtils.getBoolFor( context, KEY_EXPANDED, false );
        ((ExpandImageButton)findViewById( R.id.expander ))
            .setOnExpandChangedListener( new ExpandImageButton.ExpandChangeListener() {
                    @Override
                    public void expandedChanged( boolean nowExpanded )
                    {
                        mExpanded = nowExpanded;
                        DBUtils.setBoolFor( context, KEY_EXPANDED, nowExpanded );
                        startQRCodeThread( null );
                    }
                } )
            .setExpanded( mExpanded );

        return this;
    }

    public InviteView setNli( NetLaunchInfo nli )
    {
        startQRCodeThread( nli );
        return this;
    }

    public InviteView setCallbacks( ItemClicked procs ) {
        mProcs = procs;
        return this;
    }

    public Object getChoice()
    {
        Object result = null;
        RadioButton checked = getCurCheckedFor();
        if ( null != checked ) {
            // result = new InviteChoice();
            if ( mIsWho ) {
                result = mWhoPlayers.get(checked);
            } else {
                result = mHowMeans.get(checked);
            }
        }
        return result;
    }

    @Override
    public void onCheckedChanged( RadioGroup group, int checkedId )
    {
        if ( -1 != checkedId ) {
            switch( group.getId() ) {
            case R.id.group_tab:
                mIsWho = checkedId == R.id.radio_who;
                showWhoOrHow();
                break;
            case R.id.group_how:
                RadioButton button = (RadioButton)group.findViewById(checkedId);
                InviteMeans means = mHowMeans.get( button );
                mProcs.meansClicked( means );
                break;
            case R.id.group_who:
                break;
            }
        }
    }

    private RadioButton getCurCheckedFor()
    {
        RadioButton result = null;
        RadioGroup group = mIsWho ? mGroupWho : mGroupHow;
        int curSel = group.getCheckedRadioButtonId();
        if ( 0 <= curSel ) {
            result = (RadioButton)findViewById(curSel);
        }
        return result;
    }

    private void showWhoOrHow()
    {
        if ( null != mGroupWho ) {
            mGroupWho.setVisibility( mIsWho ? View.VISIBLE : View.INVISIBLE );
        }
        mGroupHow.setVisibility( mIsWho ? View.INVISIBLE : View.VISIBLE );

        boolean showEmpty = mIsWho && 0 == mWhoPlayers.size();
        findViewById( R.id.who_empty )
            .setVisibility( showEmpty ? View.VISIBLE : View.INVISIBLE );
    }

    private void startQRCodeThread( NetLaunchInfo nli )
    {
        if ( null != nli ) {
            mNli = nli;
        }
        if ( null != mNli ) {
            final String url = mNli.makeLaunchUri( getContext() ).toString();
            new Thread( new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int qrSize = mExpanded ? QRCODE_SIZE_LARGE : QRCODE_SIZE_SMALL;
                            MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
                            BitMatrix bitMatrix = multiFormatWriter.encode( url, BarcodeFormat.QR_CODE,
                                                                            qrSize, qrSize );
                            final Bitmap bitmap = Bitmap.createBitmap( qrSize, qrSize,
                                                                       Bitmap.Config.ARGB_8888 );
                            for ( int ii = 0; ii < qrSize; ++ii ) {
                                for ( int jj = 0; jj < qrSize; ++jj ) {
                                    bitmap.setPixel( ii, jj, bitMatrix.get(ii, jj)
                                                     ? Color.BLACK : Color.WHITE );
                                }
                            }

                            post( new Runnable() {
                                    @Override
                                    public void run() {
                                        ImageView iv = (ImageView)findViewById( R.id.qr_view );
                                        iv.setImageBitmap( bitmap );
                                        post ( new Runnable() {
                                                @Override
                                                public void run() {
                                                    scrollTo( 0, getBottom() );
                                                }
                                            } );
                                    }
                                } );
                        } catch ( WriterException we ) {
                            Log.ex( TAG, we );
                        }
                    }
                } ).start();
        }
    }
}
