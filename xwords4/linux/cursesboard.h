/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 2000 - 2020 by Eric House (xwords@eehouse.org).  All rights
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

#ifndef _CURSESBOARD_H_
#define _CURSESBOARD_H_

#include "cursesmain.h"
#include "nli.h"

typedef struct CursesAppGlobals CursesAppGlobals;
typedef struct CursesBoardState CursesBoardState;

typedef void (*OnGameSaved)( CursesAppGlobals* aGlobals, sqlite3_int64 rowid, bool isNew );

typedef struct _cb_dims {
    int width;
    int top;
    int height;
} cb_dims;

CursesBoardState* cb_init( CursesAppGlobals* aGlobals, LaunchParams* params,
                           CursesMenuState* menuState, OnGameSaved onGameSaved );
void cb_resized( CursesBoardState* cbState, const cb_dims* dims );

void cb_open( CursesBoardState* cbState, sqlite3_int64 rowid, const cb_dims* dims );
bool cb_new( CursesBoardState* cbState, const cb_dims* dims );
void cb_newFor( CursesBoardState* cbState, const NetLaunchInfo* nli,
                const cb_dims* dims );

bool cb_feedRow( CursesBoardState* cbState, sqlite3_int64 rowid,
                 XP_U16 expectSeed, const XP_U8* buf, XP_U16 len,
                 const CommsAddrRec* from );
void cb_feedGame( CursesBoardState* cbState, XP_U32 gameID,
                  const XP_U8* buf, XP_U16 len, const CommsAddrRec* from );
void cb_closeAll( CursesBoardState* cbState );

#endif
