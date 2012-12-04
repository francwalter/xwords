/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
/*
 * Copyright 2009-2012 by Eric House (xwords@eehouse.org).  All rights
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

import android.content.Context;
import android.database.DataSetObserver;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;       // class is not synchronized
import java.util.Random;

import junit.framework.Assert;


import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;

public class GameListAdapter extends XWListAdapter {
    private Context m_context;
    private LayoutInflater m_factory;
    private int m_fieldID;
    private Handler m_handler;
    private static final boolean s_isFire;
    private static Random s_random;
    static {
        s_isFire = Build.MANUFACTURER.equals( "Amazon" );
        if ( s_isFire ) {
            s_random = new Random();
        }
    }

    private class ViewInfo implements View.OnClickListener {
        private View m_view;
        private View m_hideable;
        private ExpiringTextView m_name;
        private boolean m_expanded, m_haveTurn, m_haveTurnLocal;
        private long m_rowid;
        private long m_lastMoveTime;
        private ImageButton m_expandButton;

        public ViewInfo( View view, long rowid ) 
        {
            m_view = view;
            m_rowid = rowid; 
            m_lastMoveTime = 0;
        }

        public ViewInfo( View view, long rowid, boolean expanded, 
                         long lastMoveTime, boolean haveTurn, 
                         boolean haveTurnLocal ) {
            this( view, rowid );
            m_expanded = expanded;
            m_lastMoveTime = lastMoveTime;
            m_haveTurn = haveTurn;
            m_haveTurnLocal = haveTurnLocal;
            m_hideable = (LinearLayout)view.findViewById( R.id.hideable );
            m_name = (ExpiringTextView)m_view.findViewById( R.id.game_name );
            m_expandButton = (ImageButton)view.findViewById( R.id.expander );
            m_expandButton.setOnClickListener( this );
            showHide();
        }

        private void showHide()
        {
            m_expandButton.setImageResource( m_expanded ?
                                             R.drawable.expander_ic_maximized :
                                             R.drawable.expander_ic_minimized);
            m_hideable.setVisibility( m_expanded? View.VISIBLE : View.GONE );

            m_name.setBackgroundColor( android.R.color.transparent );
            m_name.setPct( m_handler, m_haveTurn && !m_expanded, 
                           m_haveTurnLocal, m_lastMoveTime );
        }

        public void onClick( View view ) {
            m_expanded = !m_expanded;
            DBUtils.setExpanded( m_rowid, m_expanded );
            showHide();
        }
    }

    private HashMap<Long,ViewInfo> m_viewsCache;
    private DateFormat m_df;
    private LoadItemCB m_cb;


    public interface LoadItemCB {
        public void itemLoaded( long rowid );
        public void itemClicked( long rowid );
    }

    private class LoadItemTask extends AsyncTask<Void, Void, Void> {
        private long m_rowid;
        private Context m_context;
        // private int m_id;
        public LoadItemTask( Context context, long rowid/*, int id*/ )
        {
            m_context = context;
            m_rowid = rowid;
            // m_id = id;
        }

        @Override
        protected Void doInBackground( Void... unused ) 
        {
            // Without this, on the Fire only the last item in the
            // list it tappable.  Likely my fault, but this seems to
            // work around it.
            if ( s_isFire ) {
                try {
                    int sleepTime = 500 + (s_random.nextInt() % 500);
                    Thread.sleep( sleepTime );
                } catch ( Exception e ) {
                }
            }
            View layout = m_factory.inflate( R.layout.game_list_item, null );
            boolean hideTitle = false;//CommonPrefs.getHideTitleBar(m_context);
            GameSummary summary = DBUtils.getSummary( m_context, m_rowid, 1500 );
            if ( null == summary ) {
                m_rowid = -1;
            } else {
                String state = summary.summarizeState();

                TextView view = (TextView)layout.findViewById( R.id.game_name );
                if ( hideTitle ) {
                    view.setVisibility( View.GONE );
                } else {
                    String value = null;
                    switch ( m_fieldID ) {
                    case R.string.game_summary_field_empty:
                        break;
                    case R.string.game_summary_field_language:
                        value = 
                            DictLangCache.getLangName( m_context, 
                                                       summary.dictLang );
                        break;
                    case R.string.game_summary_field_opponents:
                        value = summary.playerNames();
                        break;
                    case R.string.game_summary_field_state:
                        value = state;
                        break;
                    }

                    String name = GameUtils.getName( m_context, m_rowid );

                    if ( null != value ) {
                        value = m_context.getString( R.string.str_game_namef, 
                                                     name, value );
                    } else {
                        value = name;
                    }
                        
                    view.setText( value );
                }

                layout.setOnClickListener( new View.OnClickListener() {
                        @Override
                        public void onClick( View v ) {
                            m_cb.itemClicked( m_rowid );
                        }
                    } );

                LinearLayout list =
                    (LinearLayout)layout.findViewById( R.id.player_list );
                boolean haveATurn = false;
                boolean haveALocalTurn = false;
                boolean[] isLocal = new boolean[1];
                for ( int ii = 0; ii < summary.nPlayers; ++ii ) {
                    ExpiringLinearLayout tmp = (ExpiringLinearLayout)
                        m_factory.inflate( R.layout.player_list_elem, null );
                    view = (TextView)tmp.findViewById( R.id.item_name );
                    view.setText( summary.summarizePlayer( ii ) );
                    view = (TextView)tmp.findViewById( R.id.item_score );
                    view.setText( String.format( "  %d", summary.scores[ii] ) );
                    boolean thisHasTurn = summary.isNextToPlay( ii, isLocal );
                    if ( thisHasTurn ) {
                        haveATurn = true;
                        if ( isLocal[0] ) {
                            haveALocalTurn = true;
                        }
                    }
                    tmp.setPct( m_handler, thisHasTurn, isLocal[0], 
                                summary.lastMoveTime );
                    list.addView( tmp, ii );
                }

                view = (TextView)layout.findViewById( R.id.state );
                view.setText( state );
                view = (TextView)layout.findViewById( R.id.modtime );
                long lastMoveTime = summary.lastMoveTime;
                lastMoveTime *= 1000;
                view.setText( m_df.format( new Date( lastMoveTime ) ) );

                int iconID;
                ImageView marker =
                    (ImageView)layout.findViewById( R.id.msg_marker );
                CommsConnType conType = summary.conType;
                if ( CommsConnType.COMMS_CONN_RELAY == conType ) {
                    iconID = R.drawable.relaygame;
                } else if ( CommsConnType.COMMS_CONN_BT == conType ) {
                    iconID = android.R.drawable.stat_sys_data_bluetooth;
                } else if ( CommsConnType.COMMS_CONN_SMS == conType ) {
                    iconID = android.R.drawable.sym_action_chat;
                } else {
                    iconID = R.drawable.sologame;
                }
                marker.setImageResource( iconID );

                view = (TextView)layout.findViewById( R.id.role );
                String roleSummary = summary.summarizeRole();
                if ( null != roleSummary ) {
                    view.setText( roleSummary );
                } else {
                    view.setVisibility( View.GONE );
                }

                boolean expanded = DBUtils.getExpanded( m_context, m_rowid );
                ViewInfo vi = new ViewInfo( layout, m_rowid, expanded, 
                                            summary.lastMoveTime, haveATurn, 
                                            haveALocalTurn );
                if ( XWApp.DEBUG ) {
                    DbgUtils.logf( "created new view for rowid %d", m_rowid );
                }
                synchronized( m_viewsCache ) {
                    m_viewsCache.put( m_rowid, vi );
                }
            }
            return null;
        } // doInBackground

        @Override
        protected void onPostExecute( Void unused )
        {
            // DbgUtils.logf( "onPostExecute(rowid=%d)", m_rowid );
            if ( -1 != m_rowid ) {
                m_cb.itemLoaded( m_rowid );
            }
        }
    } // class LoadItemTask

    public GameListAdapter( Context context, Handler handler, LoadItemCB cb ) {
        super( DBUtils.gamesList(context).length );
        m_context = context;
        m_handler = handler;
        m_cb = cb;
        m_factory = LayoutInflater.from( context );
        m_df = DateFormat.getDateTimeInstance( DateFormat.SHORT, 
                                               DateFormat.SHORT );

        m_viewsCache = new HashMap<Long,ViewInfo>();
    }
    
    public int getCount() {
        return DBUtils.gamesList(m_context).length;
    }
    
    public Object getItem( int position ) 
    {
        final long rowid = DBUtils.gamesList(m_context)[position];
        View layout;
        boolean haveLayout = false;
        synchronized( m_viewsCache ) {
            ViewInfo vi = m_viewsCache.get( rowid );
            haveLayout = null != vi;
            if ( haveLayout ) {
                layout = vi.m_view;
            } else {
                layout = m_factory.inflate( R.layout.game_list_tmp, null );
                vi = new ViewInfo( layout, rowid );
                m_viewsCache.put( rowid, vi );
            }
        }

        if ( !haveLayout ) {
            new LoadItemTask( m_context, rowid/*, ++m_taskCounter*/ ).execute();
        }

        // this doesn't work.  Rather, it breaks highlighting because
        // the background, if we don't set it, is a more complicated
        // object like @android:drawable/list_selector_background.  I
        // tried calling getBackground(), expecting to get a Drawable
        // I could then clone and modify, but null comes back.  So
        // layout must be inheriting its background from elsewhere or
        // it gets set later, during layout.
        // if ( (position%2) == 0 ) {
        //     layout.setBackgroundColor( 0xFF3F3F3F );
        // }

        return layout;
    } // getItem

    public View getView( int position, View convertView, ViewGroup parent ) {
        return (View)getItem( position );
    }

    public void inval( long rowid )
    {
        synchronized( m_viewsCache ) {
            m_viewsCache.remove( rowid );
        }
    }

    public boolean setField( String fieldName )
    {
        boolean changed = false;
        int[] ids = {
            R.string.game_summary_field_empty
            ,R.string.game_summary_field_language
            ,R.string.game_summary_field_opponents
            ,R.string.game_summary_field_state
        };
        int result = -1;
        for ( int id : ids ) {
            if ( m_context.getString( id ).equals( fieldName ) ) {
                result = id;
                break;
            }
        }
        if ( -1 == result ) {
            if ( XWApp.DEBUG ) {
                DbgUtils.logf( "GameListAdapter.setField(): unable to match"
                               + " fieldName %s", fieldName );
            }
        } else if ( m_fieldID != result ) {
            if ( XWApp.DEBUG ) {
                DbgUtils.logf( "setField: clearing views cache for change"
                               + " from %d to %d", m_fieldID, result );
            }
            m_viewsCache.clear();
            m_fieldID = result;
            changed = true;
        }
        return changed;
    }

}