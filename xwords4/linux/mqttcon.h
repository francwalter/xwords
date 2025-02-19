/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 2020 by Eric House (xwords@eehouse.org).  All rights reserved.
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

#ifndef _MQTTCON_H_
#define _MQTTCON_H_

#include "main.h"
#include "nli.h"

void mqttc_init( LaunchParams* params );
void mqttc_cleanup( LaunchParams* params );

const MQTTDevID* mqttc_getDevID( LaunchParams* params );
const gchar* mqttc_getDevIDStr( LaunchParams* params );
void mqttc_invite( LaunchParams* params, const NetLaunchInfo* nli,
                   const MQTTDevID* mqttInvitee );
void mqttc_onInviteHandled( LaunchParams* params, const NetLaunchInfo* nli );
XP_S16 mqttc_send( LaunchParams* params, XP_U32 gameID,
                   const XP_U8* buf, XP_U16 len, XP_U16 streamVersion,
                   const MQTTDevID* addressee );
void mqttc_notifyGameGone( LaunchParams* params, const MQTTDevID* addressee, XP_U32 gameID );

bool mqttc_strToDevID( const gchar* str, MQTTDevID* result );

#endif
