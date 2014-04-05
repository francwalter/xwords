/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2014 by Eric House (xwords@eehouse.org).  All rights
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

package org.eehouse.android.xw4.loc;

import android.widget.LinearLayout;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;
import android.widget.EditText;
import android.view.View;
import android.view.View.OnFocusChangeListener;

import junit.framework.Assert;

import org.eehouse.android.xw4.R;
import org.eehouse.android.xw4.Utils;
import org.eehouse.android.xw4.DbgUtils;

public class LocListItem extends LinearLayout implements OnFocusChangeListener {

    private Context m_context;
    private String m_key;
    private int m_position;
    private EditText m_edit;
    private String m_xlation;

    public LocListItem( Context cx, AttributeSet as ) 
    {
        super( cx, as );
        m_context = cx;
    }

    @Override
    protected void onFinishInflate()
    {
        super.onFinishInflate();
        m_edit = (EditText)findViewById( R.id.xlated_view ); 
        m_edit.setOnFocusChangeListener( this );
    }

    private void setEnglish()
    {
        int id = LocIDs.get( m_key );
        String str = m_context.getString( id );
        TextView tv = (TextView)findViewById( R.id.english_view );
        tv.setText( str );
        DbgUtils.logf( "setEnglish: set to %s", str );
    }

    private void setXlated()
    {
        m_xlation = LocUtils.getXlation( m_context, m_key );
        if ( null != m_xlation ) {
            m_edit.setText( m_xlation );
        }
    }

    public void onFocusChange( View view, boolean hasFocus )
    {
        Assert.assertTrue( view == m_edit );
        if ( !hasFocus ) {
            CharSequence txt = m_edit.getText();
            DbgUtils.logf( "view with text %s lost focus", txt );
            if ( ! txt.equals( m_xlation ) ) {
                LocUtils.setXlation( m_context, m_key, txt.toString() );
            }
        }
    }

    protected static LocListItem create( Context context, String key, 
                                         int position )
    {
        LocListItem result = 
            (LocListItem)Utils.inflate( context, R.layout.loc_list_item );
        result.m_key = key;
        result.m_position = position;

        result.setEnglish();
        result.setXlated();
        return result;
    }
}
