/* -*-mode: C; fill-column: 77; c-basic-offset: 4; -*- */
/* 
 * Copyright 1999 - 2001 by Eric House (fixin@peak.org).  All rights reserved.
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

#include <UIResources.h>
#include <SystemMgr.h>

#include "draw.h"

#include "palmmain.h"
#include "xwords4defines.h"
#include "LocalizedStrIncludes.h"

#ifdef DIRECT_PALMOS_CALLS

# define WINDRAWRECTANGLEFRAME(dc,f,rp) \
        (*(dc->winDrawRectangleFrameTrap))( (f), (rp) )
# define WINFILLRECTANGLE(dc,r,f ) \
        (*(dc->winFillRectangleTrap))( (r), (f) )
# define WINDRAWCHARS(dc, s, l, x, y ) \
        (*(dc->winDrawCharsTrap))((s), (l), (x), (y) )
#else

# define WINDRAWRECTANGLEFRAME(dc,f,rp) WinDrawRectangleFrame( f, rp )
# define WINFILLRECTANGLE(dc,r,f ) WinFillRectangle((r),(f))
# define WINDRAWCHARS(dc, s, l, x, y) WinDrawChars( (s), (l), (x), (y) )
#endif

#define NUMRECT_WIDTH 10
#define NUMRECT_HEIGHT 10

#define FONT_HEIGHT 8
#define LINE_SPACING 1

#define SCORE_SEP '.'
#define SCORE_SEPSTR "."

#define TILE_SUBSCRIPT 1             /* draw tile with numbers below letters? */

static XP_Bool palm_common_draw_drawCell( DrawCtx* p_dctx, XP_Rect* rect, 
                                          XP_UCHAR* letters, XP_Bitmap bitmap,
                                          XP_S16 owner, XWBonusType bonus, 
                                          XP_Bool isBlank, 
                                          XP_Bool isPending, XP_Bool isStar );
static void palm_bnw_draw_score_drawPlayer( DrawCtx* p_dctx, XP_S16 playerNum,
                                            XP_Rect* rInner, XP_Rect* rOuter, 
                                            DrawScoreInfo* dsi );
static void palm_bnw_draw_trayBegin( DrawCtx* p_dctx, XP_Rect* rect, 
                                     XP_U16 owner, XP_Bool hasfocus );
static void palm_bnw_draw_trayFinished( DrawCtx* p_dctx );
static void palm_clr_draw_clearRect( DrawCtx* p_dctx, XP_Rect* rectP );
static void palm_draw_drawMiniWindow( DrawCtx* p_dctx, unsigned char* text, 
                                      XP_Rect* rect, void** closureP );

static void
eraseRect( /* PalmDrawCtx* dctx,  */XP_Rect* rect )
{
    WinEraseRectangle( (const RectangleType*)rect, 0 );
} /* eraseRect */

static void
insetRect( XP_Rect* rect, XP_S16 by )
{
    rect->left += by;
    rect->top += by;
    by *= 2;
    rect->width -= by;
    rect->height -= by;
} /* insetRect */

static void
drawBitmapAt( DrawCtx* p_dctx, Int16 resID, Int16 x, Int16  y ) 
{
    MemHandle handle;
    handle = DmGetResource( bitmapRsc, resID );
    XP_ASSERT( handle != NULL );

    if ( handle != NULL ) {
        WinDrawBitmap( (BitmapPtr)MemHandleLock(handle), x, y );
        XP_ASSERT( MemHandleLockCount(handle ) == 1 );
        MemHandleUnlock( handle );
        DmReleaseResource( handle );
    }
} /* drawBitmapAt */

static void
bitmapInRect( PalmDrawCtx* dctx, Int16 resID, XP_Rect* rectP )
{
    XP_U16 left = rectP->left;
    XP_U16 top = rectP->top;
    if ( dctx->globals->gState.showGrid ) {
        ++left;
        ++top;
    }
    drawBitmapAt( (DrawCtx*)dctx, resID, left, top );
} /* bitmapInRect */

static void
palm_common_draw_boardBegin( DrawCtx* p_dctx, XP_Rect* rect, XP_Bool hasfocus )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    PalmAppGlobals* globals = dctx->globals;
    if ( !globals->gState.showGrid ) {
        WINDRAWRECTANGLEFRAME(dctx, rectangleFrame, (RectangleType*)rect);
    }
} /* palm_common_draw_boardBegin */

#ifdef COLOR_SUPPORT
static void
palm_clr_draw_boardBegin( DrawCtx* p_dctx, XP_Rect* rect, XP_Bool hasfocus )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    palm_common_draw_boardBegin( p_dctx, rect, hasfocus );

    WinPushDrawState();

    WinSetForeColor( dctx->drawingPrefs->drawColors[COLOR_BLACK] );
    WinSetTextColor( dctx->drawingPrefs->drawColors[COLOR_BLACK] );
    WinSetBackColor( dctx->drawingPrefs->drawColors[COLOR_WHITE] );
} /* palm_clr_draw_boardBegin */

static void
palm_clr_draw_boardFinished( DrawCtx* p_dctx )
{
    WinPopDrawState();
} /* palm_clr_draw_boardFinished */

static XP_Bool
palm_clr_draw_drawCell( DrawCtx* p_dctx, XP_Rect* rect, 
                        XP_UCHAR* letters, XP_Bitmap bitmap,
                        XP_S16 owner, XWBonusType bonus, XP_Bool isBlank, 
                        XP_Bool isPending, XP_Bool isStar )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;    
    IndexedColorType color;
    XP_U16 index;

    if ( isPending ) { 
        /* don't color background if will invert */
        index = COLOR_WHITE;
    } else if ( !!bitmap || (!!letters && XP_STRLEN((const char*)letters) > 0) ) { 
        index = COLOR_TILE;
    } else if ( bonus == BONUS_NONE ) { 
        index = COLOR_EMPTY;
    } else {
        index = COLOR_DBL_LTTR + bonus - 1;
    }
    color = dctx->drawingPrefs->drawColors[index];
    WinSetBackColor( color );

    index = (owner >= 0)? COLOR_PLAYER1 + owner: COLOR_BLACK;
    color = dctx->drawingPrefs->drawColors[index];
    if ( !!letters ) {
        WinSetTextColor( color );
    }

    return palm_common_draw_drawCell( p_dctx, rect, letters, bitmap, owner,
                                      bonus, isBlank, isPending, isStar );
} /* palm_clr_draw_drawCell */

static void
palm_clr_draw_score_drawPlayer( DrawCtx* p_dctx, XP_S16 playerNum,
                                XP_Rect* rInner, XP_Rect* rOuter, 
                                DrawScoreInfo* dsi )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    IndexedColorType newColor;
    IndexedColorType oldTextColor;
    IndexedColorType oldFGColor;

    newColor = dctx->drawingPrefs->drawColors[COLOR_PLAYER1+playerNum];
    oldTextColor = WinSetTextColor( newColor );
    oldFGColor = WinSetForeColor( newColor );

    palm_bnw_draw_score_drawPlayer( p_dctx, playerNum, rInner, rOuter, dsi );
    WinSetTextColor( oldTextColor );
    WinSetForeColor( oldFGColor );
} /* palm_clr_draw_score_drawPlayer */

#endif

static XP_Bool
palm_common_draw_drawCell( DrawCtx* p_dctx, XP_Rect* rect, 
                           XP_UCHAR* letters, XP_Bitmap bitmap,
                           XP_S16 owner, XWBonusType bonus, XP_Bool isBlank, 
                           XP_Bool isPending, XP_Bool isStar )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    GraphicsAbility able = dctx->able;
    XP_Rect localR = *rect;
    XP_U16 len;
    RectangleType saveClip, intersectR;
    PalmAppGlobals* globals = dctx->globals;
    Boolean showGrid = globals->gState.showGrid;
    Boolean showBonus = bonus != BONUS_NONE;
    Boolean complete;
    XP_Bool empty = XP_TRUE;

    if ( showGrid ) {
        ++localR.width;
        ++localR.height;
    }

    WinGetClip( &saveClip );

    RctGetIntersection( &saveClip, (RectangleType*)&localR, &intersectR );

    /* If there's no rect left inside the clip rgn, exit.  But if the rect's
       only partial go ahead and draw, but still return false indicating that
       we'd like to be allowed to draw again.  This is necessary when a cell
       needs to be redrawn for two reasons, e.g. because its bottom half
       overlaps a form that's gone away (and that supplied the clip region)
       and because its top is covered by the trading miniwindow that's also
       going away.  Under no circumstances draw outside the clip region or
       risk overdrawing menus, other forms, etc. */

    if ( intersectR.extent.x == 0 || intersectR.extent.y == 0 ) {
        return XP_FALSE;
    } else if ( intersectR.extent.x < localR.width || 
                intersectR.extent.y < localR.height ) {
        complete = XP_FALSE;
    } else {
        complete = XP_TRUE;
    }
    WinSetClip( (RectangleType*)&intersectR );

    if ( showGrid ) {
        insetRect( &localR, 1 );
    }

    eraseRect( &localR );

    if ( !!letters ) {
        len = XP_STRLEN( (const char*)letters );
        if ( len > 0 ) {
            XP_S16 strWidth = FntCharsWidth( (const char*)letters, len );
            XP_U16 x = localR.left + ((localR.width-strWidth) / 2);
            if ( len == 1 ) {
                ++x;
            }
            WINDRAWCHARS( dctx, (const char*)letters, len, x, localR.top-1 );

            showBonus = XP_FALSE;
            empty = XP_FALSE;
        }
    } else if ( !!bitmap ) {
        XP_Bool doColor = (able == COLOR) && (owner >= 0);
        /* cheating again; this belongs in a palm_clr method.  But the
           special bitmaps are rare enough that we shouldn't change the palm
           draw state every time. */
        if ( doColor ) {
            WinSetForeColor( 
                dctx->drawingPrefs->drawColors[COLOR_PLAYER1+owner] );
        }
        WinDrawBitmap( (BitmapPtr)bitmap, localR.left+1, localR.top+1 );
        if ( doColor ) {
            WinSetForeColor( dctx->drawingPrefs->drawColors[COLOR_BLACK] );
        }
        showBonus = doColor;	/* skip bonus in B&W case; can't draw both! */
        empty = XP_FALSE;
    }

    if ( isStar ) {
        bitmapInRect( dctx, STAR_BMP_RES_ID, rect );
    } else if ( showBonus && (able == ONEBIT) ) {
        /* this is my one refusal to totally factor bandw and color
           code */
        WinSetPattern( (const CustomPatternType*)
                       &dctx->u.bnw.valuePatterns[bonus-1] );
        WINFILLRECTANGLE( dctx, (RectangleType*)&localR, 0 );
    } else if ( !showBonus && empty && !showGrid ) {
        /* should this be in the v-table so I don't have to test each
           time? */
        if ( globals->romVersion >= 35 ) {
            WinDrawPixel( localR.left + ((PALM_BOARD_SCALE-1)/2), 
                          localR.top + ((PALM_BOARD_SCALE-1)/2) );
        } else {
            RectangleType r;
            r.topLeft.x = localR.left + ((PALM_BOARD_SCALE-1)/2);
            r.topLeft.y = localR.top + ((PALM_BOARD_SCALE-1)/2);
            r.extent.x = r.extent.y = 1;
            WinDrawRectangle( &r, 0 );
        }
    }

    if ( isPending ) {
        XP_ASSERT( !!bitmap ||
                   (!!letters && XP_STRLEN((const char*)letters)>0));
        WinInvertRectangle( (RectangleType*)&localR, 0 );
    }

    if ( showGrid ) {
        WINDRAWRECTANGLEFRAME(dctx, rectangleFrame, (RectangleType*)&localR);
    }

    if ( isBlank ) {
        WinEraseRectangleFrame( roundFrame, (RectangleType*)&localR );
    }

    WinSetClip( &saveClip );
    return complete;
} /* palm_common_draw_drawCell */

static void
palm_draw_invertCell( DrawCtx* p_dctx, XP_Rect* rect )
{
    XP_Rect localR = *rect;
    /*     insetRect( &localR, 3 ); */
    localR.top += 3;
    localR.left += 3;
    localR.width -= 5;
    localR.height -= 5;
    WinInvertRectangle( (RectangleType*)&localR, 0 );
} /* palm_draw_invertCell */

static void
palm_clr_draw_trayBegin( DrawCtx* p_dctx, XP_Rect* rect, 
                         XP_U16 owner, XP_Bool hasfocus )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    dctx->trayOwner = owner;

    WinPushDrawState();
    WinSetBackColor( dctx->drawingPrefs->drawColors[COLOR_TILE] );
    WinSetTextColor( dctx->drawingPrefs->drawColors[COLOR_PLAYER1+owner] );
    WinSetForeColor( dctx->drawingPrefs->drawColors[COLOR_PLAYER1+owner] );

    palm_bnw_draw_trayBegin( p_dctx, rect, owner, hasfocus );
} /* palm_clr_draw_trayBegin */

static void
palm_bnw_draw_trayBegin( DrawCtx* p_dctx, XP_Rect* rect, XP_U16 owner,
                         XP_Bool hasfocus )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    WinGetClip( &dctx->oldTrayClip );
    WinSetClip( (RectangleType*)rect );
} /* palm_draw_trayBegin */

static void
palm_clr_draw_trayFinished( DrawCtx* p_dctx )
{
    palm_bnw_draw_trayFinished( p_dctx );
    WinPopDrawState();
} /* palm_clr_draw_trayFinished */

static void
palm_bnw_draw_trayFinished( DrawCtx* p_dctx )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;    
    WinSetClip( &dctx->oldTrayClip );
} /* palm_draw_trayFinished */

static void
palm_draw_drawTile( DrawCtx* p_dctx, XP_Rect* rect, 
                    XP_UCHAR* letters, XP_Bitmap bitmap,
                    XP_S16 val, XP_Bool highlighted )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    char valBuf[3];
    XP_Rect localR = *rect;
    XP_U16 len, width;
    WinHandle numberWin = dctx->numberWin;

    draw_clearRect( p_dctx, &localR );

    localR.width -= 3;
    localR.height -= 3;
    localR.top += 2;
    localR.left += 2;

    /* this will fill it with the tile background color */
    WinEraseRectangle( (const RectangleType*)&localR, 0 );

    if ( !!letters ) {
        if ( *letters != LETTER_NONE ) { /* blank */
            FontID curFont = FntGetFont();
            FntSetFont( largeFont );
#ifdef TILE_SUBSCRIPT
            WINDRAWCHARS( dctx, (const char*)letters, 1, localR.left+2, 
                          rect->top+2 );
#else
            WINDRAWCHARS( dctx, letters, 1, localR.left+2, rect->top+7 );
#endif
            FntSetFont( curFont );
        }
    } else if ( !!bitmap ) {
        WinDrawBitmap( (BitmapPtr)bitmap, localR.left+2, localR.top+2 );
    }

    if ( val >= 0 ) {
        RectangleType numRect = {{0,0}, {NUMRECT_WIDTH, NUMRECT_HEIGHT}};
        WinHandle curWind;

        (void)StrPrintF( valBuf, "%d", val );
        len = XP_STRLEN((const char*)valBuf);

        XP_ASSERT( !!numberWin );
        curWind = WinSetDrawWindow( numberWin );

        WinEraseRectangle( &numRect, 0 );

        WinDrawChars( valBuf, len, 0, 0 );
        width = FntCharsWidth( valBuf, len );     

        (void)WinSetDrawWindow( curWind );
        numRect.extent.x = width;
#ifdef TILE_SUBSCRIPT
        WinCopyRectangle( numberWin, 0, &numRect, 
                          localR.left + localR.width - width,
                          localR.top + localR.height - 10,
                          winOverlay );
#else
        WinCopyRectangle( numberWin, 0, &numRect, 
                          localR.left + localR.width - width,
                          localR.top,
                          winOverlay );
#endif
    }

    WINDRAWRECTANGLEFRAME( dctx, rectangleFrame, (RectangleType*)&localR );
    if ( highlighted ) {
        insetRect( &localR, 1 );
        WINDRAWRECTANGLEFRAME(dctx, rectangleFrame, (RectangleType*)&localR );
    }
} /* palm_draw_drawTile */

static void
palm_draw_drawTileBack( DrawCtx* p_dctx, XP_Rect* rect )
{
    palm_draw_drawTile( p_dctx, rect, (unsigned char*)"?", (XP_Bitmap)NULL, 
                        -1, XP_FALSE );
} /* palm_draw_drawTileBack */

static void
palm_draw_drawTrayDivider( DrawCtx* p_dctx, XP_Rect* rect, XP_Bool selected )
{
    XP_Rect lRect = *rect;

    draw_clearRect( p_dctx, &lRect );

    ++lRect.left;
    --lRect.width;

    if ( selected ) {
        PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
        short pattern[] = { 0xFF00, 0xFF00, 0xFF00, 0xFF00 };

        WinSetPattern( (const CustomPatternType*)&pattern );
        WINFILLRECTANGLE( dctx, (RectangleType*)&lRect, 0 );

    } else {
        WinDrawRectangle( (RectangleType*)&lRect, 0 );
    }
} /* palm_draw_drawTrayDivider */

static void 
palm_bnw_draw_clearRect( DrawCtx* p_dctx, XP_Rect* rectP )
{
    eraseRect( rectP );
} /* palm_draw_clearRect */

static void 
palm_clr_draw_clearRect( DrawCtx* p_dctx, XP_Rect* rectP )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    WinPushDrawState();
    WinSetBackColor( dctx->drawingPrefs->drawColors[COLOR_WHITE] );
    eraseRect( rectP );
    WinPopDrawState();
} /* palm_clr_draw_clearRect */

static void
palm_clr_draw_drawMiniWindow( DrawCtx* p_dctx, unsigned char* text, 
                              XP_Rect* rect, void** closureP )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    WinSetBackColor( dctx->drawingPrefs->drawColors[COLOR_WHITE] );
    WinSetTextColor( dctx->drawingPrefs->drawColors[COLOR_BLACK] );

    palm_draw_drawMiniWindow( p_dctx, text, rect, closureP );
} /* palm_clr_draw_drawMiniWindow */

static void
palm_draw_drawBoardArrow( DrawCtx* p_dctx, XP_Rect* rectP, 
                          XWBonusType cursorBonus, XP_Bool vertical )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    Int16 resID = vertical? DOWN_ARROW_RESID:RIGHT_ARROW_RESID;

    bitmapInRect( dctx, resID, rectP );
} /* palm_draw_drawBoardArrow */

#ifdef COLOR_SUPPORT
static void
palm_clr_draw_drawBoardArrow( DrawCtx* p_dctx, XP_Rect* rectP, 
                               XWBonusType cursorBonus, XP_Bool vertical )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    XP_U16 index;

    if ( cursorBonus == BONUS_NONE ) { 
        index = COLOR_EMPTY;
    } else {
        index = COLOR_DBL_LTTR + cursorBonus - 1;
    }

    WinSetBackColor( dctx->drawingPrefs->drawColors[index] );
    palm_draw_drawBoardArrow( p_dctx, rectP, cursorBonus, vertical );
} /* palm_clr_draw_drawBoardArrow */
#endif

static void
palm_draw_scoreBegin( DrawCtx* p_dctx, XP_Rect* rect, XP_U16 numPlayers, 
                      XP_Bool hasfocus )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    WinGetClip( &dctx->oldScoreClip );
    WinSetClip( (RectangleType*)rect );

    eraseRect( rect );
} /* palm_draw_scoreBegin */

/* rectContainsRect: Dup of something in board.c.  They could share if I were
 * willing to link from here out.
 */
static XP_Bool
rectContainsRect( XP_Rect* rect1, XP_Rect* rect2 )
{
    return ( rect1->top <= rect2->top
             && rect1->left <= rect2->left
             && rect1->top + rect1->height >= rect2->top + rect2->height
             && rect1->left + rect1->width >= rect2->left + rect2->width );
} /* rectContainsRect */

static XP_Bool
palm_draw_vertScrollBoard( DrawCtx* p_dctx, XP_Rect* rect, XP_S16 dist )
{
    RectangleType clip;
    XP_Bool canDoIt;

    /* if the clip rect doesn't contain the scroll rect we can't do anything
       right now: WinScrollRectangle won't do its job. */
    WinGetClip( &clip );
    canDoIt = rectContainsRect( (XP_Rect*)&clip, rect );

    if ( canDoIt ) {
        RectangleType vacated;
        WinDirectionType dir;

        if ( dist >= 0 ) {
            dir = winUp;
        } else {
            dir = winDown;
            dist = -dist;
        }

        WinScrollRectangle( (RectangleType*)rect, dir, dist, &vacated );
        *rect = *(XP_Rect*)&vacated;
    }
    return canDoIt;
} /* palm_draw_vertScrollBoard */

/* Given some text, determine its bounds and draw it if requested, else
 * return the bounds.  If the width of the string exceeds that of the rect in
 * which it can be fit, split it at ':'.
 */
static void
palmMeasureDrawText( PalmDrawCtx* dctx, XP_Rect* bounds, XP_UCHAR* txt, 
                     XP_Bool vertical, XP_Bool isTurn, XP_UCHAR skipChar, 
                     XP_Bool draw )
{
    XP_U16 len = XP_STRLEN( (const char*)txt );
    XP_U16 widths[2];
    XP_U16 maxWidth, height;
    XP_U16 nLines = 1;
    XP_U16 secondLen = 0;
    XP_UCHAR* second = NULL;

    widths[0] = FntCharsWidth( (const char*)txt, len ) + 1;

    if ( widths[0] > bounds->width ) {
        XP_UCHAR ch[2];
        ch[0] = skipChar; 
        ch[1] = '\0';

        XP_ASSERT( skipChar );
        second = (XP_UCHAR*)StrStr( (const char*)txt, (const char*)ch );
        XP_ASSERT( !!second );
        ++second;		/* colon's on the first line */
        secondLen = XP_STRLEN( (const char*)second );

        len -= secondLen;

        if ( skipChar ) {
            --len;
        }

        widths[0] = FntCharsWidth( (const char*)txt, len );
        widths[1] = FntCharsWidth( (const char*)second, secondLen );
        maxWidth = XP_MAX( widths[0], widths[1] );
        ++nLines;
    } else {
        maxWidth = widths[0];
    }

    height = nLines * FONT_HEIGHT + ( LINE_SPACING * (nLines-1) );
    if ( vertical && isTurn ) {
        height += 5;		/* for the horizontal bars */
    }

    XP_ASSERT( height <= bounds->height );
    XP_ASSERT( maxWidth <= bounds->width );

    if ( draw ) {
        XP_U16 x, y;

        /* Center what we'll be drawing by advancing the appropriate
           coordinate to eat up half the extra space */
        x = bounds->left + 1;// + (bounds->width - widths[0]) / 2;
        y = bounds->top;
        if ( vertical && isTurn ) {
            y += 1;
        } else {
            y -= 2;
        }

        WINDRAWCHARS( dctx, (const char*)txt, len, x, y );
        if ( nLines == 2 ) {
            XP_ASSERT( vertical );
            y += FONT_HEIGHT + LINE_SPACING;
            x = bounds->left + ((bounds->width - widths[1]) / 2);
            WINDRAWCHARS( dctx, (const char*)second, secondLen, x, y );
        }

    } else {
        /* return the measurements */
        bounds->width = maxWidth;
        bounds->height = height;
    }

} /* palmMeasureDrawText */

static void
palmFormatRemText( PalmDrawCtx* dctx, XP_UCHAR* buf, XP_S16 nTilesLeft )
{
    XP_UCHAR* remStr = (*dctx->getResStrFunc)(dctx->globals, STR_REMTILES);
    if ( nTilesLeft < 0 ) {
        nTilesLeft = 0;
    }
    (void)StrPrintF( (char*)buf, (const char*)remStr, nTilesLeft );
} /* palmFormatRemText */

static void
palm_draw_measureRemText( DrawCtx* p_dctx, XP_Rect* rect, XP_S16 nTilesLeft,
                          XP_U16* widthP, XP_U16* heightP )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    PalmAppGlobals* globals = dctx->globals;
    XP_UCHAR buf[10];
    XP_Rect localRect;
    XP_Bool isVertical = !globals->gState.showGrid;

    palmFormatRemText( dctx, buf, nTilesLeft );

    localRect = *rect;
    palmMeasureDrawText( dctx, &localRect, buf, isVertical, XP_FALSE,
                         ':', XP_FALSE );
    
    *widthP = localRect.width;
    *heightP = localRect.height;
} /* palm_draw_measureRemText */

static void
palm_draw_drawRemText( DrawCtx* p_dctx, XP_Rect* rInner, XP_Rect* rOuter, 
                       XP_S16 nTilesLeft )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    PalmAppGlobals* globals = dctx->globals;
    XP_UCHAR buf[10];

    XP_Bool isVertical = !globals->gState.showGrid;

    palmFormatRemText( dctx, buf, nTilesLeft );

    palmMeasureDrawText( dctx, rInner, buf, isVertical, XP_FALSE,
                         ':', XP_TRUE );
} /* palm_draw_drawRemText */

/* Measure text that'll be drawn for player.  If vertical, it'll often get
 * split into two lines, esp after the number of remaining tiles appears.
 */
static void
palmFormatScore( char* buf, DrawScoreInfo* dsi, XP_Bool vertical )
{
    char borders[] = {'�', '\0'};
    char remBuf[10];
    char* remPart = remBuf;

    if ( vertical || !dsi->isTurn ) {
        borders[0] = '\0';
    }

    if ( dsi->nTilesLeft >= 0 ) {
        StrPrintF( remPart, SCORE_SEPSTR "%d", dsi->nTilesLeft );
    } else {
        *remPart = '\0';
    }

    (void)StrPrintF( buf, "%s%d%s%s", borders, dsi->score, remPart, borders );
} /* palmFormatScore */

static void
palm_draw_measureScoreText( DrawCtx* p_dctx, XP_Rect* rect, DrawScoreInfo* dsi,
                            XP_U16* widthP, XP_U16* heightP )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    PalmAppGlobals* globals = dctx->globals;
    char buf[20];
    /*     FontID oldFont = 0; */
    XP_Bool vertical = !globals->gState.showGrid;
    XP_Rect localRect = *rect;

    /*     if ( !vertical && dsi->selected ) { */
    /* 	oldFont = FntGetFont(); */
    /* 	FntSetFont( boldFont ); */
    /*     } */

    palmFormatScore( buf, dsi, vertical );
    palmMeasureDrawText( dctx, &localRect, (XP_UCHAR*)buf, dsi->isTurn, vertical, 
                         SCORE_SEP, XP_FALSE );

    *widthP = localRect.width;
    *heightP = localRect.height;

    /*     result = widthAndText( buf, score, nTilesInTray, isTurn,  */
    /* 			   !globals->gState.showGrid, &ignore, ignoreLines ); */

    /*     if ( !vertical && dsi->selected ) { */
    /* 	FntSetFont( oldFont ); */
    /*     } */
} /* palm_draw_measureScoreText */

static void
palm_bnw_draw_score_drawPlayer( DrawCtx* p_dctx, XP_S16 playerNum,
                                XP_Rect* rInner, XP_Rect* rOuter, 
                                DrawScoreInfo* dsi )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    PalmAppGlobals* globals = dctx->globals;
    XP_UCHAR scoreBuf[20];
    XP_Bool vertical = !globals->gState.showGrid;

    palmFormatScore( (char*)scoreBuf, dsi, vertical );
    palmMeasureDrawText( dctx, rInner, (XP_UCHAR*)scoreBuf, vertical, dsi->isTurn,
                         SCORE_SEP, XP_TRUE );

    if ( vertical && dsi->isTurn ) {
        RectangleType r = *(RectangleType*)rInner;
        XP_U16 x, y;

        x = r.topLeft.x;
        y = r.topLeft.y + 1;

        WinDrawLine( x, y, x + r.extent.x - 1, y);
        y += r.extent.y - 3;
        WinDrawLine( x, y, x + r.extent.x - 1, y );
    }

    if ( dsi->selected ) {
        WinInvertRectangle( (RectangleType*)rInner, 0 );
        /* 	if ( !vertical ) { */
        /* 	    FntSetFont( oldFont ); */
        /* 	} */
    }
} /* palm_bnw_draw_score_drawPlayer */

#define PENDING_DIGITS 3
static void
palm_draw_score_pendingScore( DrawCtx* p_dctx, XP_Rect* rect, XP_S16 score,
                              XP_U16 playerNum )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    XP_UCHAR* str;
    char buf[PENDING_DIGITS+1] = "000";
    RectangleType oldClip, newClip;
    XP_U16 x = rect->left + 1;

    str = (*dctx->getResStrFunc)( dctx->globals, STR_PTS );

    WinGetClip( &oldClip );
    RctGetIntersection( &oldClip, (RectangleType*)rect, &newClip );
    if ( newClip.extent.y > 0 ) {
        WinSetClip( &newClip );
        eraseRect( rect );

        if ( score >= 0 ) {
            XP_UCHAR tbuf[4];
            UInt16 len;
            if ( score <= 999 ) {
                StrPrintF( (char*)tbuf, "%d", score );
            } else {
                StrCopy( (char*)tbuf, "wow" ); /* thanks, Marcus :-) */
            }
            len = XP_STRLEN( (const char*)tbuf );
            XP_MEMCPY( &buf[PENDING_DIGITS-len], tbuf, len );
        } else {
            StrCopy( buf, "???" );
        }

        if ( rect->height >= PALM_TRAY_SCALEH ) {
            WINDRAWCHARS( dctx, (const char*)str, 
                          XP_STRLEN((const char*)str), x, rect->top );
        }
        WINDRAWCHARS( dctx, buf, PENDING_DIGITS, x, 
                      rect->top + (rect->height/2) - 1 );
        WinSetClip( &oldClip );
    }
} /* palm_draw_score_pendingScore */

static void
palm_draw_scoreFinished( DrawCtx* p_dctx )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    WinSetClip( &dctx->oldScoreClip );
} /* palm_draw_scoreFinished */

static void
palmFormatTimerText( XP_UCHAR* buf, XP_S16 secondsLeft )
{
    XP_U16 minutes, seconds;
    XP_UCHAR secBuf[6];

    if ( secondsLeft < 0 ) {
        *buf++ = '-';
        secondsLeft *= -1;
    }
    minutes = secondsLeft / 60;
    seconds = secondsLeft % 60;

    /* StrPrintF can't do 0-padding; do it manually.  Otherwise 5:03 will
       come out 5:3 */
    StrPrintF( (char*)secBuf, "0%d", seconds );
    StrPrintF( (char*)buf, "%d:%s", minutes,
               secBuf[2] == '\0'? secBuf:&secBuf[1] );
} /* palmFormatTimerText */

static void
palm_draw_drawTimer( DrawCtx* p_dctx, XP_Rect* rInner, XP_Rect* rOuter,
                     XP_U16 player, XP_S16 secondsLeft )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    XP_UCHAR buf[10];
    XP_Rect localR = *rInner;
    RectangleType saveClip;
    XP_U16 len, width;

    palmFormatTimerText( buf, secondsLeft );
    len = XP_STRLEN( (const char*)buf );

    width = FntCharsWidth( (const char*)buf, len );

    eraseRect( &localR );

    if ( width < localR.width ) {
        localR.left += localR.width - width;
        localR.width = width;
    }

    localR.top -= 2;
    localR.height += 1;

    WinGetClip( &saveClip );
    WinSetClip( (RectangleType*)&localR );
    WINDRAWCHARS( dctx, (const char*)buf, len, localR.left, localR.top );
    WinSetClip( &saveClip );
} /* palm_draw_drawTimer */

#define MINI_LINE_HT 12
#define MINI_V_PADDING 6
#define MINI_H_PADDING 8

static XP_UCHAR*
palm_draw_getMiniWText( DrawCtx* p_dctx, XWMiniTextType textHint )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx; 
    XP_UCHAR* str;
    XP_U16 strID = 0;		/* make compiler happy */

    switch( textHint ) {
    case BONUS_DOUBLE_LETTER:
        strID = STR_DOUBLE_LETTER; break;
    case BONUS_DOUBLE_WORD:
        strID = STR_DOUBLE_WORD; break;
    case BONUS_TRIPLE_LETTER:
        strID = STR_TRIPLE_LETTER; break;
    case BONUS_TRIPLE_WORD:
        strID = STR_TRIPLE_WORD; break;
    case INTRADE_MW_TEXT:
        strID = STR_TRADING_REMINDER; break;
    default:
        XP_ASSERT( XP_FALSE );
    }

    str = (*dctx->getResStrFunc)( dctx->globals, strID );

    return str;
} /* palm_draw_getMiniWText */

#define VALUE_HINT_RECT_HEIGHT 16
static void
palm_draw_measureMiniWText( DrawCtx* p_dctx, unsigned char* str, 
                            XP_U16* widthP, XP_U16* heightP )
{
    /*     PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx; */
    FntSetFont( stdFont );
    /* 8 stolen from xwords.c*/
    *widthP = FntCharsWidth( (const char*)str, 
                             XP_STRLEN((const char*)str) ) + 8;
    *heightP = VALUE_HINT_RECT_HEIGHT;
} /* palm_draw_measureMiniWText */

typedef struct MiniWinData {
    WinHandle bitsBehind;
    XP_S16 miniX;
    XP_S16 miniY;
} MiniWinData;

static void
palm_draw_drawMiniWindow( DrawCtx* p_dctx, unsigned char* text, 
                          XP_Rect* rect, void** closureP )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
    RectangleType localR = *(RectangleType*)rect;
    XP_U16 ignoreErr;
    XP_Bool hasClosure = !!closureP;
    MiniWinData* data = (MiniWinData*)(hasClosure? *closureP: NULL);

    if ( hasClosure ) {
        if ( !data ) {
            data = XP_MALLOC( dctx->mpool, sizeof(MiniWinData) );
            data->bitsBehind = WinSaveBits( &localR, &ignoreErr );
            data->miniX = localR.topLeft.x;
            data->miniY = localR.topLeft.y;
            *closureP = data;
        } else {
            XP_ASSERT( data->miniX == localR.topLeft.x );
            XP_ASSERT( data->miniY == localR.topLeft.y );
        }
    }

    WinEraseRectangle( &localR, 0 );
    localR.topLeft.x++;
    localR.topLeft.y++;
    localR.extent.x -= 3;
    localR.extent.y -= 3;
    WINDRAWRECTANGLEFRAME( dctx, popupFrame, &localR );
    WINDRAWCHARS( dctx, (const char*)text, XP_STRLEN((const char*)text), 
                  localR.topLeft.x+2, localR.topLeft.y+1 );
} /* palm_draw_drawMiniWindow */

static void
palm_draw_eraseMiniWindow( DrawCtx* p_dctx, XP_Rect* rect, XP_Bool lastTime,
                           void** closure, XP_Bool* invalUnder )
{
    MiniWinData* data = *closure;
#ifdef DEBUG
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;
#endif

    if ( !!closure && !!*closure ) {
        /* this DELETES data->bitsBehind */
        WinRestoreBits( data->bitsBehind, data->miniX, data->miniY );
        XP_FREE( dctx->mpool, data );
        *closure = NULL;
    }
} /* palm_draw_eraseMiniWindow */

static void
draw_doNothing( DrawCtx* dctx, ... )
{
} /* draw_doNothing */

DrawCtx* 
palm_drawctxt_make( MPFORMAL GraphicsAbility able, 
                    PalmAppGlobals* globals,
                    GetResStringFunc getRSF, DrawingPrefs* drawprefs )
{
    PalmDrawCtx* dctx;
    XP_U16 i;
    Err ignore;

    dctx = XP_MALLOC( mpool, sizeof(PalmDrawCtx) );
    XP_MEMSET( dctx, 0, sizeof(PalmDrawCtx) );

    MPASSIGN(dctx->mpool, mpool);

    dctx->able = able;
    dctx->globals = globals;
    dctx->getResStrFunc = getRSF;
    dctx->drawingPrefs = drawprefs;

    dctx->vtable = XP_MALLOC( mpool,
                              sizeof(*(((PalmDrawCtx*)dctx)->vtable)) );
    for ( i = 0; i < sizeof(*dctx->vtable)/4; ++i ) {
        ((void**)(dctx->vtable))[i] = draw_doNothing;
    }

    /* To keep the number of entry points this file has to 1, all
       functions but the initter called from here must go through a
       vtable call.  so....*/
    dctx->drawBitmapFunc = drawBitmapAt;

#ifdef DIRECT_PALMOS_CALLS
    /* It'd probably be best to do this with metadata, with an array
       associating the offset of the procptr within the draw ctxt with the
       trap number so I could just loop through setting addresses. */

    dctx->winDrawRectangleFrameTrap = 
        SysGetTrapAddress(sysTrapWinDrawRectangleFrame);
    dctx->winFillRectangleTrap =
        SysGetTrapAddress(sysTrapWinFillRectangle);
    dctx->winDrawCharsTrap =
        SysGetTrapAddress(sysTrapWinDrawChars);
#endif

    SET_VTABLE_ENTRY( dctx->vtable, draw_invertCell, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_drawTile, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_drawTileBack, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_drawTrayDivider, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_drawBoardArrow, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_scoreBegin, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_vertScrollBoard, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_measureRemText, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_drawRemText, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_measureScoreText, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_score_pendingScore, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_scoreFinished, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_drawTimer, palm );

    SET_VTABLE_ENTRY( dctx->vtable, draw_getMiniWText, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_measureMiniWText, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_drawMiniWindow, palm );
    SET_VTABLE_ENTRY( dctx->vtable, draw_eraseMiniWindow, palm );

    if ( able == COLOR ) {
#ifdef COLOR_SUPPORT
        SET_VTABLE_ENTRY( dctx->vtable, draw_boardBegin, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_boardFinished, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_drawCell, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_score_drawPlayer, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_trayBegin, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_trayFinished, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_clearRect, palm_clr );
        SET_VTABLE_ENTRY( dctx->vtable, draw_drawMiniWindow, palm_clr );

        SET_VTABLE_ENTRY( dctx->vtable, draw_drawBoardArrow, palm_clr );
#else
        XP_ASSERT(0);
#endif
    } else {
        SET_VTABLE_ENTRY( dctx->vtable, draw_boardBegin, palm_common );
        SET_VTABLE_ENTRY( dctx->vtable, draw_drawCell, palm_common );
        SET_VTABLE_ENTRY( dctx->vtable, draw_score_drawPlayer, palm_bnw );
        SET_VTABLE_ENTRY( dctx->vtable, draw_trayBegin, palm_bnw );
        SET_VTABLE_ENTRY( dctx->vtable, draw_trayFinished, palm_bnw );
        SET_VTABLE_ENTRY( dctx->vtable, draw_clearRect, palm_bnw );
    }

    dctx->numberWin = WinCreateOffscreenWindow( NUMRECT_WIDTH, NUMRECT_HEIGHT,
                                                screenFormat, &ignore );

    if ( able == COLOR ) {
    } else {
        short patBits[] = { 0x8844, 0x2211, 0x8844, 0x2211,
                            0xaa55, 0xaa55, 0xaa55, 0xaa55,  
                            0xCC66, 0x3399, 0xCC66, 0x3399,
                            0xCCCC, 0x3333, 0xCCCC, 0x3333 };
        XP_MEMCPY( &dctx->u.bnw.valuePatterns[0], patBits, sizeof(patBits) );
    }

    return (DrawCtx*)dctx;
} /* palm_drawctxt_make */

void
palm_drawctxt_destroy( DrawCtx* p_dctx )
{
    PalmDrawCtx* dctx = (PalmDrawCtx*)p_dctx;

    XP_ASSERT( !!dctx->numberWin );
    WinDeleteWindow( dctx->numberWin, false );

    XP_FREE( dctx->mpool, p_dctx->vtable );
    XP_FREE( dctx->mpool, dctx );
} /* palm_drawctxt_destroy */
