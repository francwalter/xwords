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
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;       // class is not synchronized
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;
import org.eehouse.android.xw4.DBUtils.GameGroupInfo;

public class GameListAdapter implements ExpandableListAdapter {
    private Context m_context;
    private ExpandableListView m_list;
    private LayoutInflater m_factory;
    private int m_fieldID;
    private Handler m_handler;
    private LoadItemCB m_cb;

    public interface LoadItemCB {
        public void itemClicked( long rowid, GameSummary summary );
    }

    public GameListAdapter( Context context, ExpandableListView list, 
                            Handler handler, LoadItemCB cb, String fieldName ) 
    {
        // super( DBUtils.gamesList(context).length );
        m_context = context;
        m_list = list;
        m_handler = handler;
        m_cb = cb;
        m_factory = LayoutInflater.from( context );

        m_fieldID = fieldToID( fieldName );
    }

    // public void inval( long rowid )
    // {
    //     synchronized( m_viewsCache ) {
    //         m_viewsCache.remove( rowid );
    //     }
    // }

    public void expandGroups( ExpandableListView view )
    {
        HashMap<String,GameGroupInfo> info = gameInfo();
        String[] names = groupNames();
        for ( int ii = 0; ii < names.length; ++ii ) {
            GameGroupInfo ggi = info.get( names[ii] );
            if ( ggi.m_expanded ) {
                view.expandGroup( ii );
            }
        }
    }

    // public void setField( String field )
    // {
    //     int[] ids = {
    //         R.string.game_summary_field_empty
    //         ,R.string.game_summary_field_language
    //         ,R.string.game_summary_field_opponents
    //         ,R.string.game_summary_field_state
    //     };
    //     int result = -1;
    //     for ( int id : ids ) {
    //         if ( m_context.getString( id ).equals( field ) ) {
    //             result = id;
    //             break;
    //         }
    //     }
    //     if ( m_fieldID != result ) {
    //         m_viewsCache.clear();
    //         m_fieldID = result;
    //     }
    // }

    public long getRowIDFor( int group, int child )
    {
        long rowid = DBUtils.ROWID_NOTFOUND;
        String[] groupNames = groupNames();
        if ( group < groupNames.length ) {
            String name = groupNames()[group];
            long[] rows = getRows( name );
            if ( child < rows.length ) {
                rowid = rows[child];
            }
        }
        return rowid;
    }

    public long getRowIDFor( long packedPosition )
    {
        int childPosition = ExpandableListView.
            getPackedPositionChild( packedPosition );
        int groupPosition = ExpandableListView.
            getPackedPositionGroup( packedPosition );
        return getRowIDFor( groupPosition, childPosition );
    }

    public long getGroupIDFor( int groupPos )
    {
        String name = groupNames()[groupPos];
        GameGroupInfo ggi = gameInfo().get( name );
        return ggi.m_id;
    }

    public String groupName( long groupid )
    {
        String result = null;
        String[] names = groupNames();
        int index;
        for ( index = 0; index < names.length; ++index ) {
            GameGroupInfo ggi = gameInfo().get( names[index] );
            if ( groupid == ggi.m_id ) {
                result = names[index];
                break;
            }
        }
        return result;
    }

    //////////////////////////////////////////////////////////////////////////
    // ExpandableListAdapter interface
    //////////////////////////////////////////////////////////////////////////
    public long getCombinedGroupId( long groupId )
    {
        return groupId;
    }

    public long getCombinedChildId( long groupId, long childId )
    {
        return groupId << 16 | childId;
    }

    public boolean isEmpty() { return false; }

    public void onGroupCollapsed( int groupPosition )
    {
        long groupid = getGroupIDFor( groupPosition );
        DBUtils.setGroupExpanded( m_context, groupid, false );
        // m_closedLangs.add( m_langs[groupPosition] );
        // saveClosed();
    }
    public void onGroupExpanded( int groupPosition )
    {
        long groupid = getGroupIDFor( groupPosition );
        DBUtils.setGroupExpanded( m_context, groupid, true );
        // m_closedLangs.add( m_langs[groupPosition] );
        // saveClosed();
    }

    public boolean areAllItemsEnabled() { return false; }

    public boolean isChildSelectable( int groupPosition, int childPosition ) 
    { return true; }

    public View getChildView( int groupPosition, int childPosition, 
                              boolean isLastChild, View convertView, 
                              ViewGroup parent)
    {
        return getChildView( groupPosition, childPosition );
    }

    private View getChildView( int groupPosition, int childPosition )
    {
        long rowid = getRowIDFor( groupPosition, childPosition );
        return getItem( rowid );
    }

    public View getGroupView( int groupPosition, boolean isExpanded, 
                              View convertView, ViewGroup parent )
    {
        View row = 
            Utils.inflate(m_context,
                          android.R.layout.simple_expandable_list_item_1 );
        TextView view = (TextView)row.findViewById( android.R.id.text1 );
        String name = groupNames()[groupPosition];
        if ( name.equals("") ) {
            name = "<Unnamed>";
        }
        if ( !isExpanded ) {
            int nKids = getChildrenCount( groupPosition );
            name += String.format( " (%d)", nKids );
        }
        view.setText( name );
        return view;
    }

    public boolean hasStableIds() { return false; }
    
    public long getChildId( int groupPosition, int childPosition )
    {
        return childPosition;
    }

    public long getGroupId( int groupPosition )
    {
        return groupPosition;
    }

    public Object getChild( int groupPosition, int childPosition )
    {
        String name = groupNames()[groupPosition];
        long[] rows = getRows( name );
        return rows[childPosition];
    }
    
    public Object getGroup( int groupPosition )
    {
        return null;
    }

    public int getChildrenCount( int groupPosition )
    {
        String name = groupNames()[ groupPosition ];
        long[] rows = getRows( name );
        return rows.length;
    }

    public int getGroupCount()
    {
        return groupNames().length;
    }

    public void registerDataSetObserver( DataSetObserver obs ){}
    public void unregisterDataSetObserver( DataSetObserver obs ){}

    // private View getItem( final long rowid ) 
    // {
    //     View layout;
    //     boolean haveLayout = false;
    //     synchronized( m_viewsCache ) {
    //         ViewInfo vi = m_viewsCache.get( rowid );
    //         haveLayout = null != vi;
    //         if ( haveLayout ) {
    //             layout = vi.m_view;
    //         } else {
    //             layout = m_factory.inflate( R.layout.game_list_tmp, null );
    //             vi = new ViewInfo( layout, rowid );
    //             m_viewsCache.put( rowid, vi );
    //         }
    //     }
    // }
    // @Override
    // public int getCount() {
    //     return DBUtils.gamesList(m_context).length;
    // }

    // // Views.  A view depends on a summary, which takes time to load.
    // // When one needs loading it's done via an async task.
    // public View getView( int position, View convertView, ViewGroup parent ) 
    // {
    //     GameListItem result = (GameListItem)
    //         m_factory.inflate( R.layout.game_list_item, null );
    //     result.init( m_handler, DBUtils.gamesList(m_context)[position],
    //                  m_fieldID, m_cb );
    //     return result;
    // }

    public void inval( long rowid )
    {
        GameListItem child = getItemFor( rowid );
        if ( null != child && child.getRowID() == rowid ) {
            child.forceReload();
        } else {
            DbgUtils.logf( "no child for rowid %d", rowid );
            GameListItem.inval( rowid );
            m_list.invalidate();
        }
    }

    public void invalName( long rowid )
    {
        GameListItem item = getItemFor( rowid );
        if ( null != item ) {
            item.invalName();
        }
    }

    private long[] getRows( String group )
    {
        GameGroupInfo ggi = gameInfo().get(group);
        long groupID = ggi.m_id;
        long[] rows = DBUtils.getGroupGames( m_context, groupID );
        return rows;
    }

    public String[] groupNames()
    {
        HashMap<String,GameGroupInfo> info = gameInfo();
        Set<String> set = info.keySet();
        String[] names = new String[ set.size() ];
        set.toArray(names);
        return names;
    }
    
    public int getGroupPosition( long groupid )
    {
        int pos;
        String[] names = groupNames();
        HashMap<String, GameGroupInfo> info = gameInfo();
        for ( pos = 0; pos < names.length; ++pos ) {
            GameGroupInfo ggi = info.get( names[pos] );
            if ( ggi.m_id == groupid ) {
                break;
            }
        }
        return pos;
    }

    public boolean setField( String fieldName )
    {
        boolean changed = false;
        int newID = fieldToID( fieldName );
        if ( -1 == newID ) {
            if ( XWApp.DEBUG ) {
                DbgUtils.logf( "GameListAdapter.setField(): unable to match"
                               + " fieldName %s", fieldName );
            }
        } else if ( m_fieldID != newID ) {
            if ( XWApp.DEBUG ) {
                DbgUtils.logf( "setField: clearing views cache for change"
                               + " from %d to %d", m_fieldID, newID );
            }
            m_fieldID = newID;
            // return true so caller will do onContentChanged.
            // There's no other way to signal GameListItem instances
            // since we don't maintain a list of them.
            changed = true;
        }
        return changed;
    }

    private GameListItem getItemFor( long rowid )
    {
        GameListItem result = null;
        int position = positionFor( rowid );
        if ( 0 <= position ) {
            result = (GameListItem)m_list.getChildAt( position );
        }
        return result;
    }

    private int fieldToID( String fieldName )
    {
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
        return result;
    }

    private int positionFor( long rowid )
    {
        int position = -1;
        long[] rowids = DBUtils.gamesList( m_context );
        for ( int ii = 0; ii < rowids.length; ++ii ) {
            if ( rowids[ii] == rowid ) {
                position = ii;
                break;
            }
        }
        return position;
    }
}
