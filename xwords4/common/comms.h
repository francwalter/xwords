/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */
/* 
 * Copyright 2001 by Eric House (xwords@eehouse.org).  All rights reserved.
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

#ifndef _COMMS_H_
#define _COMMS_H_

#include "comtypes.h"
#include "mempool.h"
#include "xwrelay.h"

EXTERN_C_START

#define CHANNEL_NONE ((XP_PlayerAddr)0)
#define CONN_ID_NONE 0L

typedef XP_U32 MsgID;           /* this is too big!!! PENDING */
typedef XP_U8  XWHostID;

typedef enum {
    COMMS_CONN_UNUSED,          /* I want errors on uninited case */
    COMMS_CONN_IP_NOUSE,
    COMMS_CONN_RELAY,
    COMMS_CONN_BT,
    COMMS_CONN_IR,

    LAST_____FOO
} CommsConnType;

/* on Palm BtLibDeviceAddressType is a 48-bit quantity.  Linux's typeis the
   same size.  Goal is something all platforms support */
typedef XP_U8 XP_BtAddr[6];

#define MAX_HOSTNAME_LEN 63
typedef struct CommsAddrRec {
    CommsConnType conType;

    union {
        struct {
            XP_UCHAR hostName_ip[MAX_HOSTNAME_LEN + 1];
            XP_U32 ipAddr_ip;      /* looked up from above */
            XP_U16 port_ip;
        } ip;
        struct {
            XP_UCHAR cookie[MAX_COOKIE_LEN + 1];
            XP_UCHAR hostName[MAX_HOSTNAME_LEN + 1];
            XP_U32 ipAddr;      /* looked up from above */
            XP_U16 port;
        } ip_relay;
        struct {
            /* nothing? */
            XP_UCHAR foo;       /* wince doesn't like nothing here */
        } ir;
        struct {
            /* guests can browse for the host to connect to */
            XP_UCHAR hostName[MAX_HOSTNAME_LEN + 1];
            XP_BtAddr btAddr;
        } bt;
    } u;
} CommsAddrRec;

typedef XP_S16 (*TransportSend)( const XP_U8* buf, XP_U16 len, 
                                 const CommsAddrRec* addr,
                                 void* closure );

CommsCtxt* comms_make( MPFORMAL XW_UtilCtxt* util,
                       XP_Bool isServer, 
                       XP_U16 nPlayersHere, XP_U16 nPlayersTotal,
                       TransportSend sendproc, void* closure );

void comms_reset( CommsCtxt* comms, XP_Bool isServer, 
                  XP_U16 nPlayersHere, XP_U16 nPlayersTotal );
void comms_destroy( CommsCtxt* comms );

void comms_setConnID( CommsCtxt* comms, XP_U32 connID );

/* "static" method provides default when no comms present */
void comms_getInitialAddr( CommsAddrRec* addr );
void comms_getAddr( CommsCtxt* comms, CommsAddrRec* addr );
void comms_setAddr( CommsCtxt* comms, const CommsAddrRec* addr );

CommsConnType comms_getConType( CommsCtxt* comms );

CommsCtxt* comms_makeFromStream( MPFORMAL XWStreamCtxt* stream, 
                                 XW_UtilCtxt* util, TransportSend sendproc, 
                                 void* closure );
void comms_start( CommsCtxt* comms );
void comms_writeToStream( CommsCtxt* comms, XWStreamCtxt* stream );

XP_S16 comms_send( CommsCtxt* comms, XWStreamCtxt* stream );
XP_S16 comms_resendAll( CommsCtxt* comms );


XP_Bool comms_checkIncomingStream( CommsCtxt* comms, XWStreamCtxt* stream, 
                                   CommsAddrRec* addr );

# ifdef DEBUG
void comms_getStats( CommsCtxt* comms, XWStreamCtxt* stream );
# endif

EXTERN_C_END

#endif /* _COMMS_H_ */
