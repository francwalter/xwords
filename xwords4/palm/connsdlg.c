/* -*-mode: C; fill-column: 77; c-basic-offset: 4; compile-command: "make ARCH=ARM_ONLY MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 2003 by Eric House (xwords@eehouse.org).  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

#if defined XWFEATURE_BLUETOOTH || defined XWFEATURE_RELAY

#include <NetMgr.h>

#include "callback.h"

#include "connsdlg.h"
#include "strutils.h"
#include "palmmain.h"
#include "palmutil.h"
#include "palmir.h"
#include "palmbt.h"

/* When user pops up via Host gadget, we want to get the port to listen on.
 * When pops up via the Guest gadget, we want to get the port and IP address
 * of the host AS WELL AS the local port that we'll tell it we're listening
 * on.  We need local state for both, since user can switch between them and
 * expect state to live as long as the parent dialog isn't exited.
 */

typedef struct ConnsDlgState {
    ListPtr connTypesList;
    XP_U16 serverRole;
    XP_Bool isNewGame;
    CommsConnType conType;
    CommsAddrRec* addr;
    XP_BtAddr btAddr;           /* since there's no field, save it here */
    XP_BtAddrStr tmp;
    char btName[PALM_BT_NAME_LEN];
} ConnsDlgState;

static void
ctlsFromState( PalmAppGlobals* XP_UNUSED_BT(globals), ConnsDlgState* state )
{
    CommsAddrRec* addr = state->addr;
    XP_Bool isNewGame = state->isNewGame;
    state->conType = addr->conType;

    if ( 0 ) {
#ifdef XWFEATURE_RELAY
    } else if ( addr->conType == COMMS_CONN_RELAY ) {
        XP_UCHAR buf[16];
        setFieldStr( XW_CONNS_RELAY_FIELD_ID, addr->u.ip_relay.hostName );
        setFieldEditable( XW_CONNS_RELAY_FIELD_ID, isNewGame );

        StrPrintF( buf, "%d", addr->u.ip_relay.port );
        setFieldStr( XW_CONNS_PORT_FIELD_ID, buf );
        setFieldEditable( XW_CONNS_PORT_FIELD_ID, isNewGame );

        setFieldStr( XW_CONNS_COOKIE_FIELD_ID, addr->u.ip_relay.cookie );
        setFieldEditable( XW_CONNS_COOKIE_FIELD_ID, isNewGame );
#endif
#ifdef XWFEATURE_BLUETOOTH
    } else if ( addr->conType == COMMS_CONN_BT 
                && state->serverRole == SERVER_ISCLIENT ) {
        ControlPtr ctrl = getActiveObjectPtr( XW_CONNS_BT_HOSTTRIGGER_ID );
        if ( '\0' == addr->u.bt.hostName[0] ) {
            palm_bt_addrString( globals, &addr->u.bt.btAddr, &state->tmp );
            CtlSetLabel( ctrl, state->tmp.chars );
        } else {
            CtlSetLabel( ctrl, addr->u.bt.hostName );
        }
        CtlSetEnabled( ctrl, isNewGame );
#endif
    }
} /* ctlsFromState */

static void
stateFromCtls( ConnsDlgState* state )
{
    CommsAddrRec* addr = state->addr;

    addr->conType = state->conType;

    if ( 0 ) {
#ifdef XWFEATURE_RELAY
    } else if ( addr->conType == COMMS_CONN_RELAY ) {
        XP_UCHAR buf[16];
        getFieldStr( XW_CONNS_RELAY_FIELD_ID, addr->u.ip_relay.hostName,
                      sizeof(addr->u.ip_relay.hostName) );

        getFieldStr( XW_CONNS_PORT_FIELD_ID, buf, sizeof(buf) );
        addr->u.ip_relay.port = StrAToI( buf );        

        getFieldStr( XW_CONNS_COOKIE_FIELD_ID, addr->u.ip_relay.cookie,
                      sizeof(addr->u.ip_relay.cookie) );
#endif
#ifdef XWFEATURE_BLUETOOTH
    } else if ( addr->conType == COMMS_CONN_BT 
                && state->serverRole == SERVER_ISCLIENT ) {
        /* Not exactly from a control... */
        XP_MEMCPY( addr->u.bt.hostName, state->btName, 
                   sizeof(addr->u.bt.hostName) );
        XP_MEMCPY( &addr->u.bt.btAddr, &state->btAddr, 
                   sizeof(addr->u.bt.btAddr) );
        LOG_HEX( &addr->u.bt.btAddr, sizeof(addr->u.bt.btAddr), __func__ );
#endif
    }
} /* stateFromCtls */

static void
updateFormCtls( FormPtr form, ConnsDlgState* state )
{
    const XP_U16 relayCtls[] = {
        XW_CONNS_RELAY_LABEL_ID ,
#ifdef XWFEATURE_RELAY
        XW_CONNS_RELAY_FIELD_ID,
        XW_CONNS_PORT_LABEL_ID,
        XW_CONNS_PORT_FIELD_ID,
        XW_CONNS_COOKIE_LABEL_ID,
        XW_CONNS_COOKIE_FIELD_ID,
#endif
        0
    };
    const XP_U16 btGuestCtls[] = {
#ifdef XWFEATURE_BLUETOOTH
        XW_CONNS_BT_HOSTNAME_LABEL_ID,
        XW_CONNS_BT_HOSTTRIGGER_ID,
#else
        XW_CONNS_BT_NOTSUPPORT_LABEL_ID,
#endif
        0
    };
    const XP_U16* allCtls[] = {
        relayCtls, btGuestCtls
    };
    const XP_U16* on;
    XP_U16 i;

    if ( state->conType == COMMS_CONN_RELAY ) {
        on = relayCtls;
    } else if ( state->conType == COMMS_CONN_BT
#ifdef XWFEATURE_BLUETOOTH
                && state->serverRole == SERVER_ISCLIENT 
#endif
                ) {
        on = btGuestCtls;
    } else {
        on = NULL;
    }

    for ( i = 0; i < VSIZE(allCtls); ++i ) {
        const XP_U16* cur = allCtls[i];
        if ( cur != on ) {
            disOrEnableSet( form, cur, XP_FALSE );
        }
    }
    if ( on != NULL ) {
        disOrEnableSet( form, on, XP_TRUE );
    }

} /* updateFormCtls */

static void
cleanupExit( PalmAppGlobals* globals )
{
    XP_FREE( globals->mpool, globals->connState );
    globals->connState = NULL;
    FrmReturnToForm( 0 );
} /* cleanupExit */

static XP_U16
conTypeToSel( CommsConnType conType )
{
    XP_U16 result = 0;
    switch ( conType ) {
    case COMMS_CONN_BT: /* result = 0;  */break;
    case COMMS_CONN_IR: result = 1; break;
    case COMMS_CONN_RELAY: result = 2; break;
    default:
        XP_ASSERT(0);
    }
    return result;
} /* conTypeToSel */

static CommsConnType
selToConType( XP_U16 sel )
{
    CommsConnType conType = COMMS_CONN_BT;
    switch( sel ) {
    case 0: /* conType = COMMS_CONN_BT;  */break;
    case 1: conType = COMMS_CONN_IR; break;
    case 2: conType = COMMS_CONN_RELAY; break;
    default: XP_ASSERT(0);
    }
    return conType;
} /* selToConType */

#ifdef XWFEATURE_BLUETOOTH
static void
browseForDeviceName( PalmAppGlobals* globals, ConnsDlgState* state )
{
    XP_BtAddr btAddr;
    if ( palm_bt_browse_device( globals, &btAddr, 
                                state->btName, sizeof( state->btName ) ) ) {
        CtlSetLabel( getActiveObjectPtr( XW_CONNS_BT_HOSTTRIGGER_ID ),
                     state->btName );
        XP_MEMCPY( &state->btAddr, &btAddr, sizeof(state->btAddr) );
        LOG_HEX( &state->btAddr, sizeof(state->btAddr), __func__ );
    }
} /* browseForDeviceName */
#endif

Boolean
ConnsFormHandleEvent( EventPtr event )
{
    Boolean result;
    PalmAppGlobals* globals;
    ConnsDlgState* state;
    FormPtr form;
    XP_S16 chosen;

    CALLBACK_PROLOGUE();

    globals = getFormRefcon();
    state = globals->connState;
    if ( !state ) {
        state = globals->connState = XP_MALLOC( globals->mpool,
                                                sizeof(*state) );
        XP_MEMSET( state, 0, sizeof(*state) );
    }

    form = FrmGetActiveForm();

    switch ( event->eType ) {
    case frmOpenEvent:
        state->serverRole = 
            (DeviceRole)globals->dlgParams[CONNS_PARAM_ROLE_INDEX];
        state->addr = 
            (CommsAddrRec*)globals->dlgParams[CONNS_PARAM_ADDR_INDEX];
        state->isNewGame = globals->isNewGame;
        XP_MEMCPY( &state->btAddr, &state->addr->u.bt.btAddr, 
                   sizeof(state->btAddr) );

        ctlsFromState( globals, state );

        /* setup connection popup */
        state->connTypesList = getActiveObjectPtr( XW_CONNS_TYPE_LIST_ID );
        XP_ASSERT( state->conType == COMMS_CONN_IR
                   || state->conType == COMMS_CONN_RELAY
                   || state->conType == COMMS_CONN_BT );
        setSelectorFromList( XW_CONNS_TYPE_TRIGGER_ID, state->connTypesList,
                             conTypeToSel(state->conType) );

        updateFormCtls( form, state );

    case frmUpdateEvent:
        FrmDrawForm( form );
        result = true;
        break;

    case ctlSelectEvent:
        result = true;
        switch ( event->data.ctlSelect.controlID ) {

#ifdef XWFEATURE_BLUETOOTH
        case XW_CONNS_BT_HOSTTRIGGER_ID:
            if ( state->isNewGame ) {
                browseForDeviceName( globals, state );
            }
            break;
#endif

        case XW_CONNS_TYPE_TRIGGER_ID:
            if ( state->isNewGame ) {
                chosen = LstPopupList( state->connTypesList );
                if ( chosen >= 0 ) {
                    setSelectorFromList( XW_CONNS_TYPE_TRIGGER_ID, 
                                         state->connTypesList, chosen );
                    state->conType = selToConType( chosen );
                    updateFormCtls( form, state );
                }
            }
            break;

        case XW_CONNS_OK_BUTTON_ID:
            if ( !state->isNewGame ) {
                /* do nothing; same as cancel */
            } else {
                EventType eventToPost = { .eType = connsSettingChgEvent };
                stateFromCtls( state );
                EvtAddEventToQueue( &eventToPost );
            }
            /* FALLTHRU */
        case XW_CONNS_CANCEL_BUTTON_ID:
            cleanupExit( globals );
            break;
        }
        break;
    default:
        result = false;
    }

    CALLBACK_EPILOGUE();
    return result;
} /* ConnsFormHandleEvent */

#endif /* #if defined XWFEATURE_BLUETOOTH || defined XWFEATURE_RELAY */
