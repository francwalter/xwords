/* -*-mode: C; fill-column: 78; c-basic-offset: 4; compile-command: "make MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 2000-2007 by Eric House (xwords@eehouse.org).  All rights
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
#ifdef PLATFORM_NCURSES

#include <ncurses.h>
#include <signal.h>
#include <assert.h>
#include <ctype.h>

#include <netdb.h>		/* gethostbyname */
#include <errno.h>
//#include <net/netinet.h>

#include <sys/poll.h>

#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#include "linuxmain.h"
#include "cursesmain.h"
#include "cursesask.h"
#include "cursesletterask.h"
#include "linuxbt.h"
#include "model.h"
#include "draw.h"
#include "board.h"
#include "engine.h"
/* #include "compipe.h" */
#include "xwproto.h"
#include "xwstream.h"
#include "xwstate.h"
#include "server.h"
#include "memstream.h"
#include "util.h"
#include "dbgutil.h"

#define MENU_WINDOW_HEIGHT 5	/* three lines plus borders */
#define INFINITE_TIMEOUT -1

CursesAppGlobals globals;	/* must be global b/c of SIGWINCH_handler */

static void changeMenuForFocus( CursesAppGlobals* globals, 
                                BoardObjectType obj );
static XP_Bool handleLeft( CursesAppGlobals* globals );
static XP_Bool handleRight( CursesAppGlobals* globals );
static XP_Bool handleUp( CursesAppGlobals* globals );
static XP_Bool handleDown( CursesAppGlobals* globals );
static XP_Bool handleFocusKey( CursesAppGlobals* globals, XP_Key key );


#ifdef MEM_DEBUG
# define MEMPOOL params->util->mpool,
#else
# define MEMPOOL
#endif

/* extern int errno; */

static void
cursesUserError( CursesAppGlobals* globals, char* format, ... )
{
    char buf[512];
    va_list ap;

    va_start( ap, format );

    vsprintf( buf, format, ap );

    (void)cursesask( globals, buf, 1, "OK" );

    va_end(ap);
} /* cursesUserError */

static XP_S16 
curses_util_userPickTile( XW_UtilCtxt* uc, const PickInfo* XP_UNUSED(pi), 
                          XP_U16 playerNum, const XP_UCHAR4* texts, 
                          XP_U16 nTiles )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    char query[128];
    XP_S16 index;
    char* playerName = globals->cGlobals.params->gi.players[playerNum].name;

    sprintf( query, "Pick tile for %s! (Tab or type letter to select\n"
             "then hit <cr>.)", playerName );

    index = curses_askLetter( globals, query, texts, nTiles );
    return index;
} /* util_userPickTile */

static void
curses_util_userError( XW_UtilCtxt* uc, UtilErrID id )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    XP_Bool silent;
    XP_UCHAR* message = linux_getErrString( id, &silent );

    if ( silent ) {
        XP_LOGF( "silent userError: %s", message );
    } else {
        cursesUserError( globals, message );
    }
} /* curses_util_userError */

static XP_Bool
curses_util_userQuery( XW_UtilCtxt* uc, UtilQueryID id, XWStreamCtxt* stream )
{
    CursesAppGlobals* globals;
    char* question;
    char* answers[3];
    short numAnswers = 0;
    XP_Bool freeMe = XP_FALSE;
    XP_Bool result;
    XP_U16 okIndex = 1;

    switch( id ) {
    case QUERY_COMMIT_TURN:
        question = strFromStream( stream );
        freeMe = XP_TRUE;
        answers[numAnswers++] = "Cancel";
        answers[numAnswers++] = "Ok";
        break;
    case QUERY_COMMIT_TRADE:
        question = "Commit trade?";
        answers[numAnswers++] = "Cancel";
        answers[numAnswers++] = "Ok";
        break;
    case QUERY_ROBOT_MOVE:
    case QUERY_ROBOT_TRADE:
        question = strFromStream( stream );
        freeMe = XP_TRUE;
        answers[numAnswers++] = "Ok";
        okIndex = 0;
        break;
        
    default:
        XP_ASSERT( 0 );
        return 0;
    }
    globals = (CursesAppGlobals*)uc->closure;
    result = cursesask( globals, question, numAnswers, 
                        answers[0], answers[1], answers[2] ) == okIndex;

    if ( freeMe ) {
        free( question );
    }

    return result;
} /* curses_util_userQuery */

static void
curses_util_trayHiddenChange( XW_UtilCtxt* XP_UNUSED(uc), 
                              XW_TrayVisState XP_UNUSED(state),
                              XP_U16 XP_UNUSED(nVisibleRows) )
{
    /* nothing to do if we don't have a scrollbar */
} /* curses_util_trayHiddenChange */

static void
cursesShowFinalScores( CursesAppGlobals* globals )
{
    XWStreamCtxt* stream;
    XP_UCHAR* text;

    stream = mem_stream_make( MPPARM(globals->cGlobals.params->util->mpool)
                              globals->cGlobals.params->vtMgr,
                              globals, CHANNEL_NONE, NULL );
    server_writeFinalScores( globals->cGlobals.game.server, stream );

    text = strFromStream( stream );

    (void)cursesask( globals, text, 1, "Ok" );

    free( text );
} /* cursesShowFinalScores */

static void
curses_util_notifyGameOver( XW_UtilCtxt* uc )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    board_draw( globals->cGlobals.game.board );

    /* game belongs in cGlobals... */
    if ( globals->cGlobals.params->printHistory ) {    
        catGameHistory( &globals->cGlobals );
    }

    if ( globals->cGlobals.params->quitAfter ) {
        globals->timeToExit = XP_TRUE;
    } else if ( globals->cGlobals.params->undoWhenDone ) {
        server_handleUndo( globals->cGlobals.game.server );
    } else {
        cursesShowFinalScores( globals );
    }
} /* curses_util_notifyGameOver */

static XP_Bool
curses_util_hiliteCell( XW_UtilCtxt* XP_UNUSED(uc), 
                        XP_U16 XP_UNUSED(col), XP_U16 XP_UNUSED(row) )
{
    return XP_TRUE;
} /* curses_util_hiliteCell */

static XP_Bool
curses_util_engineProgressCallback( XW_UtilCtxt* XP_UNUSED(uc) )
{
    return XP_TRUE;
} /* curses_util_engineProgressCallback */

static void
curses_util_setTimer( XW_UtilCtxt* uc, XWTimerReason why, XP_U16 when,
                      XWTimerProc proc, void* closure )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;

    globals->cGlobals.timerProcs[why] = proc;
    globals->cGlobals.timerClosures[why] = closure;
    globals->nextTimer = util_getCurSeconds(uc) + when;
} /* curses_util_setTimer */

static void
curses_util_requestTime( XW_UtilCtxt* uc ) 
{
    /* I've created a pipe whose read-only end is plugged into the array of
       fds that my event loop polls so that I can write to it to simulate
       post-event on a more familiar system.  It works, so no complaints! */
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    (void)write( globals->timepipe[1], "!", 1 );
} /* curses_util_requestTime */

static void
initCurses( CursesAppGlobals* globals )
{
    WINDOW* mainWin;
    WINDOW* menuWin;
    WINDOW* boardWin;

    int x, y;

    /* ncurses man page says most apps want this sequence  */
    mainWin = initscr(); 
    cbreak(); 
    noecho();
    nonl();
    intrflush(stdscr, FALSE);
    keypad(stdscr, TRUE);

    getmaxyx(mainWin, y, x);
    globals->statusLine = y - MENU_WINDOW_HEIGHT - 1;
    menuWin = newwin( MENU_WINDOW_HEIGHT, x, y-MENU_WINDOW_HEIGHT, 0 );
    nodelay(menuWin, 1);		/* don't block on getch */
    boardWin = newwin( MAX_ROWS+1, x, 0, 0 );

/*     leaveok( boardWin, 1 ); */
/*     leaveok( menuWin, 1 ); */

    globals->menuWin = menuWin;
    globals->boardWin = boardWin;
    globals->mainWin = mainWin;
} /* initCurses */

#if 0
static void
showStatus( CursesAppGlobals* globals )
{
    char* str;

    switch ( globals->state ) {
    case XW_SERVER_WAITING_CLIENT_SIGNON:
	str = "Waiting for client[s] to connnect";
	break;
    case XW_SERVER_READY_TO_PLAY:
	str = "It's somebody's move";
	break;
    default:
	str = "unknown state";
    }

    
    standout();
    mvaddstr( globals->statusLine, 0, str );
/*     clrtoeol();     */
    standend();

    refresh();
} /* showStatus */
#endif

typedef XP_Bool (*CursesMenuHandler)(CursesAppGlobals* globals);
typedef struct MenuList {
    CursesMenuHandler handler;
    char* desc;
    char* keyDesc;
    char key;
} MenuList;
static XP_Bool
handleQuit( CursesAppGlobals* globals )
{
    globals->timeToExit = XP_TRUE;
    return XP_TRUE;
} /* handleQuit */

static void
checkAssignFocus( BoardCtxt* board )
{
    if ( OBJ_NONE == board_getFocusOwner(board) ) {
        board_focusChanged( board, OBJ_BOARD, XP_TRUE );
    }
}

static XP_Bool
handleSpace( CursesAppGlobals* globals )
{
    XP_Bool handled;
    checkAssignFocus( globals->cGlobals.game.board );

    globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                       XP_RAISEFOCUS_KEY, &handled );
    return XP_TRUE;
} /* handleSpace */

static XP_Bool
handleRet( CursesAppGlobals* globals )
{
    XP_Bool handled;
    globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                       XP_RETURN_KEY, &handled );
    return XP_TRUE;
} /* handleRet */

static XP_Bool
handleHint( CursesAppGlobals* globals )
{
    XP_Bool redo;
    globals->doDraw = board_requestHint( globals->cGlobals.game.board, 
#ifdef XWFEATURE_SEARCHLIMIT
                                         XP_FALSE,
#endif
                                         &redo );
    return XP_TRUE;
} /* handleHint */

static XP_Bool
handleCommit( CursesAppGlobals* globals )
{
    globals->doDraw = board_commitTurn( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleCommit */

static XP_Bool
handleJuggle( CursesAppGlobals* globals )
{
    globals->doDraw = board_juggleTray( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleJuggle */

static XP_Bool
handleHide( CursesAppGlobals* globals )
{
    XW_TrayVisState curState = 
        board_getTrayVisState( globals->cGlobals.game.board );

    if ( curState == TRAY_REVEALED ) {
        globals->doDraw = board_hideTray( globals->cGlobals.game.board );
    } else {
        globals->doDraw = board_showTray( globals->cGlobals.game.board );
    }

    return XP_TRUE;
} /* handleJuggle */

static XP_Bool
handleAltLeft( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTLEFT );
}

static XP_Bool
handleAltRight( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTRIGHT );
}

static XP_Bool
handleAltUp( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTUP );
}

static XP_Bool
handleAltDown( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTDOWN );
}

static XP_Bool
handleFlip( CursesAppGlobals* globals )
{
    globals->doDraw = board_flip( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleFlip */

static XP_Bool
handleToggleValues( CursesAppGlobals* globals )
{
    globals->doDraw = board_toggle_showValues( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleToggleValues */

static XP_Bool
handleBackspace( CursesAppGlobals* globals )
{
    XP_Bool handled;
    globals->doDraw = board_handleKey( globals->cGlobals.game.board,
                                       XP_CURSOR_KEY_DEL, &handled );
    return XP_TRUE;
} /* handleBackspace */

static XP_Bool
handleUndo( CursesAppGlobals* globals )
{
    globals->doDraw = server_handleUndo( globals->cGlobals.game.server );
    return XP_TRUE;
} /* handleUndo */

static XP_Bool
handleReplace( CursesAppGlobals* globals )
{
    globals->doDraw = board_replaceTiles( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleReplace */

MenuList sharedMenuList[] = {
    { handleQuit, "Quit", "Q", 'Q' },
    { handleRight, "Tab right", "<tab>", '\t' },
    { handleSpace, "Raise focus", "<spc>", ' ' },
    { handleRet, "Click/tap", "<ret>", '\r' },
    { handleHint, "Hint", "?", '?' },

#ifdef KEYBOARD_NAV
    { handleLeft, "Left", "H", 'H' },
    { handleRight, "Right", "L", 'L' },
    { handleUp, "Up", "J", 'J' },
    { handleDown, "Down", "K", 'K' },
#endif

    { handleCommit, "Commit move", "C", 'C' },
    { handleFlip, "Flip", "F", 'F' },
    { handleToggleValues, "Show values", "V", 'V' },

    { handleBackspace, "Remove from board", "<del>", 8 },
    { handleUndo, "Undo prev", "U", 'U' },
    { handleReplace, "uNdo cur", "N", 'N' },

    { NULL, NULL, NULL, '\0'}
};

#ifdef KEYBOARD_NAV
static XP_Bool
handleFocusKey( CursesAppGlobals* globals, XP_Key key )
{
    XP_Bool handled;
    XP_Bool draw;

    checkAssignFocus( globals->cGlobals.game.board );

    draw = board_handleKey( globals->cGlobals.game.board, key, &handled );
    if ( !handled ) {
        BoardObjectType nxt;
        BoardObjectType order[] = { OBJ_BOARD, OBJ_SCORE, OBJ_TRAY };
        draw = linShiftFocus( &globals->cGlobals, key, order, &nxt ) || draw;
        if ( nxt != OBJ_NONE ) {
            changeMenuForFocus( globals, nxt );
        }
    }

    globals->doDraw = draw || globals->doDraw;
    return XP_TRUE;
}

static XP_Bool
handleLeft( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_LEFT );
} /* handleLeft */

static XP_Bool
handleRight( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_RIGHT );
} /* handleRight */

static XP_Bool
handleUp( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_UP );
} /* handleUp */

static XP_Bool
handleDown( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_DOWN );
} /* handleDown */
#endif

MenuList boardMenuList[] = {
    { handleAltLeft,  "Force left", "{", '{' },
    { handleAltRight, "Force right", "}", '}' },
    { handleAltUp,    "Force up", "_", '_' },
    { handleAltDown,  "Force down", "+", '+' },
    { NULL, NULL, NULL, '\0'}
};

MenuList scoreMenuList[] = {
#ifdef KEYBOARD_NAV
#endif
    { NULL, NULL, NULL, '\0'}
};

MenuList trayMenuList[] = {
    { handleJuggle, "Juggle", "G", 'G' },
    { handleHide, "[un]hIde", "I", 'I' },
    { handleAltLeft, "Divider left", "{", '{' },
    { handleAltRight, "Divider right", "}", '}' },

    { NULL, NULL, NULL, '\0'}
};

static void
figureMaxes( MenuList* mList, short maxLines, short* maxKeyP, short* maxCmdP )
{
    short i;

    *maxKeyP = *maxCmdP = 0;

    for ( i = 0; i < maxLines && mList->handler != NULL; ++i ) {
        short keyLen = strlen(mList->keyDesc);
        short cmdLen = strlen(mList->desc);
        *maxKeyP = XP_MAX( *maxKeyP, keyLen );
        *maxCmdP= XP_MAX( *maxCmdP, cmdLen );
        ++mList;
    }
} /* figureMaxes */

static void
drawMenuFromList( CursesAppGlobals* globals, MenuList* menuList )
{
    short i;
    short maxKey = 0, maxCmd = 0;
    short line = 0, col;
    short nLines;
    int winMaxY, winMaxX;
    WINDOW* win = globals->menuWin;
    XP_Bool done = XP_FALSE;

    getmaxyx( win, winMaxY, winMaxX );

    nLines = globals->nLinesMenu;
    if ( nLines == 0 ) {
        nLines = 1;
    }

    for ( ; !done; ++nLines ) {
        MenuList* entry = sharedMenuList;
        XP_Bool isShared = XP_TRUE;

        wclear( win );

        maxKey = maxCmd = 0;
        for ( line = 0, col = -2, i = 0; ; ++entry, ++line ) {
            char* key;

            if ( entry->handler == NULL ) {
                if ( !isShared ) {
                    done = XP_TRUE;
                    break;
                } else if ( menuList->handler == NULL )  {
                    done = XP_TRUE;
                    break;
                } else {
                    isShared = XP_FALSE;
                    entry = menuList;
                    XP_ASSERT( !!entry->handler );
                }
            }

            XP_ASSERT( nLines > 0 );
            if ( line % nLines == 0 ) {
                line = 0;
                col += maxKey + maxCmd + 2;
                figureMaxes( entry, nLines, &maxKey, &maxCmd );
                if ( (col + maxCmd + strlen(entry->keyDesc)) >= winMaxX ) {
                    break;
                }
            }

            key = entry->keyDesc;

            wstandout( win );
            mvwaddstr( win, line, col+maxKey-strlen(key), key );
            wstandend( win );
            mvwaddstr( win, line, col+maxKey+1, entry->desc );
        }
    }

    globals->nLinesMenu = nLines - 1;

    wrefresh( win );
} /* drawMenuFromList */

static void 
SIGWINCH_handler( int signal )
{
    int x, y;

    assert( signal == SIGWINCH );

    endwin(); 

/*     (*globals.drawMenu)( &globals );  */

    getmaxyx( stdscr, y, x );
    wresize( globals.mainWin, y-MENU_WINDOW_HEIGHT, x );

    board_draw( globals.cGlobals.game.board );
} /* SIGWINCH_handler */

static void
cursesListenOnSocket( CursesAppGlobals* globals, int newSock )
{
    XP_ASSERT( globals->fdCount+1 < FD_MAX );

    XP_WARNF( "setting fd[%d] to %d", globals->fdCount, newSock );
    globals->fdArray[globals->fdCount].fd = newSock;
    globals->fdArray[globals->fdCount].events = POLLIN;

    ++globals->fdCount;
    XP_LOGF( "listenOnSocket: there are now %d sources to poll",
             globals->fdCount );
} /* cursesListenOnSocket */

static void
curses_stop_listening( CursesAppGlobals* globals, int sock )
{
    int count = globals->fdCount;
    int i;
    bool found = false;

    for ( i = 0; i < count; ++i ) {
        if ( globals->fdArray[i].fd == sock ) {
            found = true;
        } else if ( found ) {
            globals->fdArray[i-1].fd = globals->fdArray[i].fd;
        }
    }

    assert( found );
    --globals->fdCount;
} /* curses_stop_listening */

static void
curses_socket_changed( void* closure, int oldSock, int newSock,
                       void** XP_UNUSED(storage) )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)closure;
    if ( oldSock != -1 ) {
        curses_stop_listening( globals, oldSock );
    }
    if ( newSock != -1 ) {
        cursesListenOnSocket( globals, newSock );
    }
} /* curses_socket_changed */

static void
curses_socket_acceptor( int listener, Acceptor func, CommonGlobals* cGlobals )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)cGlobals;
    XP_ASSERT( !cGlobals->acceptor || (func == cGlobals->acceptor) );
    cGlobals->acceptor = func;
    globals->csInfo.server.serverSocket = listener;
    cursesListenOnSocket( globals, listener );
}

static int
figureTimeout( CursesAppGlobals* XP_UNUSED_RELAY(globals) )
{
    int result = INFINITE_TIMEOUT;
#ifdef XWFEATURE_RELAY
    if ( globals->cGlobals.timerProcs[TIMER_HEARTBEAT] != 0 ) {
        XP_U32 now = util_getCurSeconds( globals->cGlobals.params->util );
        XP_U32 then = globals->nextTimer;
        if ( now >= then ) {
            result = 0;
        } else {
            result = (then - now) * 1000;
        }
    }
#endif
    return result;
} /* figureTimeout */

/* 
 * Ok, so this doesn't block yet.... 
 */
static XP_Bool
blocking_gotEvent( CursesAppGlobals* globals, int* ch )
{
    XP_Bool result = XP_FALSE;
    int numEvents;
    short fdIndex;
    XP_Bool redraw = XP_FALSE;

    int timeout = figureTimeout( globals );
    numEvents = poll( globals->fdArray, globals->fdCount, timeout );

    if ( timeout != INFINITE_TIMEOUT && numEvents == 0 ) {
#ifdef XWFEATURE_RELAY
        if ( !globals->cGlobals.params->noHeartbeat ) {
            linuxFireTimer( &globals->cGlobals, TIMER_HEARTBEAT );
        }
#endif
    } else if ( numEvents > 0 ) {
	
        /* stdin first */
        if ( (globals->fdArray[FD_STDIN].revents & POLLIN) != 0 ) {
            int evtCh = fgetc(stdin);
            XP_LOGF( "%s: got key: %x", __FUNCTION__, evtCh );
            *ch = evtCh;
            result = XP_TRUE;
            --numEvents;
        }
        if ( (globals->fdArray[FD_STDIN].revents & ~POLLIN ) ) {
            XP_LOGF( "some other events set on stdin" );
        }

        if ( (globals->fdArray[FD_TIMEEVT].revents & POLLIN) != 0 ) {
            char ch;
            /* 	    XP_DEBUGF( "curses got a USER EVENT\n" ); */
            (void)read(globals->fdArray[FD_TIMEEVT].fd, &ch, 1 );
        }

        fdIndex = FD_FIRSTSOCKET;

        if ( numEvents > 0 && 
             (globals->fdArray[fdIndex].revents & POLLIN) != 0 ) {
            int nBytes;
            unsigned char buf[256];
            struct sockaddr_in addr_sock;

            --numEvents;

            if ( globals->fdArray[fdIndex].fd 
                 == globals->csInfo.server.serverSocket ) {
                /* It's the listening socket: call platform's accept()
                   wrapper */
                (*globals->cGlobals.acceptor)( globals->fdArray[fdIndex].fd, 
                                               globals );
            } else {
                /* It's a normal data socket */
                if ( 0 ) {
#ifdef XWFEATURE_RELAY
                } else if ( globals->cGlobals.params->conType
                            == COMMS_CONN_RELAY ) {
                    nBytes = linux_relay_receive( &globals->cGlobals, buf, 
                                                  sizeof(buf) );
#endif
#ifdef XWFEATURE_BLUETOOTH
                } else if ( globals->cGlobals.params->conType
                            == COMMS_CONN_BT ) {
                    nBytes = linux_bt_receive( globals->fdArray[fdIndex].fd, 
                                               buf, sizeof(buf) );
#endif
                } else {
                    XP_ASSERT( 0 );
                }

                if ( nBytes != -1 ) {
                    XWStreamCtxt* inboundS;
                    redraw = XP_FALSE;

                    XP_STATUSF( "linuxReceive=>%d", nBytes );
                    inboundS = stream_from_msgbuf( &globals->cGlobals, 
                                                   buf, nBytes );
                    if ( !!inboundS ) {
                        CommsAddrRec addrRec;
                
                        XP_MEMSET( &addrRec, 0, sizeof(addrRec) );
                        addrRec.conType = COMMS_CONN_RELAY;
            
                        addrRec.u.ip_relay.ipAddr = 
                            ntohl(addr_sock.sin_addr.s_addr);
                        XP_LOGF( "captured incoming ip address: 0x%lx",
                                 addrRec.u.ip_relay.ipAddr );

                        if ( comms_checkIncomingStream(
                                globals->cGlobals.game.comms,
                                inboundS, &addrRec ) ) {
                            XP_LOGF( "comms read port: %d", 
                                     addrRec.u.ip_relay.port );
                            redraw = server_receiveMessage( 
                                  globals->cGlobals.game.server, inboundS );
                        }
                        stream_destroy( inboundS );
                    }
                
                    /* if there's something to draw resulting from the
                       message, we need to give the main loop time to reflect
                       that on the screen before giving the server another
                       shot.  So just call the idle proc. */
                    if ( redraw ) {
                        curses_util_requestTime(globals->cGlobals.params->util);
                    }
                }

            }
            ++fdIndex;
        }

        redraw = server_do( globals->cGlobals.game.server ) || redraw;
        if ( redraw ) {
            /* messages change a lot */
            board_invalAll( globals->cGlobals.game.board );
            board_draw( globals->cGlobals.game.board );
        }
    }
    return result;
} /* blocking_gotEvent */

static void
changeMenuForFocus( CursesAppGlobals* globals, BoardObjectType focussed )
{
#ifdef KEYBOARD_NAV
    if ( focussed == OBJ_TRAY ) {
        globals->menuList = trayMenuList;
        drawMenuFromList( globals, trayMenuList );
    } else if ( focussed == OBJ_BOARD ) {
        globals->menuList = boardMenuList;
        drawMenuFromList( globals, boardMenuList );
    } else if ( focussed == OBJ_SCORE ) {
        globals->menuList = scoreMenuList;
        drawMenuFromList( globals, scoreMenuList );
    } else {
        XP_ASSERT(0);
    }
#endif
} /* changeMenuForFocus */

#if 0
static void
initClientSocket( CursesAppGlobals* globals, char* serverName )
{
    struct hostent* hostinfo;
    hostinfo = gethostbyname( serverName );
    if ( !hostinfo ) {
	userError( globals, "unable to get host info for %s\n", serverName );
    } else {
	char* hostName = inet_ntoa( *(struct in_addr*)hostinfo->h_addr );
	XP_LOGF( "gethostbyname returned %s", hostName );
	globals->csInfo.client.serverAddr = inet_addr(hostName);
	XP_LOGF( "inet_addr returned %lu", 
		 globals->csInfo.client.serverAddr );
    }
} /* initClientSocket */
#endif

static VTableMgr*
curses_util_getVTManager(XW_UtilCtxt* uc)
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    return globals->cGlobals.params->vtMgr;
} /* linux_util_getVTManager */

static XP_Bool
curses_util_askPassword( XW_UtilCtxt* XP_UNUSED(uc), 
                         const XP_UCHAR* XP_UNUSED(name), 
                         XP_UCHAR* XP_UNUSED(buf), XP_U16* XP_UNUSED(len) )
{
    XP_WARNF( "curses_util_askPassword not implemented" );
    return XP_FALSE;
} /* curses_util_askPassword */

static void
curses_util_yOffsetChange( XW_UtilCtxt* XP_UNUSED(uc), XP_U16 oldOffset, 
                           XP_U16 newOffset )
{
    if ( oldOffset != newOffset ) {
	XP_WARNF( "curses_util_yOffsetChange(%d,%d) not implemented",
		  oldOffset, newOffset );    
    }
} /* curses_util_yOffsetChange */

static XP_Bool
curses_util_warnIllegalWord( XW_UtilCtxt* XP_UNUSED(uc), 
                             BadWordInfo* XP_UNUSED(bwi), 
                             XP_U16 XP_UNUSED(player),
                             XP_Bool XP_UNUSED(turnLost) )
{
    XP_WARNF( "curses_util_warnIllegalWord not implemented" );
    return XP_FALSE;
} /* curses_util_warnIllegalWord */

static void
cursesSendOnClose( XWStreamCtxt* stream, void* closure )
{
    XP_S16 result;
    CursesAppGlobals* globals = (CursesAppGlobals*)closure;

    XP_LOGF( "cursesSendOnClose called" );
    result = comms_send( globals->cGlobals.game.comms, stream );
} /* cursesSendOnClose */

static XWStreamCtxt* 
curses_util_makeStreamFromAddr(XW_UtilCtxt* uc, XP_PlayerAddr channelNo )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    LaunchParams* params = globals->cGlobals.params;

    XWStreamCtxt* stream = mem_stream_make( MPPARM(uc->mpool) 
                                            params->vtMgr,
                                            uc->closure, channelNo, 
                                            cursesSendOnClose );
    return stream;
} /* curses_util_makeStreamFromAddr */

static void
setupCursesUtilCallbacks( CursesAppGlobals* globals, XW_UtilCtxt* util )
{
    util->vtable->m_util_userError = curses_util_userError;

    util->vtable->m_util_getVTManager = curses_util_getVTManager;
    util->vtable->m_util_askPassword = curses_util_askPassword;
    util->vtable->m_util_yOffsetChange = curses_util_yOffsetChange;
    util->vtable->m_util_warnIllegalWord = curses_util_warnIllegalWord;
    util->vtable->m_util_makeStreamFromAddr = curses_util_makeStreamFromAddr;

    util->vtable->m_util_userQuery = curses_util_userQuery;
    util->vtable->m_util_userPickTile = curses_util_userPickTile;
    util->vtable->m_util_trayHiddenChange = curses_util_trayHiddenChange;
    util->vtable->m_util_notifyGameOver = curses_util_notifyGameOver;
    util->vtable->m_util_hiliteCell = curses_util_hiliteCell;
    util->vtable->m_util_engineProgressCallback = 
        curses_util_engineProgressCallback;

    util->vtable->m_util_setTimer = curses_util_setTimer;
    util->vtable->m_util_requestTime = curses_util_requestTime;

    util->closure = globals;
} /* setupCursesUtilCallbacks */

static void
sendOnClose( XWStreamCtxt* stream, void* closure )
{
    CursesAppGlobals* globals = closure;
    XP_LOGF( "curses sendOnClose called" );
    XP_ASSERT( !!globals->cGlobals.game.comms );
    comms_send( globals->cGlobals.game.comms, stream );
} /* sendOnClose */

static XP_Bool
handleKeyEvent( CursesAppGlobals* globals, MenuList* list, char ch )
{
    while ( list->handler != NULL ) {
        if ( list->key == ch ) {
            if ( (*list->handler)(globals) ) {
                return XP_TRUE;
            }
        }
        ++list;
    }
    return XP_FALSE;
} /* handleKeyEvent */

static XP_Bool
passKeyToBoard( CursesAppGlobals* globals, char ch )
{
    XP_Bool handled = ch >= 'a' && ch <= 'z';
    if ( handled ) {
        ch += 'A' - 'a';
        globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                           ch, NULL );
    }
    return handled;
} /* passKeyToBoard */

void
cursesmain( XP_Bool isServer, LaunchParams* params )
{
    int piperesult;
    DictionaryCtxt* dict;
    XP_U16 gameID;
    XP_U16 colWidth, scoreLeft;

    memset( &globals, 0, sizeof(globals) );

    globals.amServer = isServer;
    globals.cGlobals.params = params;
#ifdef XWFEATURE_RELAY
    globals.cGlobals.socket = -1;
#endif

    globals.cGlobals.socketChanged = curses_socket_changed;
    globals.cGlobals.socketChangedClosure = &globals;
    globals.cGlobals.addAcceptor = curses_socket_acceptor;

    globals.cp.showBoardArrow = XP_TRUE;
    globals.cp.showRobotScores = params->showRobotScores;

    dict = params->dict;

    setupCursesUtilCallbacks( &globals, params->util );

#ifdef XWFEATURE_RELAY
    if ( params->conType == COMMS_CONN_RELAY ) {
        globals.cGlobals.defaultServerName
            = params->connInfo.relay.relayName;
    }
#endif
    cursesListenOnSocket( &globals, 0 ); /* stdin */

    piperesult = pipe( globals.timepipe );
    XP_ASSERT( piperesult == 0 );

    /* reader pipe */
    cursesListenOnSocket( &globals, globals.timepipe[0] );
    signal( SIGWINCH, SIGWINCH_handler );
    initCurses( &globals );

    globals.draw = (struct CursesDrawCtx*)cursesDrawCtxtMake( globals.boardWin );
    
    gameID = (XP_U16)util_getCurSeconds( globals.cGlobals.params->util );
    game_makeNewGame( MEMPOOL &globals.cGlobals.game, &params->gi,
                      params->util, (DrawCtx*)globals.draw,
                      gameID, &globals.cp, linux_send, 
                      IF_CH(linux_reset) &globals );

    if ( globals.cGlobals.game.comms ) {
        CommsAddrRec addr;

        if ( 0 ) {
#ifdef XWFEATURE_RELAY
        } else if ( params->conType == COMMS_CONN_RELAY ) {
            addr.conType = COMMS_CONN_RELAY;
            addr.u.ip_relay.ipAddr = 0;       /* ??? */
            addr.u.ip_relay.port = params->connInfo.relay.defaultSendPort;
            XP_STRNCPY( addr.u.ip_relay.hostName, params->connInfo.relay.relayName,
                        sizeof(addr.u.ip_relay.hostName) - 1 );
            XP_STRNCPY( addr.u.ip_relay.cookie, params->connInfo.relay.cookie,
                        sizeof(addr.u.ip_relay.cookie) - 1 );
#endif
#ifdef XWFEATURE_BLUETOOTH
        } else if ( params->conType == COMMS_CONN_BT ) {
            addr.conType = COMMS_CONN_BT;
            XP_ASSERT( sizeof(addr.u.bt.btAddr) 
                       >= sizeof(params->connInfo.bt.hostAddr));
            XP_MEMCPY( &addr.u.bt.btAddr, &params->connInfo.bt.hostAddr,
                       sizeof(params->connInfo.bt.hostAddr) );
#endif
        }
        comms_setAddr( globals.cGlobals.game.comms, &addr );
    }

	model_setDictionary( globals.cGlobals.game.model, params->dict );

    board_setPos( globals.cGlobals.game.board, 1, 1, XP_FALSE );
    colWidth = 2;
    board_setScale( globals.cGlobals.game.board, colWidth, 1 );
    scoreLeft = (colWidth * MAX_COLS) + 3;
    board_setScoreboardLoc( globals.cGlobals.game.board, 
                            scoreLeft, 1,
                            45, 5, /*4 players + rem*/ XP_FALSE );

    board_setTrayLoc( globals.cGlobals.game.board,
                      scoreLeft, 8, (3*MAX_TRAY_TILES)+1, 
                      4, 1 );
    /* no divider -- yet */
    /*     board_setTrayVisible( globals.board, XP_TRUE, XP_FALSE ); */

    board_invalAll( globals.cGlobals.game.board );

    /* send any events that need to get off before the event loop begins */
    if ( !isServer ) {
        if ( 1 /* stream_open( params->info.clientInfo.stream )  */) {
            server_initClientConnection( globals.cGlobals.game.server, 
                                         mem_stream_make( MEMPOOL
                                                          params->vtMgr,
                                                          &globals,
                                                          (XP_PlayerAddr)0,
                                                          sendOnClose ) );
        } else {
            cursesUserError( &globals, "Unable to open connection to server");
            exit( 0 );
        }
    }

    server_do( globals.cGlobals.game.server );

    globals.menuList = boardMenuList;
    drawMenuFromList( &globals, boardMenuList );
    board_draw( globals.cGlobals.game.board );

    while ( !globals.timeToExit ) {
        int ch;
        if ( blocking_gotEvent( &globals, &ch )
             && (handleKeyEvent( &globals, globals.menuList, ch )
                 || handleKeyEvent( &globals, sharedMenuList, ch )
                 || passKeyToBoard( &globals, ch ) ) ) {
            if ( globals.doDraw ) {
                board_draw( globals.cGlobals.game.board );
                globals.doDraw = XP_FALSE;
            }
        }
    }
    
    endwin();
} /* cursesmain */
#endif /* PLATFORM_NCURSES */
