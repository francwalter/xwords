/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009 - 2012 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
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
import android.app.ExpandableListActivity;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import junit.framework.Assert;

import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.DictUtils.DictAndLoc;
import org.eehouse.android.xw4.jni.XwJNI;
import org.eehouse.android.xw4.jni.JNIUtilsImpl;
import org.eehouse.android.xw4.jni.GameSummary;
import org.eehouse.android.xw4.DictUtils.DictLoc;
import org.eehouse.android.xw4.loc.LocUtils;
import org.eehouse.android.xw4.loc.LocUtils.AlertBuilder;

public class DictsDelegate extends DelegateBase 
    implements View.OnClickListener, AdapterView.OnItemLongClickListener,
               SelectableItem, MountEventReceiver.SDCardNotifiee, 
               DlgDelegate.DlgClickNotify,
               DictImportActivity.DownloadFinishedListener {

    protected static final String DICT_DOLAUNCH = "do_launch";
    protected static final String DICT_LANG_EXTRA = "use_lang";
    protected static final String DICT_NAME_EXTRA = "use_dict";

    private ExpandableListActivity m_activity;
    private HashSet<String> m_closedLangs;


    private String[] m_langs;
    private String m_downloadStr;
    private ExpandableListView m_expView;
    private String[] m_locNames;
    private DictListAdapter m_adapter;
    private HashSet<XWListItem> m_selDicts;
    private CharSequence m_origTitle;

    private boolean m_launchedForMissing = false;

    private class DictListAdapter implements ExpandableListAdapter {
        private Context m_context;
        private XWListItem[][] m_cache;

        public DictListAdapter( Context context ) {
            m_context = context;
        }

        public boolean areAllItemsEnabled() { return false; }

        public Object getChild( int groupPosition, int childPosition )
        {
            return null;
        }

        public long getChildId( int groupPosition, int childPosition )
        {
            return childPosition;
        }

        public View getChildView( int groupPosition, int childPosition, 
                                  boolean isLastChild, View convertView, 
                                  ViewGroup parent)
        {
            return getChildView( groupPosition, childPosition );
        }

        private View getChildView( int groupPosition, int childPosition )
        {
            XWListItem view = null;
            if ( null != m_cache && null != m_cache[groupPosition] ) {
                view = m_cache[groupPosition][childPosition];
            }

            if ( null == view ) {
                view = XWListItem.inflate( m_activity, DictsDelegate.this );

                int lang = (int)getGroupId( groupPosition );
                DictAndLoc[] dals = DictLangCache.getDALsHaveLang( m_context, 
                                                                   lang );

                if ( null != dals && childPosition < dals.length ) {
                    DictAndLoc dal;
                    dal = dals[childPosition];
                    view.setText( dal.name );

                    DictLoc loc = dal.loc;
                    view.setComment( m_locNames[loc.ordinal()] );
                    view.cache( loc );
                } else {
                    view.setText( m_downloadStr );
                }

                addToCache( groupPosition, childPosition, view );
                view.setOnClickListener( DictsDelegate.this );
            }
            return view;
        }

        public int getChildrenCount( int groupPosition )
        {
            int lang = (int)getGroupId( groupPosition );
            DictAndLoc[] dals = DictLangCache.getDALsHaveLang( m_context, lang );
            int result = 0; // 1;     // 1 for the download option
            if ( null != dals ) {
                result += dals.length;
            }
            return result;
        }

        public long getCombinedChildId( long groupId, long childId )
        {
            return groupId << 16 | childId;
        }

        public long getCombinedGroupId( long groupId )
        {
            return groupId;
        }

        public Object getGroup( int groupPosition )
        {
            return null;
        }

        public int getGroupCount()
        {
            return m_langs.length;
        }

        public long getGroupId( int groupPosition )
        {
            int lang = DictLangCache.getLangLangCode( m_context, 
                                                      m_langs[groupPosition] );
            return lang;
        }

        public View getGroupView( int groupPosition, boolean isExpanded, 
                                  View convertView, ViewGroup parent )
        {
            View row = LocUtils.inflate( m_activity, android.R.layout
                                         .simple_expandable_list_item_1 );
            TextView view = (TextView)row.findViewById( android.R.id.text1 );
            view.setText( m_langs[groupPosition] );
            return view;
        }

        public boolean hasStableIds() { return false; }
        public boolean isChildSelectable( int groupPosition, 
                                          int childPosition ) { return true; }
        public boolean isEmpty() { return false; }
        public void onGroupCollapsed(int groupPosition)
        {
            m_closedLangs.add( m_langs[groupPosition] );
            saveClosed();
        }
        public void onGroupExpanded(int groupPosition){
            m_closedLangs.remove( m_langs[groupPosition] );
            saveClosed();
        }
        public void registerDataSetObserver( DataSetObserver obs ){}
        public void unregisterDataSetObserver( DataSetObserver obs ){}

        protected XWListItem getSelChildView()
        {
            Assert.assertTrue( 1 == m_selDicts.size() );
            return m_selDicts.iterator().next();
        }

        private void addToCache( int group, int child, XWListItem view )
        {
            if ( null == m_cache ) {
                m_cache = new XWListItem[getGroupCount()][];
            }
            if ( null == m_cache[group] ) {
                m_cache[group] = new XWListItem[getChildrenCount(group)];
            }
            Assert.assertTrue( null == m_cache[group][child] );
            m_cache[group][child] = view;
        }
    }

    protected DictsDelegate( ExpandableListActivity activity, Bundle savedInstanceState )
    {
        super( activity, savedInstanceState, R.menu.dicts_menu );
        m_activity = activity;
        init( savedInstanceState );
    }

    protected Dialog createDialog( int id )
    {
        OnClickListener lstnr, lstnr2;
        Dialog dialog;
        String message;
        boolean doRemove = true;

        DlgID dlgID = DlgID.values()[id];
        switch( dlgID ) {
        case MOVE_DICT:
            final XWListItem[] selItems = getSelItems();
            final int[] moveTo = { -1 };
            message = m_activity.getString( R.string.move_dict_fmt,
                                            getJoinedNames( selItems ) );

            OnClickListener newSelLstnr =
                new OnClickListener() {
                    public void onClick( DialogInterface dlgi, int item ) {
                        moveTo[0] = item;
                        AlertDialog dlg = (AlertDialog)dlgi;
                        Button btn = 
                            dlg.getButton( AlertDialog.BUTTON_POSITIVE ); 
                        btn.setEnabled( true );
                    }
                };

            lstnr = new OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        DictLoc toLoc = itemToRealLoc( moveTo[0] );
                        for ( XWListItem selItem : selItems ) {
                            DictLoc fromLoc = (DictLoc)selItem.getCached();
                            String name = selItem.getText();
                            if ( fromLoc == toLoc ) {
                                DbgUtils.logf( "not moving %s: same loc", name );
                            } else if ( DictUtils.moveDict( m_activity,
                                                            name, fromLoc, 
                                                            toLoc ) ) {
                                selItem.setComment( m_locNames[toLoc.ordinal()] );
                                selItem.cache( toLoc );
                                selItem.invalidate();
                                DBUtils.dictsMoveInfo( m_activity, name, 
                                                       fromLoc, toLoc );
                            } else {
                                DbgUtils.logf( "moveDict(%s) failed", name );
                            }
                        }
                    }
                };

            dialog = new AlertDialog.Builder( m_activity )
                .setTitle( message )
                .setSingleChoiceItems( makeDictDirItems(), moveTo[0],
                                       newSelLstnr )
                .setPositiveButton( R.string.button_move, lstnr )
                .setNegativeButton( R.string.button_cancel, null )
                .create();
            break;

        case SET_DEFAULT:
            final XWListItem row = m_selDicts.iterator().next();
            lstnr = new OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        if ( DialogInterface.BUTTON_NEGATIVE == item
                             || DialogInterface.BUTTON_POSITIVE == item ) {
                            setDefault( row, R.string.key_default_dict );
                        }
                        if ( DialogInterface.BUTTON_NEGATIVE == item 
                             || DialogInterface.BUTTON_NEUTRAL == item ) {
                            setDefault( row, R.string.key_default_robodict );
                        }
                    }
                };
            String name = row.getText();
            String lang = DictLangCache.getLangName( m_activity, name);
            message = m_activity.getString( R.string.set_default_message_fmt,
                                            name, lang );
            dialog = new LocUtils.AlertBuilder( m_activity )
                .setTitle( R.string.query_title )
                .setMessage( message )
                .setPositiveButton( R.string.button_default_human, lstnr )
                .setNeutralButton( R.string.button_default_robot, lstnr )
                .setNegativeButton( R.string.button_default_both, lstnr )
                .create();
            break;

        case DICT_OR_DECLINE:
            lstnr = new OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        Intent intent = m_activity.getIntent();
                        int lang = intent.getIntExtra( MultiService.LANG, -1 );
                        String name = intent.getStringExtra( MultiService.DICT );
                        m_launchedForMissing = true;
                        DictImportActivity
                            .downloadDictInBack( m_activity, lang, 
                                                 name, DictsDelegate.this );
                    }
                };
            lstnr2 = new OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        m_activity.finish();
                    }
                };

            dialog = MultiService.missingDictDialog( m_activity, 
                                                     m_activity.getIntent(), 
                                                     lstnr, lstnr2 );
            break;

        default:
            dialog = super.createDialog( id );
            doRemove = false;
            break;
        }

        if ( doRemove && null != dialog ) {
            Utils.setRemoveOnDismiss( m_activity, dialog, dlgID );
        }

        return dialog;
    } // createDialog

    protected void prepareDialog( int id, Dialog dialog )
    {
        if ( DlgID.MOVE_DICT.ordinal() == id ) {
            // The move button should always start out disabled
            // because the selected location should be where it
            // currently is.
            ((AlertDialog)dialog).getButton( AlertDialog.BUTTON_POSITIVE )
                .setEnabled( false );
        }
    }

    private void init( Bundle savedInstanceState ) 
    {
        m_closedLangs = new HashSet<String>();
        String[] closed = XWPrefs.getClosedLangs( m_activity );
        if ( null != closed ) {
            for ( String str : closed ) {
                m_closedLangs.add( str );
            }
        }

        Resources res = m_activity.getResources();
        m_locNames = res.getStringArray( R.array.loc_names );

        m_downloadStr = m_activity.getString( R.string.download_dicts );
            
        m_activity.setContentView( R.layout.dict_browse );
        m_expView = m_activity.getExpandableListView();
        m_expView.setOnItemLongClickListener( this );
        
        Button download = (Button)m_activity.findViewById( R.id.download );
        if ( ABUtils.haveActionBar() ) {
            download.setVisibility( View.GONE );
        } else {
            download.setOnClickListener( this );
        }

        mkListAdapter();

        Intent intent = m_activity.getIntent();
        if ( null != intent ) {
            if ( MultiService.isMissingDictIntent( intent ) ) {
                showDialog( DlgID.DICT_OR_DECLINE );
            } else {
                boolean downloadNow = intent.getBooleanExtra( DICT_DOLAUNCH, false );
                if ( downloadNow ) {
                    int lang = intent.getIntExtra( DICT_LANG_EXTRA, 0 );
                    String name = intent.getStringExtra( DICT_NAME_EXTRA );
                    startDownload( lang, name );
                }

                downloadNewDict( intent );
            }
        }

        m_origTitle = m_activity.getTitle();
    } // onCreate

    protected void onResume()
    {
        MountEventReceiver.register( this );

        mkListAdapter();
        expandGroups();
        setTitleBar();
    }

    protected void onStop() 
    {
        MountEventReceiver.unregister( this );
    }

    public void onClick( View view ) 
    {
        if ( view instanceof Button ) {
            startDownload( 0, null );
        } else {
            XWListItem item = (XWListItem)view;
            DictBrowseActivity.launch( m_activity, item.getText(), 
                                       (DictLoc)item.getCached() );
        }
    }

    protected boolean onBackPressed() 
    {
        boolean handled = 0 < m_selDicts.size();
        if ( handled ) {
            clearSelections();
        }
        return handled;
    }

    protected boolean onPrepareOptionsMenu( Menu menu ) 
    {
        int nSel = m_selDicts.size();
        Utils.setItemVisible( menu, R.id.dicts_download, 
                              0 == nSel && ABUtils.haveActionBar() );
        Utils.setItemVisible( menu, R.id.dicts_select, 1 == nSel );

        boolean allVolatile = selItemsVolatile();
        Utils.setItemVisible( menu, R.id.dicts_move, 
                              allVolatile && DictUtils.haveWriteableSD() );
        Utils.setItemVisible( menu, R.id.dicts_delete, allVolatile );

        return true;
    }

    protected boolean onOptionsItemSelected( MenuItem item )
    {
        boolean handled = true;

        switch ( item.getItemId() ) {
        case R.id.dicts_download:
            startDownload( 0, null );
            break;
        case R.id.dicts_delete:
            deleteSelected();
            break;
        case R.id.dicts_move:
            showDialog( DlgID.MOVE_DICT );
            break;
        case R.id.dicts_select:
            showDialog( DlgID.SET_DEFAULT );
            break;
        default:
            handled = false;
        }

        return handled;
    }

    private void downloadNewDict( Intent intent )
    {
        int loci = intent.getIntExtra( UpdateCheckReceiver.NEW_DICT_LOC, 0 );
        if ( 0 < loci ) {
            String url = 
                intent.getStringExtra( UpdateCheckReceiver.NEW_DICT_URL );
            DictImportActivity.downloadDictInBack( m_activity, url );
            m_activity.finish();
        }
    }

    private void setDefault( XWListItem view, int keyId )
    {
        SharedPreferences sp
            = PreferenceManager.getDefaultSharedPreferences( m_activity );
        SharedPreferences.Editor editor = sp.edit();
        String key = m_activity.getString( keyId );
        String name = view.getText();
        editor.putString( key, name );
        editor.commit();
    }

    //////////////////////////////////////////////////////////////////////
    // OnItemLongClickListener interface
    //////////////////////////////////////////////////////////////////////
    public boolean onItemLongClick( AdapterView<?> parent, View view, 
                                    int position, long id ) {
        boolean success = view instanceof SelectableItem.LongClickHandler;
        if ( success ) {
            ((SelectableItem.LongClickHandler)view).longClicked();
        }
        return success;
    }

    private boolean selItemsVolatile() 
    {
        boolean result = 0 < m_selDicts.size();
        for ( Iterator<XWListItem> iter = m_selDicts.iterator(); 
              result && iter.hasNext(); ) {
            DictLoc loc = (DictLoc)iter.next().getCached();
            if ( loc == DictLoc.BUILT_IN ) {
                result = false;
            }
        }
        return result;
    }

    private void deleteSelected()
    {
        XWListItem[] items = getSelItems();
        String msg = m_activity.getString( R.string.confirm_delete_dict_fmt, 
                                           getJoinedNames( items ) );

        // When and what to warn about.  First off, if there's another
        // identical dict, simply confirm.  Or if nobody's using this
        // dict *and* it's not the last of a language that somebody's
        // using, simply confirm.  If somebody is using it, then we
        // want different warnings depending on whether it's the last
        // available dict in its language.

        for ( XWListItem item : items ) {
            String dict = item.getText();
            if ( 1 < DictLangCache.getDictCount( m_activity, dict ) ) {
                // there's another; do nothing
            } else {
                String newMsg = null;
                int langcode = DictLangCache.getDictLangCode( m_activity, dict );
                String langName = DictLangCache.getLangName( m_activity, langcode );
                DictAndLoc[] langDals = DictLangCache.getDALsHaveLang( m_activity, 
                                                                       langcode );
                int nUsingLang = DBUtils.countGamesUsingLang( m_activity, langcode );

                if ( 1 == langDals.length ) { // last in this language?
                    if ( 0 < nUsingLang ) {
                        newMsg = 
                            m_activity.getString( R.string.confirm_deleteonly_dict_fmt,
                                                  dict, langName );
                    }
                } else if ( 0 < DBUtils.countGamesUsingDict( m_activity, dict ) ) {
                    newMsg = m_activity.getString( R.string.confirm_deletemore_dict_fmt,
                                                   langName );
                }
                if ( null != newMsg ) {
                    msg += "\n\n" + newMsg;
                }
            }
        }

        showConfirmThen( msg, R.string.button_delete, Action.DELETE_DICT_ACTION,
                         (Object)items );
    } // deleteSelected

    //////////////////////////////////////////////////////////////////////
    // MountEventReceiver.SDCardNotifiee interface
    //////////////////////////////////////////////////////////////////////
    public void cardMounted( boolean nowMounted )
    {
        DbgUtils.logf( "DictsActivity.cardMounted(%b)", nowMounted );
        // post so other SDCardNotifiee implementations get a chance
        // to process first: avoid race conditions
        post( new Runnable() {
                public void run() {
                    mkListAdapter();
                    expandGroups();
                }
            } );
    }

    //////////////////////////////////////////////////////////////////////
    // DlgDelegate.DlgClickNotify interface
    //////////////////////////////////////////////////////////////////////
    public void dlgButtonClicked( Action action, int which, Object[] params )
    {
        switch( action ) {
        case DELETE_DICT_ACTION:
            if ( DialogInterface.BUTTON_POSITIVE == which ) {
                XWListItem[] items = (XWListItem[])params[0];
                for ( XWListItem item : items ) {
                    String name = item.getText();
                    DictLoc loc = (DictLoc)item.getCached();
                    deleteDict( name, loc );
                }
                clearSelections();
            }
            break;
        case DOWNLOAD_DICT_ACTION:
            startDownload( (Intent)params[0] );
            break;
        default:
            Assert.fail();
        }
    }

    private DictLoc itemToRealLoc( int item )
    {
        item += DictLoc.INTERNAL.ordinal();
        return DictLoc.values()[item];
    }

    private void deleteDict( String dict, DictLoc loc )
    {
        DictUtils.deleteDict( m_activity, dict, loc );
        DictLangCache.inval( m_activity, dict, loc, false );
        mkListAdapter();
        expandGroups();
    }

    private void startDownload( int lang, String name )
    {
        Intent intent = mkDownloadIntent( m_activity, lang, name );
        showNotAgainDlgThen( R.string.not_again_firefox, 
                             R.string.key_na_firefox, 
                             Action.DOWNLOAD_DICT_ACTION, intent );
    }

    private void startDownload( Intent downloadIntent )
    {
        try {
            m_activity.startActivity( downloadIntent );
        } catch ( android.content.ActivityNotFoundException anfe ) {
            Utils.showToast( m_activity, R.string.no_download_warning );
        }
    }

    private void mkListAdapter()
    {
        m_langs = DictLangCache.listLangs( m_activity );
        Arrays.sort( m_langs );
        m_adapter = new DictListAdapter( m_activity );
        m_activity.setListAdapter( m_adapter );

        m_selDicts = new HashSet<XWListItem>();
    }

    private void expandGroups()
    {
        for ( int ii = 0; ii < m_langs.length; ++ii ) {
            boolean open = true;
            String lang = m_langs[ii];
            if ( ! m_closedLangs.contains( lang ) ) {
                m_expView.expandGroup( ii );
            }
        }
    }

    private void saveClosed()
    {
        String[] asArray = m_closedLangs.toArray( new String[m_closedLangs.size()] );
        XWPrefs.setClosedLangs( m_activity, asArray );
    }

    private void clearSelections()
    {
        if ( 0 < m_selDicts.size() ) {
            XWListItem[] items = getSelItems();

            m_selDicts.clear();

            for ( XWListItem item : items ) {
                item.setSelected( false );
            }
        }
    }

    private String getJoinedNames( XWListItem[] items )
    {
        String[] names = new String[items.length];
        int ii = 0;
        for ( XWListItem item : items ) {
            names[ii++] = item.getText();
        }
        return TextUtils.join( ", ", names );
    }

    private XWListItem[] getSelItems()
    {
        XWListItem[] items = new XWListItem[m_selDicts.size()];
        int indx = 0;
        for ( Iterator<XWListItem> iter = m_selDicts.iterator(); 
              iter.hasNext(); ) {
            items[indx++] = iter.next();
        }
        return items;
    }

    private void setTitleBar()
    {
        int nSels = m_selDicts.size();
        if ( 0 < nSels ) {
            m_activity.setTitle( LocUtils.getString( m_activity, R.string.sel_items_fmt, 
                                                       nSels ) );
        } else {
            m_activity.setTitle( m_origTitle );
        }
    }

    private String[] makeDictDirItems() 
    {
        boolean showDownload = DictUtils.haveDownloadDir( m_activity );
        int nItems = showDownload ? 3 : 2;
        int nextI = 0;
        String[] items = new String[nItems];
        for ( int ii = 0; ii < 3; ++ii ) {
            DictLoc loc = itemToRealLoc(ii);
            if ( !showDownload && DictLoc.DOWNLOAD == loc ) {
                continue;
            }
            items[nextI++] = m_locNames[loc.ordinal()];
        }
        return items;
    }

    private static Intent mkDownloadIntent( Context context, String dict_url )
    {
        Uri uri = Uri.parse( dict_url );
        Intent intent = new Intent( Intent.ACTION_VIEW, uri );
        intent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
        return intent;
    }

    private static Intent mkDownloadIntent( Context context,
                                            int lang, String dict )
    {
        String dict_url = Utils.makeDictUrl( context, lang, dict );
        return mkDownloadIntent( context, dict_url );
    }

    public static void launchAndDownload( Activity activity, int lang, 
                                          String name )
    {
        Intent intent = new Intent( activity, DictsActivity.class );
        intent.putExtra( DICT_DOLAUNCH, true );
        if ( lang > 0 ) {
            intent.putExtra( DICT_LANG_EXTRA, lang );
        }
        if ( null != name ) {
            Assert.assertTrue( lang != 0 );
            intent.putExtra( DICT_NAME_EXTRA, name );
        }

        activity.startActivity( intent );
    }

    public static void launchAndDownload( Activity activity, int lang )
    {
        launchAndDownload( activity, lang, null );
    }

    public static void launchAndDownload( Activity activity )
    {
        launchAndDownload( activity, 0, null );
    }

    // DictImportActivity.DownloadFinishedListener interface
    public void downloadFinished( String name, final boolean success )
    {
        if ( m_launchedForMissing ) {
            post( new Runnable() {
                    public void run() {
                        if ( success ) {
                            Intent intent = m_activity.getIntent();
                            if ( MultiService.returnOnDownload( m_activity,
                                                                intent ) ) {
                                m_activity.finish();
                            }
                        } else {
                            Utils.showToast( m_activity, 
                                             R.string.download_failed );
                        }
                    }
                } );
        }
    }

    // SelectableItem interface
    public void itemClicked( SelectableItem.LongClickHandler clicked,
                             GameSummary summary )
    {
        DbgUtils.logf( "itemClicked not implemented" );
    }

    public void itemToggled( SelectableItem.LongClickHandler toggled, 
                             boolean selected )
    {
        XWListItem dictView = (XWListItem)toggled;
        if ( selected ) {
            m_selDicts.add( dictView );
        } else {
            m_selDicts.remove( dictView );
        }
        ABUtils.invalidateOptionsMenuIf( m_activity );
        setTitleBar();
    }

    public boolean getSelected( SelectableItem.LongClickHandler obj )
    {
        XWListItem dictView = (XWListItem)obj;
        return m_selDicts.contains( dictView );
    }

}