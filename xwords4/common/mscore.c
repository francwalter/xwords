/* -*- compile-command: "cd ../linux && make -j3 MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 1998 - 2020 by Eric House (xwords@eehouse.org).  All rights
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

#include "modelp.h"
#include "util.h"
#include "engine.h"
#include "game.h"
#include "strutils.h"
#include "LocalizedStrIncludes.h"

#ifdef CPLUS
extern "C" {
#endif

#define IMPOSSIBLY_LOW_PENALTY (-20*MAX_TRAY_TILES)

/****************************** prototypes ******************************/
static XP_Bool isLegalMove( ModelCtxt* model, XWEnv xwe, MoveInfo* moves,
                            XP_Bool silent );
static XP_U16 word_multiplier( const ModelCtxt* model, XP_U16 col, XP_U16 row );
static XP_U16 find_end( const ModelCtxt* model, XP_U16 col, XP_U16 row, 
                        XP_Bool isHorizontal );
static XP_U16 find_start( const ModelCtxt* model, XP_U16 col, XP_U16 row, 
                          XP_Bool isHorizontal );
static XP_S16 checkScoreMove( ModelCtxt* model, XWEnv xwe, XP_S16 turn,
                              EngineCtxt* engine, XWStreamCtxt* stream, 
                              XP_Bool silent, WordNotifierInfo* notifyInfo );
static XP_U16 scoreWord( const ModelCtxt* model, XP_U16 turn,
                         const MoveInfo* movei, EngineCtxt* engine, 
                         XWStreamCtxt* stream, WordNotifierInfo* notifyInfo );

/* for formatting when caller wants an explanation of the score.  These live
   in separate function called only when stream != NULL so that they'll have
   as little impact as possible on the speed when the robot's looking for FAST
   scoring */
typedef struct WordScoreFormatter {
    const DictionaryCtxt* dict;

    XP_UCHAR fullBuf[80];
    XP_UCHAR wordBuf[MAX_ROWS+1];
    XP_U16 bufLen, nTiles;

    XP_Bool firstPass;
} WordScoreFormatter;
static void wordScoreFormatterInit( WordScoreFormatter* fmtr, 
                                    const DictionaryCtxt* dict );
static void wordScoreFormatterAddTile( WordScoreFormatter* fmtr, Tile tile, 
                                       XP_U16 tileMultiplier, 
                                       XP_Bool isBlank );
static void wordScoreFormatterFinish( WordScoreFormatter* fmtr, Tile* word, 
                                      XWStreamCtxt* stream );
static void formatWordScore( XWStreamCtxt* stream, XP_U16 wordScore, 
                             XP_U16 moveMultiplier );
static void formatSummary( XWStreamCtxt* stream, XWEnv xwe, const ModelCtxt* model,
                           XP_U16 score );


/* Calculate the score of the current move as it stands.  Flag the score
 * current so we won't have to do this again until something changes to
 * invalidate the score.
 */
static void
scoreCurrentMove( ModelCtxt* model, XWEnv xwe, XP_S16 turn, XWStreamCtxt* stream,
                  WordNotifierInfo* notifyInfo )
{
    PlayerCtxt* player = &model->players[turn];
    XP_S16 score;

    XP_ASSERT( !player->curMoveValid );

    /* recalc goes here */
    score = checkScoreMove( model, xwe, turn, (EngineCtxt*)NULL, stream,
                            XP_TRUE, notifyInfo );
    XP_ASSERT( score >= 0 || score == ILLEGAL_MOVE_SCORE );

    player->curMoveScore = score;
    player->curMoveValid = XP_TRUE;
} /* scoreCurrentMove */

void
adjustScoreForUndone( ModelCtxt* model, XWEnv xwe, const MoveInfo* mi, XP_U16 turn )
{
    XP_U16 moveScore;
    PlayerCtxt* player = &model->players[turn];

    if ( mi->nTiles == 0 ) {
        moveScore = 0;
    } else {
        moveScore = figureMoveScore( model, xwe, turn, mi, (EngineCtxt*)NULL,
                                     (XWStreamCtxt*)NULL,
                                     (WordNotifierInfo*)NULL );
    }
    player->score -= moveScore;
    player->curMoveScore = 0;
    player->curMoveValid = XP_TRUE;
} /* adjustScoreForUndone */

XP_Bool
model_checkMoveLegal( ModelCtxt* model, XWEnv xwe, XP_S16 turn, XWStreamCtxt* stream,
                      WordNotifierInfo* notifyInfo )
{
    XP_S16 score;
    score = checkScoreMove( model, xwe, turn, (EngineCtxt*)NULL, stream, XP_FALSE,
                            notifyInfo );
    return score != ILLEGAL_MOVE_SCORE;
} /* model_checkMoveLegal */

void
invalidateScore( ModelCtxt* model, XP_S16 turn )
{
    model->players[turn].curMoveValid = XP_FALSE;
} /* invalidateScore */

XP_Bool
getCurrentMoveScoreIfLegal( ModelCtxt* model, XWEnv xwe, XP_S16 turn,
                            XWStreamCtxt* stream, 
                            WordNotifierInfo* wni, XP_S16* scoreP )
{
    PlayerCtxt* player = &model->players[turn];
    if ( !player->curMoveValid ) {
        scoreCurrentMove( model, xwe, turn, stream, wni );
    }

    if ( !!scoreP ) {
        *scoreP = player->curMoveScore;
    }
    return player->curMoveScore != ILLEGAL_MOVE_SCORE;
} /* getCurrentMoveScoreIfLegal */

XP_S16
model_getPlayerScore( ModelCtxt* model, XP_S16 player )
{
    return model->players[player].score;
} /* model_getPlayerScore */

/* Based on the current scores based on tiles played and the tiles left in the
 * tray, return an array giving the left-over-tile-adjusted scores for each
 * player.
 */
void
model_figureFinalScores( ModelCtxt* model, ScoresArray* finalScoresP,
                         ScoresArray* tilePenaltiesP )
{
    XP_S16 ii, jj;
    XP_S16 penalties[MAX_NUM_PLAYERS];
    XP_S16 totalPenalty;
    XP_U16 nPlayers = model->nPlayers;
    XP_S16 firstDoneIndex = -1; /* not set unless FIRST_DONE_BONUS is set */
    const TrayTileSet* tray;
    PlayerCtxt* player;
    const DictionaryCtxt* dict = model_getDictionary( model );
    CurGameInfo* gi = model->vol.gi;

    if ( !!finalScoresP ) {
        XP_MEMSET( finalScoresP, 0, sizeof(*finalScoresP) );
    }

    totalPenalty = 0;
    for ( player = model->players, ii = 0; ii < nPlayers; ++player, ++ii ) {
        tray = model_getPlayerTiles( model, ii );

        penalties[ii] = 0;

        /* if there are no tiles left and this guy's the first done, make a
           note of it in case he's to get a bonus.  Note that this assumes
           only one player can be out of tiles. */
        if ( (tray->nTiles == 0) && (firstDoneIndex == -1) ) {
            firstDoneIndex = ii;
        } else {
            for ( jj = tray->nTiles-1; jj >= 0; --jj ) {
                penalties[ii] += dict_getTileValue( dict, tray->tiles[jj] );
            }
        }

        /* include tiles in pending move too for the player whose turn it
           is. */
        for ( jj = player->nPending - 1; jj >= 0; --jj ) {
            Tile tile = player->pendingTiles[jj].tile;
            penalties[ii] += dict_getTileValue(dict, 
                                               (Tile)(tile & TILE_VALUE_MASK));
        }
        totalPenalty += penalties[ii];
    }

    /* now total everybody's scores */
    for ( ii = 0; ii < nPlayers; ++ii ) {
        XP_S16 penalty = (ii == firstDoneIndex)? totalPenalty: -penalties[ii];

        if ( !!finalScoresP ) {
            XP_S16 score = model_getPlayerScore( model, ii );
            if ( gi->timerEnabled ) {
                score -= player_timePenalty( gi, ii );
            }
            finalScoresP->arr[ii] = score + penalty;
        }

        if ( !!tilePenaltiesP ) {
            tilePenaltiesP->arr[ii] = penalty;
        }
    }
} /* model_figureFinalScores */

typedef struct _BlockCheckState {
    ModelCtxt* model;
    XWStreamCtxt* stream;
    WordNotifierInfo* chainNI;
    XP_U16 nBadWords;
    XP_Bool silent;
} BlockCheckState;

static void
blockCheck( const WNParams* wnp, void* closure )
{
    BlockCheckState* bcs = (BlockCheckState*)closure;

    if ( !!bcs->chainNI ) {
        (bcs->chainNI->proc)( wnp, bcs->chainNI->closure );
    }
    if ( !wnp->isLegal ) {
        ++bcs->nBadWords;
        if ( !bcs->silent ) {
            if ( NULL == bcs->stream ) {
                bcs->stream =
                    mem_stream_make_raw( MPPARM(bcs->model->vol.mpool)
                                         dutil_getVTManager(bcs->model->vol.dutil));
            }
            stream_catString( bcs->stream, wnp->word );
            stream_putU8( bcs->stream, '\n' );
        }
    }
}

/* checkScoreMove.
 * Negative score means illegal.
 */
static XP_S16
checkScoreMove( ModelCtxt* model, XWEnv xwe, XP_S16 turn, EngineCtxt* engine,
                XWStreamCtxt* stream, XP_Bool silent, 
                WordNotifierInfo* notifyInfo ) 
{
    XP_Bool isHorizontal;
    XP_S16 score = ILLEGAL_MOVE_SCORE;
    PlayerCtxt* player = &model->players[turn];

    XP_ASSERT( player->nPending <= MAX_TRAY_TILES );

    if ( player->nPending == 0 ) {
        score = 0;

        if ( !!stream ) {
            formatSummary( stream, xwe, model, 0 );
        }

    } else if ( !tilesInLine( model, turn, &isHorizontal ) ) {
        if ( !silent ) { /* tiles out of line */
            util_userError( model->vol.util, xwe, ERR_TILES_NOT_IN_LINE );
        }
    } else {
        MoveInfo moveInfo;
        normalizeMoves( model, turn, isHorizontal, &moveInfo );

        if ( isLegalMove( model, xwe, &moveInfo, silent ) ) {
            /* If I'm testing for blocking, I need to chain my test onto any
               existing WordNotifierInfo. blockCheck() does that. */
            XP_Bool checkDict = PHONIES_BLOCK == model->vol.gi->phoniesAction;
            WordNotifierInfo blockWNI;
            BlockCheckState bcs;
            if ( checkDict ) {
                XP_MEMSET( &bcs, 0, sizeof(bcs) );
                bcs.model = model;
                bcs.chainNI = notifyInfo;
                bcs.silent = silent;
                blockWNI.proc = blockCheck;
                blockWNI.closure = &bcs;
                notifyInfo = &blockWNI;
            }

            XP_S16 tmpScore = figureMoveScore( model, xwe, turn, &moveInfo,
                                               engine, stream, notifyInfo );
            if ( checkDict && 0 < bcs.nBadWords ) {
                if ( !silent ) {
                    XP_ASSERT( !!bcs.stream );
                    const DictionaryCtxt* dict = model_getPlayerDict( model, turn );
                    util_informWordsBlocked( model->vol.util, xwe, bcs.nBadWords,
                                             bcs.stream, dict_getName( dict ) );
                    stream_destroy( bcs.stream );
                }
            } else {
                score = tmpScore;
            }
        }
    }
    return score;
} /* checkScoreMove */

XP_Bool
tilesInLine( ModelCtxt* model, XP_S16 turn, XP_Bool* isHorizontal ) 
{
    XP_Bool xIsCommon, yIsCommon;
    PlayerCtxt* player = &model->players[turn];
    PendingTile* pt = player->pendingTiles;
    XP_U16 commonX = pt->col;
    XP_U16 commonY = pt->row;
    short ii;

    xIsCommon = yIsCommon = XP_TRUE;

    for ( ii = 1; ++pt, ii < player->nPending; ++ii ) {
        // test the boolean first in case it's already been made false
        // (to save time)
        if ( xIsCommon && (pt->col != commonX) ) {
            xIsCommon = XP_FALSE;
        }
        if ( yIsCommon && (pt->row != commonY) ) {
            yIsCommon = XP_FALSE;
        }
    }
    *isHorizontal = !xIsCommon; // so will be vertical if both true
    return xIsCommon || yIsCommon;
} /* tilesInLine */

void
normalizeMI( MoveInfo* moveInfoOut, const MoveInfo* moveInfoIn )
{
    /* use scratch in case in and out are same */
    MoveInfo tmp = *moveInfoIn;
    // const XP_Bool isHorizontal = tmp.isHorizontal;

    XP_S16 lastTaken = -1;
    XP_U16 next = 0;
    for ( XP_U16 ii = 0; ii < tmp.nTiles; ++ii ) {
        XP_U16 lowest = 100; /* high enough to always be changed */
        XP_U16 lowIndex = 100;
        for ( XP_U16 jj = 0; jj < tmp.nTiles; ++jj ) {
            XP_U16 cur = moveInfoIn->tiles[jj].varCoord;
            if ( cur < lowest && cur > lastTaken ) {
                lowest = cur;
                lowIndex = jj;
            }
        }

        XP_ASSERT( lowIndex < MAX_ROWS );
        tmp.tiles[next++] = moveInfoIn->tiles[lowIndex];

        lastTaken = lowest;
    }

    XP_ASSERT( next == tmp.nTiles );
    *moveInfoOut = tmp;
}

void
normalizeMoves( const ModelCtxt* model, XP_S16 turn, XP_Bool isHorizontal,
                MoveInfo* moveInfo )
{
    const PlayerCtxt* player = &model->players[turn];
    const XP_U16 nTiles = player->nPending;

    moveInfo->isHorizontal = isHorizontal;
    moveInfo->nTiles = nTiles;

    if ( 0 < nTiles ) {
        const PendingTile* pt = &player->pendingTiles[0];
        moveInfo->commonCoord = isHorizontal? pt->row:pt->col;

        for ( XP_U16 ii = 0; ii < nTiles; ++ii ) {
            const PendingTile* pt = &player->pendingTiles[ii];
            moveInfo->tiles[ii].tile = pt->tile;
            moveInfo->tiles[ii].varCoord = isHorizontal? pt->col:pt->row;
        }

        normalizeMI( moveInfo, moveInfo );
    }
} /* normalizeMoves */

static XP_Bool
modelIsEmptyAt( const ModelCtxt* model, XP_U16 col, XP_U16 row )
{
    Tile tile;
    XP_U16 nCols = model_numCols( model );
    XP_Bool found = col < nCols 
        && row < nCols 
        && model_getTile( model, col, row, XP_FALSE, -1, &tile, 
                          NULL, NULL, NULL );
    return !found;
} /* modelIsEmptyAt */

/*****************************************************************************
 * Called only after moves have been confirmed to be in the same row, this
 * function works whether the word is horizontal or vertical.
 *
 * For a move to be legal, either of the following must be true: a)
 * if there are squares between those added in this move they must be occupied
 * by previously placed pieces; or b) if these pieces are contiguous then at
 * least one must touch a previously played piece (unless this is the first
 * move) NOTE: this function does not verify that a newly placed piece is on an
 * empty square.  It's assumed that the calling code, most likely that which
 * handles dragging the tiles, will have taken care of that.
 ****************************************************************************/
static XP_Bool
isLegalMove( ModelCtxt* model, XWEnv xwe, MoveInfo* mInfo, XP_Bool silent )
{
    XP_Bool result = XP_TRUE;
    XP_S16 high, low;
    XP_S16 col, row;
    XP_S16* incr;
    XP_S16* commonP;
    XP_U16 star_row = model_numRows(model) / 2;

    XP_S16 nTiles = mInfo->nTiles;
    MoveInfoTile* moves = mInfo->tiles;
    XP_U16 commonCoord = mInfo->commonCoord;

    /* First figure out what the low and high coordinates are in the dimension
       not in common */
    low = moves[0].varCoord;
    high = moves[nTiles-1].varCoord;
    XP_ASSERT( (nTiles == 1) || (low < high) );

    if ( mInfo->isHorizontal ) {
        row = commonCoord;
        incr = &col;
        commonP = &row;
    } else {
        col = commonCoord;
        incr = &row;
        commonP = &col;
    }

    /* are we looking at 2a above? */
    if ( (high - low + 1) > nTiles ) {
        /* there should be no empty tiles between the ends */
        MoveInfoTile* newTile = moves; /* the newly placed tile to be checked */
        for ( *incr = low; *incr <= high; ++*incr ) {
            if ( newTile->varCoord == *incr ) {
                ++newTile;
            } else if ( modelIsEmptyAt( model, col, row ) ) {
                if ( !silent ) {
                    util_userError( model->vol.util, xwe, ERR_NO_EMPTIES_IN_TURN );
                }
                result = XP_FALSE;
                goto exit;
            }
        }
        XP_ASSERT( newTile == &moves[nTiles] );
        goto exit;

        /* else we're looking at 2b: make sure there's some contact UNLESS
           this is the first move */
    } else {
        /* check the ends first */
        if ( low != 0 ) {
            *incr = low - 1;
            if ( !modelIsEmptyAt( model, col, row ) ) {
                goto exit;
            }
        }
        if ( high != MAX_ROWS-1 ) {
            *incr = high+1;
            if ( !modelIsEmptyAt( model, col, row ) ) {
                goto exit;
            }
        }
        /* now the neighbors above... */
        if ( commonCoord != 0 ) {
            --*commonP; /* decrement whatever's not being looped over */
            for ( *incr = low; *incr <= high; ++*incr ) {
                if ( !modelIsEmptyAt( model, col, row ) ) {
                    goto exit;
                }
            }
            ++*commonP;/* undo the decrement */
        }
        /* ...and below */
        if ( commonCoord <= MAX_ROWS - 1 ) {
            ++*commonP;
            for ( *incr = low; *incr <= high; ++*incr ) {
                if ( !modelIsEmptyAt( model, col, row ) ) {
                    goto exit;
                }
            }
            --*commonP;
        }

        /* if we got here, it's illegal unless this is the first move -- i.e.
           unless one of the tiles is on the STAR */
        if ( ( commonCoord == star_row) && 
             ( low <= star_row) && ( high >= star_row ) ) {
            if ( nTiles > 1 ) {
                goto exit;
            } else {
                if ( !silent ) {
                    util_userError(model->vol.util, xwe, ERR_TWO_TILES_FIRST_MOVE);
                }
                result = XP_FALSE;
                goto exit;
            }
        } else {
            if ( !silent ) {
                util_userError( model->vol.util, xwe, ERR_TILES_MUST_CONTACT );
            }
            result = XP_FALSE;
            goto exit;
        }
    }
    XP_ASSERT( XP_FALSE );      /* should not get here */
 exit:
    return result;
} /* isLegalMove */

XP_U16
figureMoveScore( const ModelCtxt* model, XWEnv xwe, XP_U16 turn,
                 const MoveInfo* moveInfo, EngineCtxt* engine,
                 XWStreamCtxt* stream, WordNotifierInfo* notifyInfo )
{
    XP_U16 col, row;
    XP_U16* incr;
    XP_U16 oneScore;
    XP_U16 score = 0;
    short ii;
    short moveMultiplier = 1;
    short multipliers[MAX_TRAY_TILES];
    MoveInfo tmpMI;
    const MoveInfoTile* tiles;
    XP_U16 nTiles = moveInfo->nTiles;

    XP_ASSERT( nTiles > 0 );

    if ( moveInfo->isHorizontal ) {
        row = moveInfo->commonCoord;
        incr = &col;
    } else {
        col = moveInfo->commonCoord;
        incr = &row;
    }

    for ( ii = 0; ii < nTiles; ++ii ) {
        *incr = moveInfo->tiles[ii].varCoord;
        moveMultiplier *= multipliers[ii] =
            word_multiplier( model, col, row );
    }

    oneScore = scoreWord( model, turn, moveInfo, (EngineCtxt*)NULL,
                          stream, notifyInfo );
    if ( !!stream ) {
        formatWordScore( stream, oneScore, moveMultiplier );
    }
    oneScore *= moveMultiplier;
    score += oneScore;

    /* set up the invariant slots in tmpMI */
    tmpMI.isHorizontal = !moveInfo->isHorizontal;
    tmpMI.nTiles = 1;
    tmpMI.tiles[0].varCoord = moveInfo->commonCoord;

    for ( ii = 0, tiles = moveInfo->tiles; ii < nTiles; ++ii, ++tiles ) {

        tmpMI.commonCoord = tiles->varCoord;
        tmpMI.tiles[0].tile = tiles->tile;

        oneScore = scoreWord( model, turn, &tmpMI, engine, stream, notifyInfo );
        if ( !!stream ) {
            formatWordScore( stream, oneScore, multipliers[ii] );
        }
        oneScore *= multipliers[ii];
        score += oneScore;
    }

    const CurGameInfo* gi = model->vol.gi;

    /* did he use all 7 tiles? */
    if ( gi->bingoMin <= nTiles ) {
        score += BINGO_BONUS;

        if ( !!stream ) {
            const XP_UCHAR* bstr;
            XP_UCHAR buf[128];
            if ( gi->bingoMin == gi->traySize ) {
                bstr = dutil_getUserString( model->vol.dutil, xwe, STR_BONUS_ALL );
            } else {
                bstr = dutil_getUserString( model->vol.dutil, xwe, STR_BONUS_ALL_SUB );
                XP_SNPRINTF( buf, VSIZE(buf), bstr, gi->bingoMin );
                bstr = buf;
            }
            stream_catString( stream, bstr );
        }
    }

    if ( !!stream ) {
        formatSummary( stream, xwe, model, score );
    }

    return score;
} /* figureMoveScore */

static XP_U16
word_multiplier( const ModelCtxt* model, XP_U16 col, XP_U16 row )
{
    XWBonusType bonus = model_getSquareBonus( model, col, row );
    switch ( bonus ) {
    case BONUS_DOUBLE_WORD:
        return 2;
    case BONUS_TRIPLE_WORD:
        return 3;
    case BONUS_QUAD_WORD:
        return 4;
    default:
        return 1;
    }
} /* word_multiplier */

static XP_U16
tile_multiplier( const ModelCtxt* model, XP_U16 col, XP_U16 row )
{
    XWBonusType bonus = model_getSquareBonus( model, col, row );
    switch ( bonus ) {
    case BONUS_DOUBLE_LETTER:
        return 2;
    case BONUS_TRIPLE_LETTER:
        return 3;
    case BONUS_QUAD_LETTER:
        return 4;
    default:
        return 1;
    }
} /* tile_multiplier */

static XP_U16
scoreWord( const ModelCtxt* model, XP_U16 turn,
           const MoveInfo* movei, /* new tiles */
           EngineCtxt* engine,/* for crosswise caching */
           XWStreamCtxt* stream, 
           WordNotifierInfo* notifyInfo )
{
    XP_U16 tileMultiplier;
    XP_U16 restScore = 0;
    XP_U16 thisTileValue;
    XP_U16 nTiles = movei->nTiles;
    Tile tile;
    XP_U16 start, end;
    XP_U16* incr;
    XP_U16 col, row;
    const MoveInfoTile* tiles = movei->tiles;
    XP_U16 firstCoord = tiles->varCoord;
    const DictionaryCtxt* dict = model_getPlayerDict( model, turn );

    assertSorted( movei );

    if ( movei->isHorizontal ) {
        row = movei->commonCoord;
        incr = &col;
    } else {
        col = movei->commonCoord;
        incr = &row;
    }

    *incr = tiles[nTiles-1].varCoord;
    end = find_end( model, col, row, movei->isHorizontal );

    /* This is the value *incr needs to start with below */
    *incr = tiles[0].varCoord;
    start = find_start( model, col, row, movei->isHorizontal );

    if ( (end - start) >= 1 ) { /* one-letter word: score 0 */
        WordScoreFormatter fmtr;
        if ( !!stream ) {
            wordScoreFormatterInit( &fmtr, dict );
        }

        if ( IS_BLANK(tiles->tile) ) {
            tile = dict_getBlankTile( dict );
        } else {
            tile = tiles->tile & TILE_VALUE_MASK;
        }
        thisTileValue = dict_getTileValue( dict, tile );

        XP_ASSERT( *incr == tiles[0].varCoord );
        thisTileValue *= tile_multiplier( model, col, row );

        XP_ASSERT( engine == NULL || nTiles == 1 );

        if ( engine != NULL ) {
            XP_ASSERT( nTiles==1 );
            (void)engine_getScoreCache( engine, movei->commonCoord );
        }

        /* for a while, at least, calculate and use the cached crosscheck score
         * each time through in the debug case */
        if ( 0 ) { /* makes keeping parens balanced easier */
#ifdef DEBUG
            /* Always run in DEBUG case */
        } else if ( 1 ) {
#else
            /* If notifyInfo is set, we're counting on the side-effect of its
               proc getting called. So skip caching in that case even on
               release builds. */
        } else if ( engine == NULL || notifyInfo != NULL  ) {
#endif
            Tile checkWordBuf[MAX_ROWS];
            Tile* curTile = checkWordBuf;

            for ( *incr = start; *incr <= end; ++*incr ) {
                XP_U16 tileScore = 0;
                XP_Bool isBlank;

                /* a new move? */
                if ( (nTiles > 0) && (*incr == tiles->varCoord) ) {
                    tile = tiles->tile & TILE_VALUE_MASK;
                    isBlank = IS_BLANK(tiles->tile);
                    /* don't call localGetBlankTile when in silent (robot called)
                     * mode, as the blank won't be known there.  (Assert will
                     * fail.) */

                    tileMultiplier = tile_multiplier( model, col, row );
                    ++tiles;
                    --nTiles;
                } else { /* placed on the board before this move */
                    tileMultiplier = 1;

                    (void)model_getTile( model, col, row, XP_FALSE, -1, &tile,
                                         &isBlank, NULL, NULL );

                    XP_ASSERT( (tile & TILE_VALUE_MASK) == tile );
                }

                *curTile++ = tile; /* save in case we're checking phonies */

                if ( !!stream ) {
                    wordScoreFormatterAddTile( &fmtr, tile, tileMultiplier, 
                                               isBlank );
                }

                if ( isBlank ) {
                    tile = dict_getBlankTile( dict );
                }
                tileScore = dict_getTileValue( dict, tile );

                /* The first tile in the move is already accounted for in
                   thisTileValue, so skip it here. */
                if ( *incr != firstCoord ) {
                    restScore += tileScore * tileMultiplier;
                }
            } /* for each tile */

            if ( !!notifyInfo ) {
                XP_U16 len = curTile - checkWordBuf;
                XP_Bool legal = engine_check( dict, checkWordBuf, len );

                XP_UCHAR buf[(MAX_ROWS*2)+1];
                dict_tilesToString( dict, checkWordBuf, len, buf, 
                                    sizeof(buf), NULL );

                WNParams wnp = { .word = buf, .isLegal = legal, .dict = dict,
#ifdef XWFEATURE_BOARDWORDS
                                 .movei = movei, .start = start, .end = end,
#endif
                };
                (void)(*notifyInfo->proc)( &wnp, notifyInfo->closure );
            }

            if ( !!stream ) {
                wordScoreFormatterFinish( &fmtr, checkWordBuf, stream );
            }
#ifdef DEBUG

        } else if ( engine != NULL ) {
#else
        } else { /* non-debug case we know it's non-null */
#endif
            XP_ASSERT( nTiles == 1 );
            XP_ASSERT( notifyInfo == NULL );
            XP_ASSERT( engine_getScoreCache( engine, movei->commonCoord ) 
                       == restScore );
            restScore = engine_getScoreCache( engine, movei->commonCoord );
        }

        restScore += thisTileValue;
    }

    return restScore;
} /* scoreWord */

static XP_U16
find_start( const ModelCtxt* model, XP_U16 col, XP_U16 row, 
            XP_Bool isHorizontal )
{
    XP_U16* incr = isHorizontal? &col: &row;

    for ( ; ; ) {
        if ( *incr == 0 ) {
            return 0;
        } else {
            --*incr;
            if ( modelIsEmptyAt( model, col, row ) ) {
                return *incr + 1;
            }
        }
    }
} /* find_start */

static XP_U16
find_end( const ModelCtxt* model, XP_U16 col, XP_U16 row, 
          XP_Bool isHorizontal ) 
{
    XP_U16* incr = isHorizontal? &col: &row;
    XP_U16 nCols = model_numCols( model );
    XP_U16 limit = nCols - 1;
    XP_U16 lastGood = *incr;

    XP_ASSERT( col < nCols );
    XP_ASSERT( row < nCols );

    for ( ; ; ) {
        XP_ASSERT( *incr <= limit );
        if ( *incr == limit ) {
            return limit;
        } else {
            ++*incr;
            if ( modelIsEmptyAt( model, col, row ) ) {
                return lastGood;
            } else {
                lastGood = *incr;
            }
        }
    }
} /* find_end */

static void
wordScoreFormatterInit( WordScoreFormatter* fmtr, const DictionaryCtxt* dict )
{
    XP_MEMSET( fmtr, 0, sizeof(*fmtr) );

    fmtr->dict = dict;

    fmtr->firstPass = XP_TRUE;
} /* initWordScoreFormatter */

static void
wordScoreFormatterAddTile( WordScoreFormatter* fmtr, Tile tile, 
                           XP_U16 tileMultiplier, XP_Bool isBlank )
{
    const XP_UCHAR* face;
    XP_UCHAR* fullBufPtr;
    XP_UCHAR* prefix;
    XP_U16 tileScore;

    ++fmtr->nTiles;

    face = dict_getTileString( fmtr->dict, tile );
    XP_ASSERT( XP_STRLEN(fmtr->wordBuf) + XP_STRLEN(face)
               < sizeof(fmtr->wordBuf) );
    XP_STRCAT( fmtr->wordBuf, face );
    if ( isBlank ) {
        tile = dict_getBlankTile( fmtr->dict );
    }

    tileScore = dict_getTileValue( fmtr->dict, tile );

    if ( fmtr->firstPass ) {
        prefix = (XP_UCHAR*)" [";
        fmtr->firstPass = XP_FALSE;
    } else {
        prefix = (XP_UCHAR*)"+";
    }

    fullBufPtr = fmtr->fullBuf + fmtr->bufLen;
    XP_U16 len = sizeof(fmtr->fullBuf) - fmtr->bufLen;
    if ( tileMultiplier > 1 ) {
        fmtr->bufLen += XP_SNPRINTF( fullBufPtr, len,
                                     "%s(%dx%d)", prefix, tileScore,
                                     tileMultiplier );
    } else {
        fmtr->bufLen += XP_SNPRINTF( fullBufPtr, len,
                                     "%s%d", prefix, tileScore );
    }
    
    XP_ASSERT( XP_STRLEN(fmtr->fullBuf)  == fmtr->bufLen );
    XP_ASSERT( fmtr->bufLen  < sizeof(fmtr->fullBuf) );
} /* wordScoreFormatterAddTile */

static void
wordScoreFormatterFinish( WordScoreFormatter* fmtr, Tile* word, 
                          XWStreamCtxt* stream )
{
    XP_UCHAR buf[(MAX_ROWS*2)+1];
    XP_U16 len = dict_tilesToString( fmtr->dict, word, fmtr->nTiles, 
                                     buf, sizeof(buf), NULL );

    if ( !!stream ) {
        stream_putBytes( stream, buf, len );

        stream_putBytes( stream, fmtr->fullBuf, fmtr->bufLen );
        stream_putU8( stream, ']' );
    }
} /* wordScoreFormatterFinish */

static void
formatWordScore( XWStreamCtxt* stream, XP_U16 wordScore, 
                 XP_U16 moveMultiplier )
{
    if ( wordScore > 0 ) {
        XP_U16 multipliedScore = wordScore * moveMultiplier;
        XP_UCHAR tmpBuf[40];
        if ( moveMultiplier > 1 ) {
            XP_SNPRINTF( tmpBuf, sizeof(tmpBuf), 
                         (XP_UCHAR*)" => %d x %d = %d" XP_CR,
                         wordScore, moveMultiplier, multipliedScore );
        } else {
            XP_SNPRINTF( tmpBuf, sizeof(tmpBuf), (XP_UCHAR*)" = %d" XP_CR, 
                         multipliedScore );
        }
        XP_ASSERT( XP_STRLEN(tmpBuf) < sizeof(tmpBuf) );

        stream_catString( stream, tmpBuf );
    }
} /* formatWordScore */

static void
formatSummary( XWStreamCtxt* stream, XWEnv xwe,
               const ModelCtxt* model, XP_U16 score )
{
    XP_UCHAR buf[60];
    XP_SNPRINTF( buf, sizeof(buf),
                 dutil_getUserString(model->vol.dutil, xwe, STRD_TURN_SCORE),
                 score );
    XP_ASSERT( XP_STRLEN(buf) < sizeof(buf) );
    stream_catString( stream, buf );
} /* formatSummary */

#ifdef CPLUS
}
#endif
