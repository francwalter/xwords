/* -*- compile-command: "cd ../linux && make -j3 MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 2001 - 2022 by Eric House (xwords@eehouse.org).  All rights
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

#ifndef _GAME_H_
#define _GAME_H_

#include "comtypes.h"
#include "gameinfo.h"
#include "model.h"
#include "board.h"
#include "comms.h"
#include "server.h"
#include "util.h"

#ifdef CPLUS
extern "C" {
#endif

typedef struct _GameStateInfo {
    XP_U16 visTileCount;
    XP_U16 nPendingMessages;
    XW_TrayVisState trayVisState;
    XP_Bool canHint;
    XP_Bool canUndo;
    XP_Bool canRedo;
    XP_Bool inTrade;
    XP_Bool tradeTilesSelected;
    XP_Bool canChat;
    XP_Bool canShuffle;
    XP_Bool curTurnSelected;
    XP_Bool canHideRack;
    XP_Bool canTrade;
    XP_Bool canRematch;
    XP_Bool canPause;           /* duplicate-mode only */
    XP_Bool canUnpause;         /* duplicate-mode only */
} GameStateInfo;

typedef struct _GameSummary {
    XP_Bool turnIsLocal;
    XP_Bool gameOver;
    XP_S8 turn;
    XP_U32 lastMoveTime;
    XP_S32 dupTimerExpires;
    XP_U8 missingPlayers;
    XP_U8 nPacketsPending;
    XP_U8 channelNo;            /* 0..4 */
    XP_S16 nMoves;
    XP_UCHAR isoCodeStr[MAX_ISO_CODE_LEN+1];
    XP_UCHAR opponents[64];
} GameSummary;

typedef struct _XWGame {
    XW_UtilCtxt* util;
    BoardCtxt* board;
    ModelCtxt* model;
    ServerCtxt* server;
#ifndef XWFEATURE_STANDALONE_ONLY
    CommsCtxt* comms;
#endif
    XP_U32 created;     /* dutil_getCurSeconds() of creation */
} XWGame;

XP_Bool game_makeNewGame( MPFORMAL XWEnv xwe, XWGame* game, CurGameInfo* gi,
                          const CommsAddrRec* selfAddr, const CommsAddrRec* hostAddr,
                          XW_UtilCtxt* util, DrawCtx* draw, const CommonPrefs* cp,
                          const TransportProcs* procs
#ifdef SET_GAMESEED
                          ,XP_U16 gameSeed
#endif
                          );
XP_Bool game_makeRematch( const XWGame* game, XWEnv xwe, XW_UtilCtxt* util,
                          const CommonPrefs* cp, const TransportProcs* procs,
                          XWGame* newGame, const XP_UCHAR* newName );

void game_changeDict( MPFORMAL XWGame* game, XWEnv xwe, CurGameInfo* gi,
                      DictionaryCtxt* dict );

XP_Bool game_makeFromStream( MPFORMAL XWEnv xwe, XWStreamCtxt* stream,
                             XWGame* game, CurGameInfo* gi,
                             XW_UtilCtxt* util, DrawCtx* draw,
                             CommonPrefs* cp, const TransportProcs* procs );

XP_Bool game_makeFromInvite( XWGame* newGame, XWEnv xwe, const NetLaunchInfo* nli,
                             const CommsAddrRec* selfAddr,
                             XW_UtilCtxt* util, DrawCtx* draw,
                             CommonPrefs* cp, const TransportProcs* procs );

void game_saveToStream( const XWGame* game, const CurGameInfo* gi,
                        XWStreamCtxt* stream, XP_U16 saveToken );
void game_saveSucceeded( const XWGame* game, XWEnv xwe, XP_U16 saveToken );

XP_Bool game_receiveMessage( XWGame* game, XWEnv xwe, XWStreamCtxt* stream,
                             const CommsAddrRec* retAddr );

void game_dispose( XWGame* game, XWEnv xwe );

void game_summarize( const XWGame* game, const CurGameInfo* gi, GameSummary* summary );
void game_getState( const XWGame* game, XWEnv xwe, GameStateInfo* gsi );
XP_Bool game_getIsServer( const XWGame* game );
void gi_setNPlayers( CurGameInfo* gi, XWEnv xwe, XW_UtilCtxt* util,
                     XP_U16 nTotal, XP_U16 nHere );
void gi_disposePlayerInfo( MPFORMAL CurGameInfo* gi );
void gi_writeToStream( XWStreamCtxt* stream, const CurGameInfo* gi );
void gi_readFromStream( MPFORMAL XWStreamCtxt* stream, CurGameInfo* gi );
void gi_copy( MPFORMAL CurGameInfo* destGI, const CurGameInfo* srcGi );
#ifdef DEBUG
XP_Bool gi_equal( const CurGameInfo* gi1, const CurGameInfo* gi2 );
#endif
XP_U16 gi_countLocalPlayers( const CurGameInfo* gi, XP_Bool humanOnly );

XP_Bool player_hasPasswd( const LocalPlayer* player );
XP_Bool player_passwordMatches( const LocalPlayer* player, const XP_UCHAR* pwd );
XP_U16 player_timePenalty( CurGameInfo* gi, XP_U16 playerNum );

#ifdef CPLUS
}
#endif

#endif
