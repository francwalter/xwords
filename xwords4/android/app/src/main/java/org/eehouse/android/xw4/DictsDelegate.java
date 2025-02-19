/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
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
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;


import org.eehouse.android.xw4.DictUtils.DictAndLoc;
import org.eehouse.android.xw4.DictUtils.DictLoc;
import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.DwnldDelegate.DownloadFinishedListener;
import org.eehouse.android.xw4.DwnldDelegate.OnGotLcDictListener;
import org.eehouse.android.xw4.Perms23.Perm;
import org.eehouse.android.xw4.jni.GameSummary;
import org.eehouse.android.xw4.loc.LocUtils;
import org.eehouse.android.xw4.Utils.ISOCode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

public class DictsDelegate extends ListDelegateBase
    implements View.OnClickListener, AdapterView.OnItemLongClickListener,
               SelectableItem, MountEventReceiver.SDCardNotifiee,
               DlgDelegate.DlgClickNotify, GroupStateListener,
               DownloadFinishedListener, XWListItem.ExpandedListener {
    private static final String TAG = DictsDelegate.class.getSimpleName();

    private static final String REMOTE_SHOW_KEY = "REMOTE_SHOW_KEY";
    private static final String REMOTE_INFO_KEY = "REMOTE_INFO_KEY";
    private static final String SEL_DICTS_KEY = "SEL_DICTS_KEY";

    private static final String DICT_SHOWREMOTE = "do_launch";
    private static final String DICT_LANG_EXTRA = "use_lang";
    private static final String DICT_NAME_EXTRA = "use_dict";
    protected static final String RESULT_LAST_LANG = "last_lang";
    protected static final String RESULT_LAST_DICT = "last_dict";

    private static final int SEL_LOCAL = 0;
    private static final int SEL_REMOTE = 1;

    private Activity m_activity;
    private Set<String> m_closedLangs;
    private Set<DictInfo> m_expandedItems;
    private DictListAdapter m_adapter;

    private boolean m_quickFetchMode;
    private String[] m_langs;
    private CheckBox m_checkbox;
    private String[] m_locNames;
    private String m_finishOnName;
    private Map<String, XWListItem> m_selViews;
    private Map<String, Object> m_selDicts = new HashMap<>();
    private String m_origTitle;
    private boolean m_showRemote = false;
    private String m_filterLang;
    private Map<String, Uri> m_needUpdates;
    private String m_onServerStr;
    private ISOCode m_lastLang;
    private String m_lastDict;

    private static class DictInfo implements Comparable, Serializable {
        public String m_name;
        public String m_localLang;  // what we display to user, i.e. translated
        public ISOCode mISOCode;    // what needs to be in URL
        public int m_nWords;
        public long m_nBytes;
        public String m_note;
        public DictInfo( String name, ISOCode isoCode, String localLang,
                         int nWords, long nBytes, String note )
        {
            m_name = name;
            m_localLang = localLang;
            mISOCode = isoCode;
            m_nWords = nWords;
            m_nBytes = nBytes;
            m_note = note;
        }
        @Override
        public int compareTo( Object obj ) {
            DictInfo other = (DictInfo)obj;
            return m_name.compareTo( other.m_name );
        }
    }
    private static class LangInfo {
        int m_numDictsInst;
        int m_numDictsAvail;
        int m_posn;
        public LangInfo( int posn, Collection<Object> objs )
        {
            m_posn = posn;
            for ( Object obj : objs ) {
                if ( obj instanceof DictInfo ) {
                    ++m_numDictsAvail;
                } else if ( obj instanceof DictAndLoc ) {
                    ++m_numDictsInst;
                } else {
                    Assert.failDbg();
                }
            }
        }
    }
    private HashMap<String, DictInfo[]> m_remoteInfo;

    private boolean m_launchedForMissing = false;

    private class DictListAdapter extends XWExpListAdapter {
        private Context m_context;

        public DictListAdapter( Context context )
        {
            super( new Class[] { LangInfo.class,
                                 DictAndLoc.class,
                                 DictInfo.class
                } );
            m_context = context;
        }

        @Override
        public Object[] makeListData()
        {
            ArrayList<Object> alist = new ArrayList<>();
            int nLangs = m_langs.length;
            for ( int ii = 0; ii < nLangs; ++ii ) {
                String langName = m_langs[ii];
                if ( null != m_filterLang &&
                     ! m_filterLang.equals(langName) ) {
                    continue;
                }

                ArrayList<Object> items = makeLangItems( langName );
                Assert.assertTrueNR( 0 < items.size() );

                alist.add( new LangInfo( ii, items ) );
                if ( ! m_closedLangs.contains( langName ) ) {
                    alist.addAll( items );
                }
            }
            return alist.toArray( new Object[alist.size()] );
        } // makeListData

        @Override
        public View getView( Object dataObj, View convertView )
        {
            View result = null;

            if ( dataObj instanceof LangInfo ) {
                LangInfo info = (LangInfo)dataObj;
                int groupPos = info.m_posn;
                String langName = m_langs[groupPos];
                boolean expanded = ! m_closedLangs.contains( langName );
                String details = null;
                if ( 0 < info.m_numDictsInst && 0 < info.m_numDictsAvail ) {
                    details = getString( R.string.dict_lang_inst_and_avail,
                                         info.m_numDictsInst, info.m_numDictsAvail );
                } else if ( 0 < info.m_numDictsAvail ) {
                    details = getString( R.string.dict_lang_avail,
                                         info.m_numDictsAvail );
                } else if ( 0 < info.m_numDictsInst ) {
                    details = getString( R.string.dict_lang_inst, info.m_numDictsInst );
                } else {
                    Assert.failDbg();
                }
                String title = Utils.capitalize( langName ) + " " + details;
                result = ListGroup.make( m_context, convertView,
                                         DictsDelegate.this, groupPos, title,
                                         expanded );
            } else {
                XWListItem item;
                if ( null != convertView && convertView instanceof XWListItem ) {
                    item = (XWListItem)convertView;
                } else {
                    item = XWListItem.inflate( m_activity, DictsDelegate.this );
                }
                result = item;

                String name = null;
                if ( dataObj instanceof DictAndLoc ) {
                    DictAndLoc dal = (DictAndLoc)dataObj;

                    name = dal.name;

                    DictLoc loc = dal.loc;
                    item.setComment( m_locNames[loc.ordinal()] );
                    item.setCached( loc );

                    item.setOnClickListener( DictsDelegate.this );
                    item.setExpandedListener( null ); // item might be reused

                } else if ( dataObj instanceof DictInfo ) {
                    DictInfo info = (DictInfo)dataObj;
                    name = info.m_name;

                    item.setCached( info );

                    item.setExpandedListener( DictsDelegate.this );
                    item.setExpanded( m_expandedItems.contains( info ) );
                    item.setComment( m_onServerStr );

                } else {
                    Assert.failDbg();
                }

                item.setText( name );

                boolean selected = m_selDicts.containsKey( name );
                if ( selected ) {
                    m_selViews.put( name, item );
                }
                item.setSelected( selected );
            }
            return result;
        }

        private XWExpListAdapter.GroupTest makeTestFor( final String langName )
        {
            return new XWExpListAdapter.GroupTest() {
                public boolean isTheGroup( Object item ) {
                    LangInfo info = (LangInfo)item;
                    return m_langs[info.m_posn].equals( langName );
                }
            };
        }

        protected void removeLangItems( String langName )
        {
            int indx = findGroupItem( makeTestFor( langName ) );
            removeChildrenOf( indx );
        }

        protected void addLangItems( String langName )
        {
            int indx = findGroupItem( makeTestFor( langName ) );
            addChildrenOf( indx, makeLangItems( langName ) );
        }

        private ArrayList<Object> makeLangItems( String langName )
        {
            ArrayList<Object> result = new ArrayList<>();

            HashSet<String> locals = new HashSet<>();
            ISOCode isoCode = DictLangCache.getLangIsoCode( m_context, langName );
            DictAndLoc[] dals = DictLangCache.getDALsHaveLang( m_context, isoCode );
            if ( null != dals ) {
                for ( DictAndLoc dal : dals ) {
                    locals.add( dal.name );
                }
            }

            if ( m_showRemote && null != m_remoteInfo ) {
                DictInfo[] infos = m_remoteInfo.get( langName );
                if ( null != infos ) {
                    for ( DictInfo info : infos ) {
                        if ( ! locals.contains( info.m_name ) ) {
                            result.add( info );
                        }
                    }
                } else {
                    Log.w( TAG, "No remote info for lang %s", langName );
                }
            }

            // Now append locals
            if ( null != dals ) {
                result.addAll( Arrays.asList( dals ) );
            }

            return result;
        }
    }

    protected DictsDelegate( Delegator delegator, Bundle savedInstanceState )
    {
        super( delegator, savedInstanceState, R.layout.dicts_browse,
               R.menu.dicts_menu );
        m_activity = delegator.getActivity();
    }

    @Override
    protected Dialog makeDialog( DBAlert alert, Object... params )
    {
        OnClickListener lstnr, lstnr2;
        Dialog dialog;
        String message;
        boolean doRemove = true;

        switch( alert.getDlgID() ) {
        case MOVE_DICT: {
            final String[] selNames = getSelNames();
            final int[] moveTo = { -1 };
            message = getString( R.string.move_dict_fmt,
                                 getJoinedSelNames(selNames) );

            OnClickListener newSelLstnr =
                new OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dlgi, int item ) {
                        moveTo[0] = item;
                        AlertDialog dlg = (AlertDialog)dlgi;
                        Button btn =
                            dlg.getButton( AlertDialog.BUTTON_POSITIVE );
                        btn.setEnabled( true );

                        // Ask for STORAGE (but do nothing if not granted)
                        if ( DictLoc.DOWNLOAD == itemToRealLoc( item ) ) {
                            new Perms23.Builder( Perm.STORAGE )
                                .asyncQuery( m_activity );
                        }
                    }
                };

            lstnr = new OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dlg, int item ) {
                        DictLoc toLoc = itemToRealLoc( moveTo[0] );
                        moveDicts( selNames, toLoc );
                    }
                };

            dialog = new AlertDialog.Builder( m_activity )
                .setTitle( message )
                .setSingleChoiceItems( makeDictDirItems(), moveTo[0],
                                       newSelLstnr )
                .setPositiveButton( R.string.button_move, lstnr )
                .setNegativeButton( android.R.string.cancel, null )
                .create();
        }
            break;

        case SET_DEFAULT: {
            final String dictName = m_selDicts.keySet().iterator().next();
            lstnr = new OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dlg, int item ) {
                        if ( DialogInterface.BUTTON_NEGATIVE == item
                             || DialogInterface.BUTTON_POSITIVE == item ) {
                            setDefault( dictName, R.string.key_default_dict,
                                        R.string.key_default_robodict );
                        }
                        if ( DialogInterface.BUTTON_NEGATIVE == item
                             || DialogInterface.BUTTON_NEUTRAL == item ) {
                            setDefault( dictName, R.string.key_default_robodict,
                                        R.string.key_default_dict );
                        }
                    }
                };
            String lang = DictLangCache.getDictLangName( m_activity,
                                                         dictName );
            message = getString( R.string.set_default_message_fmt,
                                 dictName, lang );
            dialog = makeAlertBuilder()
                .setTitle( R.string.query_title )
                .setMessage( message )
                .setPositiveButton( R.string.button_default_human, lstnr )
                .setNeutralButton( R.string.button_default_robot, lstnr )
                .setNegativeButton( R.string.button_default_both, lstnr )
                .create();
        }
            break;

        case DICT_OR_DECLINE: {
            lstnr = new OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dlg, int item ) {
                        Intent intent = getIntent();
                        ISOCode isoCode = ISOCode
                            .newIf( intent.getStringExtra( MultiService.ISO ) );
                        String name = intent.getStringExtra( MultiService.DICT );
                        m_launchedForMissing = true;
                        DwnldDelegate
                            .downloadDictInBack( m_activity, isoCode, name,
                                                 DictsDelegate.this );
                    }
                };
            lstnr2 = new OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dlg, int item ) {
                        curThis().finish();
                    }
                };

            dialog = MultiService.missingDictDialog( m_activity, getIntent(),
                                                     lstnr, lstnr2 );
            break;
        }

        default:
            dialog = super.makeDialog( alert, params );
            break;
        }

        return dialog;
    } // makeDialog

    @Override
    protected void init( Bundle savedInstanceState )
    {
        m_onServerStr = getString( R.string.dict_on_server );
        m_closedLangs = new HashSet<>();
        String[] closed = XWPrefs.getClosedLangs( m_activity );
        if ( null != closed ) {
            m_closedLangs.addAll( Arrays.asList( closed ) );
        }

        m_expandedItems = new HashSet<>();

        m_locNames = getStringArray( R.array.loc_names );

        getListView().setOnItemLongClickListener( this );

        m_checkbox = (CheckBox)findViewById( R.id.show_remote );
        m_checkbox.setOnClickListener( this );

        getBundledData( savedInstanceState );
        m_checkbox.setSelected( m_showRemote );

        Bundle args = getArguments();
        if ( null != args ) {
            if ( MultiService.isMissingDictBundle( args ) ) {
                showDialogFragment( DlgID.DICT_OR_DECLINE );
            } else {
                boolean showRemote = args.getBoolean( DICT_SHOWREMOTE, false );
                if ( showRemote ) {
                    m_quickFetchMode = true;
                    m_showRemote = true;
                    m_checkbox.setVisibility( View.GONE );

                    ISOCode isoCode = ISOCode.newIf( args.getString( DICT_LANG_EXTRA ) );
                    if ( null != isoCode ) {
                        m_filterLang = DictLangCache.getLangNameForISOCode( m_activity, isoCode );
                        m_closedLangs.remove( m_filterLang );
                    }
                    String name = args.getString( DICT_NAME_EXTRA );
                    if ( null == name ) {
                        new FetchListTask( m_activity ).execute();
                    } else {
                        m_finishOnName = name;
                        startDownload( isoCode, name );
                    }
                }

                downloadNewDict( args );
            }
        }

        m_origTitle = getTitle();

        makeNotAgainBuilder( R.string.key_na_dicts, R.string.not_again_dicts )
            .show();

        Perms23.tryGetPermsNA( this, Perm.STORAGE, R.string.dicts_storage_rationale,
                               R.string.key_na_perms_storage_dicts,
                               Action.STORAGE_CONFIRMED );
    } // init

    @Override
    protected void onResume()
    {
        super.onResume();

        MountEventReceiver.register( this );

        setTitleBar();
    }

    @Override
    protected void onStop()
    {
        MountEventReceiver.unregister( this );
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState( Bundle outState )
    {
        outState.putBoolean( REMOTE_SHOW_KEY, m_showRemote );
        outState.putSerializable( REMOTE_INFO_KEY, m_remoteInfo );
        outState.putSerializable( SEL_DICTS_KEY, (HashMap)m_selDicts );
    }

    private void getBundledData( Bundle sis )
    {
        if ( null != sis ) {
            m_showRemote = sis.getBoolean( REMOTE_SHOW_KEY, false  );
            m_remoteInfo = (HashMap)sis.getSerializable( REMOTE_INFO_KEY );
            m_selDicts = (HashMap)sis.getSerializable( SEL_DICTS_KEY );
        }
    }

    @Override
    public void onClick( View view )
    {
        if ( view == m_checkbox ) {
            switchShowingRemote( m_checkbox.isChecked() );
        } else {
            XWListItem item = (XWListItem)view;
            DictBrowseDelegate.launch( getDelegator(), item.getText(),
                                       (DictLoc)item.getCached() );
        }
    }

    @Override
    protected boolean handleBackPressed()
    {
        boolean handled = 0 < m_selDicts.size();
        if ( handled ) {
            clearSelections();
        } else {
            if ( null != m_lastLang && null != m_lastDict ) {
                Intent intent = new Intent();
                intent.putExtra( RESULT_LAST_LANG, m_lastLang.toString() );
                intent.putExtra( RESULT_LAST_DICT, m_lastDict );
                setResult( Activity.RESULT_OK, intent );
            } else {
                setResult( Activity.RESULT_CANCELED );
            }
        }
        return handled;
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu )
    {
        // int nSel = m_selDicts.size();
        int[] nSels = countSelDicts();
        Utils.setItemVisible( menu, R.id.dicts_select,
                              1 == nSels[SEL_LOCAL] && 0 == nSels[SEL_REMOTE] );

        // NO -- test if any downloadable selected
        Utils.setItemVisible( menu, R.id.dicts_download,
                              0 == nSels[SEL_LOCAL] && 0 < nSels[SEL_REMOTE] );

        Utils.setItemVisible( menu, R.id.dicts_deselect_all,
                              0 < nSels[SEL_LOCAL] || 0 < nSels[SEL_REMOTE] );

        boolean allVolatile = 0 == nSels[SEL_REMOTE] && selItemsVolatile();
        Utils.setItemVisible( menu, R.id.dicts_move,
                              allVolatile && DictUtils.haveWriteableSD() );
        Utils.setItemVisible( menu, R.id.dicts_delete, allVolatile );

        return true;
    }

    @Override
    public boolean onOptionsItemSelected( MenuItem item )
    {
        boolean handled = true;

        switch ( item.getItemId() ) {
        case R.id.dicts_delete: // ideally, disable me when dictbrowse open
            deleteSelected();
            break;
        case R.id.dicts_move:
            showDialogFragment( DlgID.MOVE_DICT );
            break;
        case R.id.dicts_select:
            showDialogFragment( DlgID.SET_DEFAULT );
            break;
        case R.id.dicts_deselect_all:
            clearSelections();
            break;
        case R.id.dicts_download:
            Uri[] uris = new Uri[countNeedDownload()];
            String[] names = new String[uris.length];
            int count = 0;

            for ( Map.Entry<String, Object> entry : m_selDicts.entrySet() ) {
                Object cached = entry.getValue();
                if ( cached instanceof DictInfo ) {
                    DictInfo info = (DictInfo)cached;
                    String name = entry.getKey();
                    Uri uri = Utils.makeDictUriFromCode( m_activity,
                                                         info.mISOCode, name );
                    uris[count] = uri;
                    names[count] = name;
                    ++count;
                }
            }
            DwnldDelegate.downloadDictsInBack( m_activity, uris, names, this );
            break;
        default:
            handled = false;
        }

        return handled;
    }

    private void moveDicts( String[] selNames, DictLoc toLoc )
    {
        if ( toLoc.needsStoragePermission() ) {
            tryGetPerms( Perm.STORAGE, R.string.move_dict_rationale,
                         Action.MOVE_CONFIRMED, selNames, toLoc );
        } else {
            moveDictsWithPermission( selNames, toLoc );
        }
    }

    private void moveDictsWithPermission( Object[] params )
    {
        String[] selNames = (String[])params[0];
        DictLoc toLoc = (DictLoc)params[1];
        moveDictsWithPermission( selNames, toLoc );
    }

    private void moveDictsWithPermission( String[] selNames, DictLoc toLoc )
    {
        for ( String name : selNames ) {
            DictLoc fromLoc = (DictLoc)m_selDicts.get( name );
            if ( fromLoc == toLoc ) {
                Log.w( TAG, "not moving %s: same loc", name );
            } else if ( DictUtils.moveDict( m_activity,
                                            name, fromLoc,
                                            toLoc ) ) {
                if ( m_selViews.containsKey( name ) ) {
                    XWListItem selItem = m_selViews.get( name );
                    selItem.setComment( m_locNames[toLoc.ordinal()] );
                    selItem.setCached( toLoc );
                    selItem.invalidate();
                }
                DBUtils.dictsMoveInfo( m_activity, name,
                                       fromLoc, toLoc );
            } else {
                DbgUtils.showf( m_activity, R.string.toast_no_permission );
                Log.w( TAG, "moveDict(%s) failed", name );
            }
        }
    }

    private void switchShowingRemote( boolean showRemote )
    {
        // if showing for the first time, download remote info and let the
        // completion routine finish (or clear the checkbox if cancelled.)
        // Otherwise just toggle boolean and redraw.
        if ( m_showRemote != showRemote ) {
            m_showRemote = showRemote;
            if ( showRemote && null == m_remoteInfo ) {
                new FetchListTask( m_activity ).execute();
            } else {
                mkListAdapter();
            }
        }
    }

    private int countNeedDownload()
    {
        int result = 0;
        for ( Iterator<Object> iter = m_selDicts.values().iterator();
              iter.hasNext(); ) {
            Object obj = iter.next();
            if ( obj instanceof DictInfo ) {
                ++result;
            }
        }
        return result;
    }

    private void downloadNewDict( Bundle args )
    {
        int loci = args.getInt( UpdateCheckReceiver.NEW_DICT_LOC, 0 );
        if ( 0 < loci ) {
            String name =
                args.getString( UpdateCheckReceiver.NEW_DICT_NAME );
            String url =
                args.getString( UpdateCheckReceiver.NEW_DICT_URL );
            Uri uri = Uri.parse( url );
            DwnldDelegate.downloadDictInBack( m_activity, uri, name, null );
            finish();
        }
    }

    private void setDefault( String name, int keyId, int otherKey )
    {
        ISOCode isoCode = DictLangCache.getDictISOCode( m_activity, name );
        String curLangName = XWPrefs.getPrefsString( m_activity, R.string.key_default_language );
        ISOCode curISOCode = DictLangCache.getLangIsoCode( m_activity, curLangName );
        boolean changeLang = !isoCode.equals( curISOCode );

        SharedPreferences sp
            = PreferenceManager.getDefaultSharedPreferences( m_activity );
        SharedPreferences.Editor editor = sp.edit();
        String key = getString( keyId );
        editor.putString( key, name );

        if ( changeLang ) {
            // change other dict too
            key = getString( otherKey );
            editor.putString( key, name );

            // and change language
            String langName = DictLangCache.getLangNameForISOCode( m_activity, isoCode );
            key = getString( R.string.key_default_language );
            editor.putString( key, langName );
        }

        editor.commit();
    }

    //////////////////////////////////////////////////////////////////////
    // GroupStateListener interface
    //////////////////////////////////////////////////////////////////////
    @Override
    public void onGroupExpandedChanged( Object groupObj, boolean expanded )
    {
        ListGroup lg = (ListGroup)groupObj;
        String langName = m_langs[lg.getPosition()];
        if ( expanded ) {
            m_closedLangs.remove( langName );
            m_adapter.addLangItems( langName );
        } else {
            m_closedLangs.add( langName );
            m_adapter.removeLangItems( langName );
        }
        saveClosed();
    }

    //////////////////////////////////////////////////////////////////////
    // OnItemLongClickListener interface
    //////////////////////////////////////////////////////////////////////
    @Override
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
        for ( Iterator<Object> iter = m_selDicts.values().iterator();
              result && iter.hasNext(); ) {
            Object obj = iter.next();
            if ( obj instanceof DictLoc ) {
                DictLoc loc = (DictLoc)obj;
                if ( loc == DictLoc.BUILT_IN ) {
                    result = false;
                }
            } else {
                result = false;
            }
        }
        return result;
    }

    private void deleteSelected()
    {
        String[] names = getSelNames();
        String msg = getQuantityString( R.plurals.confirm_delete_dict_fmt,
                                        names.length, getJoinedSelNames(names) );

        // Confirm.  And for each dict, warn if (after ALL are deleted) any
        // game will no longer be openable without downloading.  For now
        // anyway skip warning for the case where user will have to switch to
        // a different same-lang wordlist to open a game.

        class LangDelData {
            public LangDelData( ISOCode isoCode ) {
                delDicts = new HashSet<>();
                langName = DictLangCache.getLangNameForISOCode( m_activity, isoCode );
                nDicts = DictLangCache.getDALsHaveLang( m_activity, isoCode ).length;
            }
            public String dictsStr()
            {
                if ( null == m_asArray ) {
                    String[] arr = delDicts.toArray(new String[delDicts.size()]);
                    m_asArray = TextUtils.join( ", ", arr );
                }
                return m_asArray;
            }
            Set<String> delDicts;
            private String m_asArray;
            String langName;
            int nDicts;
        }

        Map<ISOCode, LangDelData> dels = new HashMap<>();
        Set<ISOCode> skipLangs = new HashSet<>();
        for ( String dict : m_selDicts.keySet() ) {
            ISOCode isoCode = DictLangCache.getDictISOCode( m_activity, dict );
            if ( skipLangs.contains( isoCode ) ) {
                continue;
            }
            int nUsingLang = DBUtils.countGamesUsingISOCode( m_activity, isoCode );
            if ( 0 == nUsingLang ) {
                // remember, since countGamesUsingLang is expensive
                skipLangs.add( isoCode );
            } else {
                LangDelData data = dels.get( isoCode );
                if ( null == data ) {
                    data = new LangDelData( isoCode );
                    dels.put( isoCode, data );
                }
                data.delDicts.add( dict );
            }
        }

        for ( Iterator<LangDelData> iter = dels.values().iterator(); iter.hasNext(); ) {
            LangDelData data = iter.next();
            int nLeftAfter = data.nDicts - data.delDicts.size();

            if ( 0 == nLeftAfter ) { // last in this language?
                String newMsg = getString( R.string.confirm_deleteonly_dicts_fmt,
                                           data.dictsStr(), data.langName );
                msg += "\n\n" + newMsg;
            }
        }

        makeConfirmThenBuilder( Action.DELETE_DICT_ACTION, msg )
            .setPosButton( R.string.button_delete )
            .setParams( (Object)names )
            .show();
    } // deleteSelected

    //////////////////////////////////////////////////////////////////////
    // MountEventReceiver.SDCardNotifiee interface
    //////////////////////////////////////////////////////////////////////
    public void cardMounted( boolean nowMounted )
    {
        Log.i( TAG, "cardMounted(%b)", nowMounted );
        // post so other SDCardNotifiee implementations get a chance
        // to process first: avoid race conditions
        post( new Runnable() {
                @Override
                public void run() {
                    mkListAdapter();
                }
            } );
    }

    //////////////////////////////////////////////////////////////////////
    // DlgDelegate.DlgClickNotify interface
    //////////////////////////////////////////////////////////////////////
    @Override
    public boolean onPosButton( Action action, Object... params )
    {
        boolean handled = true;
        switch( action ) {
        case DELETE_DICT_ACTION:
            String[] names = (String[])params[0];
            for ( String name : names ) {
                DictLoc loc = (DictLoc)m_selDicts.get( name );
                deleteDict( name, loc );
            }
            clearSelections();

            mkListAdapter();
            break;
        case UPDATE_DICTS_ACTION:
            Uri[] uris = new Uri[m_needUpdates.size()];
            names = new String[uris.length];
            int count = 0;
            for ( Iterator<String> iter = m_needUpdates.keySet().iterator();
                  iter.hasNext();  ) {
                String name = iter.next();
                names[count] = name;
                uris[count] = m_needUpdates.get( name );
                ++count;
            }
            DwnldDelegate.downloadDictsInBack( m_activity, uris, names, this );
            break;
        case MOVE_CONFIRMED:
            moveDictsWithPermission( params );
            break;
        case STORAGE_CONFIRMED:
            mkListAdapter();
            break;
        default:
            handled = super.onPosButton( action, params );
        }
        return handled;
    }

    @Override
    public boolean onNegButton( Action action, Object... params )
    {
        boolean handled = true;
        switch ( action ) {
        case STORAGE_CONFIRMED:
            mkListAdapter();
            break;
        default:
            handled = super.onNegButton( action, params );
        }
        return handled;
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
    }

    private void startDownload( ISOCode isoCode, String name )
    {
        DwnldDelegate.downloadDictInBack( m_activity, isoCode, name, this );
    }

    private void resetLangs()
    {
        Set<String> langs = new HashSet<>();
        langs.addAll( Arrays.asList(DictLangCache.listLangs( m_activity )) );
        if ( m_showRemote && null != m_remoteInfo ) {
            langs.addAll( m_remoteInfo.keySet() );
        }
        m_langs = langs.toArray( new String[langs.size()] );
        Arrays.sort( m_langs, java.text.Collator.getInstance() );
    }

    private void mkListAdapter()
    {
        resetLangs();
        m_adapter = new DictListAdapter( m_activity );
        setListAdapterKeepScroll( m_adapter );

        m_selViews = new HashMap<>();
    }

    private void saveClosed()
    {
        String[] asArray = m_closedLangs.toArray( new String[m_closedLangs.size()] );
        XWPrefs.setClosedLangs( m_activity, asArray );
    }

    private void clearSelections()
    {
        if ( 0 < m_selDicts.size() ) {
            for ( String name : getSelNames() ) {
                if ( m_selViews.containsKey( name ) ) {
                    XWListItem item = m_selViews.get( name );
                    item.setSelected( false );
                }
            }
            m_selDicts.clear();
            m_selViews.clear();
        }
    }

    private String[] getSelNames()
    {
        Set<String> nameSet = m_selDicts.keySet();
        String[] names = nameSet.toArray( new String[nameSet.size()] );
        return names;
    }

    private String getJoinedSelNames(String[] names)
    {
        return TextUtils.join( ", ", names );
    }

    private String getJoinedSelNames()
    {
        String[] names = getSelNames();
        return getJoinedSelNames( names );
    }

    private int[] countSelDicts()
    {
        int[] results = new int[] { 0, 0 };
        for ( Object obj : m_selDicts.values() ) {
            if ( obj instanceof DictLoc ) {
                ++results[SEL_LOCAL];
            } else if ( obj instanceof DictInfo ) {
                ++results[SEL_REMOTE];
            } else {
                Log.d( TAG, "obj is a: " + obj );
                Assert.failDbg();
            }
        }
        Log.i( TAG, "countSelDicts() => {loc: %d; remote: %d}",
               results[SEL_LOCAL], results[SEL_REMOTE] );
        return results;
    }

    private void setTitleBar()
    {
        int nSels = m_selDicts.size();
        if ( 0 < nSels ) {
            setTitle( getString( R.string.sel_items_fmt, nSels ) );
        } else {
            setTitle( m_origTitle );
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

    public static void downloadForResult( Delegator delegator, RequestCode requestCode,
                                          ISOCode isoCode )
    {
        downloadForResult( delegator, requestCode, isoCode, null );
    }

    public static void downloadForResult( Delegator delegator, RequestCode requestCode )
    {
        downloadForResult( delegator, requestCode, null, null );
    }

    public static void downloadDefaultDict( Context context, ISOCode isoCode,
                                            OnGotLcDictListener lstnr )
    {
        new GetDefaultDictTask( context, isoCode, lstnr ).execute();
    }

    private static final int FAKE_GROUP = 101;
    private static MenuItem addItem(Menu menu, String name)
    {
        return menu.add( FAKE_GROUP, Menu.NONE, Menu.NONE, name );
    }

    private static void doPopup( final Delegator dlgtor, View button,
                                 String curDict, final ISOCode isoCode ) {

        final HashMap<MenuItem, DictAndLoc> itemData
            = new HashMap<>();
        final Context context = dlgtor.getActivity();

        MenuItem.OnMenuItemClickListener listener =
            new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick( MenuItem item )
                {
                    DictAndLoc dal = itemData.get( item );
                    String prevKey = keyForLang( isoCode );
                    DBUtils.setStringFor( context, prevKey, dal.name );
                    DictBrowseDelegate.launch( dlgtor, dal.name,
                                               dal.loc );
                    return true;
                }
            };

        String prevSel = prevSelFor( context, isoCode );
        if ( null == prevSel ) {
            prevSel = curDict;
        }
        PopupMenu popup = new PopupMenu( context, button );
        Menu menu = popup.getMenu();

        // Add at top but save until have dal info
        MenuItem curItem = addItem( menu,
                                    LocUtils.getString( context,
                                                        R.string.cur_menu_marker_fmt,
                                                        curDict ) );
        DictAndLoc[] dals = DictLangCache.getDALsHaveLang( context, isoCode );
        for ( DictAndLoc dal : dals ) {
            boolean isCur = dal.name.equals(curDict);
            MenuItem item = isCur ? curItem : addItem( menu, dal.name );
            item.setOnMenuItemClickListener( listener );
            itemData.put( item, dal );
            item.setChecked( dal.name.equals(prevSel) );
        }
        menu.setGroupCheckable( FAKE_GROUP, true, true );

        popup.show();
    }

    public static boolean handleDictsPopup( Delegator delegator, View button,
                                            String curDict, ISOCode isoCode )
    {
        int nDicts = DictLangCache.getLangCount( delegator.getActivity(), isoCode );
        boolean canHandle = 1 < nDicts;
        if ( canHandle ) {
            doPopup( delegator, button, curDict, isoCode );
        }
        return canHandle;
    }

    private static String keyForLang( ISOCode isoCode )
    {
        return String.format( "%s:lang=%s", TAG, isoCode );
    }

    static String prevSelFor( Context context, ISOCode isoCode )
    {
        String key = keyForLang( isoCode );
        return DBUtils.getStringFor( context, key );
    }

    @Override
    protected DictsDelegate curThis()
    {
        return (DictsDelegate)super.curThis();
    }

    //////////////////////////////////////////////////////////////////////
    // XWListItem.ExpandedListener interface
    //////////////////////////////////////////////////////////////////////
    public void expanded( XWListItem me, boolean expanded )
    {
        final DictInfo info = (DictInfo)me.getCached();
        if ( expanded ) {
            m_expandedItems.add( info ); // may already be there
            LinearLayout view =
                (LinearLayout)inflate( R.layout.remote_dict_details );
            Button button = (Button)view.findViewById( R.id.download_button );
            button.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View view ) {
                        DwnldDelegate.
                            downloadDictInBack( m_activity, info.mISOCode,
                                                info.m_name,
                                                DictsDelegate.this );
                    }
                } );

            long kBytes = (info.m_nBytes + 999) / 1000;
            String msg = getString( R.string.dict_info_fmt, info.m_nWords,
                                    kBytes );
            if ( ! TextUtils.isEmpty(info.m_note) ) {
                msg += "\n" + getString( R.string.dict_info_note_fmt, info.m_note );
            }
            TextView summary = (TextView)view.findViewById( R.id.details );
            summary.setText( msg );

            me.addExpandedView( view );
        } else {
            me.removeExpandedView();
            m_expandedItems.remove( info );
        }
    }

    //////////////////////////////////////////////////////////////////////
    // DwnldActivity.DownloadFinishedListener interface
    //////////////////////////////////////////////////////////////////////
    @Override
    public void downloadFinished( ISOCode isoCode, final String name,
                                  final boolean success )
    {
        if ( success && m_showRemote ) {
            m_lastLang = isoCode;
            m_lastDict = name;
        }

        if ( m_launchedForMissing ) {
            post( new Runnable() {
                    @Override
                    public void run() {
                        if ( success ) {
                            Intent intent = getIntent();
                            if ( MultiService.returnOnDownload( m_activity,
                                                                intent ) ) {
                                finish();
                            } else if ( null != m_finishOnName
                                        && m_finishOnName.equals( name ) ) {
                                finish();
                            }
                        } else {
                            showToast( R.string.download_failed );
                        }
                    }
                } );
        } else {
            mkListAdapter();
        }
    }

    //////////////////////////////////////////////////////////////////////
    // SelectableItem interface
    //////////////////////////////////////////////////////////////////////
    public void itemClicked( SelectableItem.LongClickHandler clicked,
                             GameSummary summary )
    {
        Log.i( TAG, "itemClicked not implemented" );
    }

    public void itemToggled( SelectableItem.LongClickHandler toggled,
                             boolean selected )
    {
        XWListItem dictView = (XWListItem)toggled;
        String lang = dictView.getText();
        if ( selected ) {
            m_selViews.put( lang, dictView );
            m_selDicts.put( lang, dictView.getCached() );
        } else {
            m_selViews.remove( lang );
            m_selDicts.remove( lang );
        }
        invalidateOptionsMenuIf();
        setTitleBar();
    }

    public boolean getSelected( SelectableItem.LongClickHandler obj )
    {
        XWListItem dictView = (XWListItem)obj;
        boolean result = m_selDicts.containsKey( dictView.getText() );
        return result;
    }

    private static class GetDefaultDictTask extends AsyncTask<Void, Void, String> {
        private Context m_context;
        private ISOCode m_lc;
        private String m_langName;
        private OnGotLcDictListener m_lstnr;

        public GetDefaultDictTask( Context context, ISOCode isoCode,
                                   OnGotLcDictListener lnr ) {
            m_context = context;
            m_lc = isoCode;
            m_lstnr = lnr;
        }

        @Override
        public String doInBackground( Void... unused )
        {
            // FIXME: this should pass up the language code to retrieve and
            // parse less data
            String name = null;
            String proc = listDictsProc( m_lc );
            HttpsURLConnection conn = NetUtils.makeHttpsUpdateConn( m_context,
                                                                    proc );
            if ( null != conn ) {
                JSONObject theOne = null;
                String langName = null;
                String json = NetUtils.runConn( conn, new JSONObject() );
                if ( null != json ) {
                    try {
                        JSONObject obj = new JSONObject( json );
                        JSONArray langs = obj.optJSONArray( "langs" );
                        int nLangs = langs.length();
                        for ( int ii = 0; ii < nLangs; ++ii ) {
                            JSONObject langObj = langs.getJSONObject( ii );
                            ISOCode langCode = ISOCode.newIf( langObj.getString( "lc" ) );
                            if ( ! langCode.equals( m_lc ) ) {
                                continue;
                            }
                            // we have our language; look for one marked default;
                            // otherwise take the largest.
                            m_langName = langObj.getString( "lang" );
                            JSONArray dicts = langObj.getJSONArray( "dicts" );
                            int nDicts = dicts.length();
                            int theOneNWords = 0;
                            for ( int jj = 0; jj < nDicts; ++jj ) {
                                JSONObject dict = dicts.getJSONObject( jj );
                                if ( dict.optBoolean( "isDflt", false ) ) {
                                    theOne = dict;
                                    break;
                                } else {
                                    int nWords = dict.getInt( "nWords" );
                                    if ( null == theOne
                                         || nWords > theOneNWords ) {
                                        theOne = dict;
                                        theOneNWords = nWords;
                                    }
                                }
                            }
                        }

                        // If we got here and theOne isn't set, there is
                        // no wordlist available for this language. Set
                        // the flag so we don't try again, even though
                        // we've failed.
                        if ( null == theOne ) {
                            XWPrefs.setPrefsBoolean( m_context,
                                                     R.string.key_got_langdict,
                                                     true );
                        }

                    } catch ( JSONException ex ) {
                        Log.ex( TAG, ex );
                        theOne = null;
                    }
                }

                if ( null != theOne ) {
                    name = theOne.optString( "xwd" );
                }
            }
            return name;
        }

        @Override
        protected void onPostExecute( String name )
        {
            m_lstnr.gotDictInfo( null != name, m_lc, name );
        }
    }

    private class FetchListTask extends AsyncTask<Void, Void, Boolean>
        implements OnCancelListener {
        private Context m_context;

        public FetchListTask( Context context )
        {
            if ( null == m_langs ) {
                resetLangs();
            }
            m_context = context;
            startProgress( R.string.progress_title, R.string.remote_empty, this );
        }

        @Override
        public Boolean doInBackground( Void... unused )
        {
            boolean success = false;
            String proc = listDictsProc( null );
            HttpsURLConnection conn = NetUtils.makeHttpsUpdateConn( m_context,
                                                                    proc );
            if ( null != conn ) {
                String json = NetUtils.runConn( conn, new JSONObject() );
                if ( !isCancelled() ) {
                    if ( null != json ) {
                        post( new Runnable() {
                                public void run() {
                                    setProgressMsg( R.string.remote_digesting );
                                }
                            } );
                    }
                    success = digestData( json );
                }
            }
            return new Boolean( success );
        }

        @Override
        protected void onCancelled()
        {
            m_remoteInfo = null;
            m_showRemote = false;
        }

        @Override
        protected void onCancelled( Boolean success )
        {
            onCancelled();
        }

        @Override
        protected void onPostExecute( Boolean success )
        {
            if ( success ) {
                mkListAdapter();

                if ( 0 < m_needUpdates.size() ) {
                    String[] names = m_needUpdates.keySet()
                        .toArray(new String[m_needUpdates.size()]);
                    String joined = TextUtils.join( ", ", names );
                    makeConfirmThenBuilder( Action.UPDATE_DICTS_ACTION,
                                            R.string.update_dicts_fmt, joined )
                        .setPosButton( R.string.button_download )
                        .show();
                }
            } else {
                makeOkOnlyBuilder( R.string.remote_no_net ).show();
                m_checkbox.setChecked( false );
            }
            stopProgress();
        }

        private boolean digestData( String jsonData )
        {
            boolean success = false;
            JSONArray langs = null;

            m_needUpdates = new HashMap<>();
            if ( null != jsonData ) {
                Set<String> closedLangs = new HashSet<>();
                final Set<String> curLangs =
                    new HashSet<>( Arrays.asList( m_langs ) );

                // DictLangCache hits the DB hundreds of times below. Fix!
                Log.w( TAG, "Fix me I'm stupid" );
                try {
                    // Log.d( TAG, "digestData(%s)", jsonData );
                    JSONObject obj = new JSONObject( jsonData );
                    langs = obj.optJSONArray( "langs" );

                    int nLangs = langs.length();
                    m_remoteInfo = new HashMap<>();
                    for ( int ii = 0; !isCancelled() && ii < nLangs; ++ii ) {
                        JSONObject langObj = langs.getJSONObject( ii );
                        ISOCode isoCode = ISOCode.newIf( langObj.optString( "lc", null ) );
                        String urlLangName = langObj.getString( "lang" );
                        String localLangName = null;
                        if ( null != isoCode ) {
                            localLangName = DictLangCache.getLangNameForISOCode( m_activity, isoCode );
                        }
                        if ( null == localLangName ) {
                            localLangName = urlLangName;
                            DictLangCache.setLangNameForISOCode( m_context,
                                                                 isoCode,
                                                                 urlLangName );
                        }

                        if ( null != m_filterLang &&
                             ! m_filterLang.equals( localLangName ) ) {
                            continue;
                        }

                        if ( ! curLangs.contains( localLangName ) ) {
                            closedLangs.add( localLangName );
                        }

                        JSONArray dicts = langObj.getJSONArray( "dicts" );
                        int nDicts = dicts.length();
                        ArrayList<DictInfo> dictNames = new ArrayList<>();
                        for ( int jj = 0; !isCancelled() && jj < nDicts;
                              ++jj ) {
                            JSONObject dict = dicts.getJSONObject( jj );
                            String name = dict.getString( "xwd" );
                            name = DictUtils.removeDictExtn( name );
                            long nBytes = dict.optLong( "nBytes", -1 );
                            int nWords = dict.optInt( "nWords", -1 );
                            String note = dict.optString( "note" );
                            if ( 0 == note.length() ) {
                                note = null;
                            }
                            DictInfo info = new DictInfo( name, isoCode, localLangName,
                                                          nWords, nBytes, note );

                            if ( !m_quickFetchMode ) {
                                // Check if we have it and it needs an update
                                if ( DictLangCache.haveDict( m_activity, isoCode, name )){
                                    boolean matches = true;
                                    JSONArray sums = dict.optJSONArray("md5sums");
                                    if ( null != sums ) {
                                        matches = false;
                                        String[] curSums = DictLangCache.getDictMD5Sums( m_activity, name );
                                        for ( String curSum : curSums ) {
                                            for ( int kk = 0; !matches && kk < sums.length();
                                                  ++kk ) {
                                                String sum = sums.getString( kk );
                                                matches = sum.equals( curSum );
                                            }
                                        }
                                    }
                                    if ( !matches ) {
                                        Uri uri =
                                            Utils.makeDictUriFromName( m_activity,
                                                                       urlLangName, name );
                                        m_needUpdates.put( name, uri );
                                    }
                                }
                            }
                            dictNames.add( info );
                        }
                        if ( 0 < dictNames.size() ) {
                            DictInfo[] asArray = dictNames
                                .toArray( new DictInfo[dictNames.size()] );
                            Arrays.sort( asArray );
                            m_remoteInfo.put( localLangName, asArray );
                        }
                    }

                    closedLangs.remove( m_filterLang );
                    m_closedLangs.addAll( closedLangs );

                    success = true;
                } catch ( JSONException ex ) {
                    Log.ex( TAG, ex );
                }
            }

            return success;
        } // digestData

        /////////////////////////////////////////////////////////////////
        // DialogInterface.OnCancelListener interface
        /////////////////////////////////////////////////////////////////
        public void onCancel( DialogInterface dialog )
        {
            m_checkbox.setChecked( false );
            cancel( true );
        }
    } // class FetchListTask

    private static String listDictsProc( ISOCode lc )
    {
        String proc = String.format( "listDicts?vc=%d",
                                     BuildConfig.VERSION_CODE );
        if ( null != lc ) {
            proc += String.format( "&lc=%s", lc );
        }
        return proc;
    }

    public static void start( Delegator delegator )
    {
        delegator.addFragment( DictsFrag.newInstance( delegator ), null );
    }

    public static void downloadForResult( Delegator delegator,
                                          RequestCode requestCode,
                                          ISOCode isoCode, String name )
    {
        Bundle bundle = new Bundle();
        bundle.putBoolean( DICT_SHOWREMOTE, true );
        if ( null != isoCode ) {
            bundle.putString( DICT_LANG_EXTRA, isoCode.toString() );
        }
        if ( null != name ) {
            Assert.assertTrue( null != isoCode );
            bundle.putString( DICT_NAME_EXTRA, name );
        }
        delegator.addFragmentForResult( DictsFrag.newInstance( delegator ),
                                        bundle, requestCode );
    }
}
