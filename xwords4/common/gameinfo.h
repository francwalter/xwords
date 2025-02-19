/* -*- compile-command: "cd ../linux && make -j3 MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 2001-2013 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
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

#ifndef _GAMEINFO_H_
#define _GAMEINFO_H_

#include "nlityp.h"

#ifdef CPLUS
extern "C" {
#endif

typedef struct LocalPlayer {
    XP_UCHAR* name;
    XP_UCHAR* password;
    XP_UCHAR* dictName;
    XP_U16 secondsUsed;
    XP_Bool isLocal;
    XP_U8 robotIQ;              /* 0 means not a robot; 1-100 means how
                                   dumb is it with 1 meaning very smart */
} LocalPlayer;

#define LP_IS_ROBOT(lp) ((lp)->robotIQ != 0)
#define LP_IS_LOCAL(lp) ((lp)->isLocal)

#define DUMB_ROBOT 0
#define SMART_ROBOT 1

typedef struct CurGameInfo {
    XP_UCHAR* dictName;
    LocalPlayer players[MAX_NUM_PLAYERS];
    XP_U32 gameID;      /* uniquely identifies game */
    XP_U16 gameSeconds; /* for timer */
    XP_UCHAR isoCodeStr[MAX_ISO_CODE_LEN+1];
    XP_U8 nPlayers;
    XP_U8 boardSize;
    XP_U8 traySize;
    XP_U8 bingoMin;
    XP_U8 forceChannel;
    DeviceRole serverRole;

    XP_Bool hintsNotAllowed;
    XP_Bool timerEnabled;
    XP_Bool allowPickTiles;
    XP_Bool allowHintRect;
    XP_Bool inDuplicateMode;
    XWPhoniesChoice phoniesAction;
    XP_Bool confirmBTConnect;   /* only used for BT */
} CurGameInfo;

#ifdef DEBUG
# define LOGGI( gip, msg ) game_logGI( (gip), (msg), __func__, __LINE__ )
    void game_logGI( const CurGameInfo* gi, const char* msg,
                     const char* func, int line );
#else
# define LOGGI(gi, msg)
#endif

#ifdef CPLUS
}
#endif

#endif
