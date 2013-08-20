/* -*- compile-command: "make -j3"; -*- */

/* 
 * Copyright 2005 - 2012 by Eric House (xwords@eehouse.org).  All rights
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

//////////////////////////////////////////////////////////////////////////////
//
// This program is a *very rough* cut at a message forwarding server that's
// meant to sit somewhere that cellphones can reach and forward packets across
// connections so that they can communicate.  It exists to work around the
// fact that many cellular carriers prevent direct incoming connections from
// reaching devices on their networks.  It's meant for Crosswords, but might
// be useful for other things.  It also needs a lot of work, and I hacked it
// up before making an exhaustive search for other alternatives.
//
//////////////////////////////////////////////////////////////////////////////

#include <stdio.h>
#include <unistd.h>
#include <netdb.h>		/* gethostbyname */
#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <assert.h>
#include <sys/select.h>
#include <stdarg.h>
#include <syslog.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <glib.h>

#if defined(__FreeBSD__)
# if (OSVERSION > 500000)
#  include "getopt.h"
# else
#  include "unistd.h"
# endif
#else
# include <getopt.h>
#endif

#include <sys/time.h>

#include "xwrelay.h"
#include "crefmgr.h"
#include "ctrl.h"
#include "http.h"
#include "mlock.h"
#include "tpool.h"
#include "configs.h"
#include "timermgr.h"
#include "permid.h"
#include "lstnrmgr.h"
#include "dbmgr.h"
#include "addrinfo.h"
#include "devmgr.h"
#include "udpqueue.h"
#include "udpack.h"
#include "udpager.h"

typedef struct _UDPHeader {
    uint32_t packetID;
    unsigned char proto;
    XWRelayReg cmd;
} UDPHeader;

static int s_nSpawns = 0;
static int g_maxsocks = -1;
static int g_udpsock = -1;

bool
willLog( XW_LogLevel level ) 
{
    RelayConfigs* rc = RelayConfigs::GetConfigs();
    int configLevel = level;

    if ( NULL != rc ) {

        if ( ! rc->GetValueFor( "LOGLEVEL", &configLevel ) ) {
            configLevel = level - 1; /* drop it */
        }
    }

    return level <= configLevel;
}

void
logf( XW_LogLevel level, const char* format, ... )
{
    if ( willLog( level ) ) {
#ifdef USE_SYSLOG
        char buf[256];
        va_list ap;
        va_start( ap, format );
        vsnprintf( buf, sizeof(buf), format, ap );
        syslog( LOG_LOCAL0 | LOG_INFO, buf );
        va_end(ap);
#else
        FILE* where = NULL;
        struct tm* timp;
        struct timeval tv;
        bool useFile;
        char logFile[256];

        RelayConfigs* rc = RelayConfigs::GetConfigs();
        useFile = rc->GetValueFor( "LOGFILE_PATH", logFile, sizeof(logFile) );

        if ( useFile ) {
            where = fopen( logFile, "a" );
        } else  {
            where = stderr;
        }

        if ( !!where ) {
            static int tm_yday = 0;
            gettimeofday( &tv, NULL );
            struct tm result;
            timp = localtime_r( &tv.tv_sec, &result );

            /* log the date once/day.  This isn't threadsafe so may be
               repeated but that's harmless. */
            if ( tm_yday != timp->tm_yday ) {
                tm_yday = timp->tm_yday;
                fprintf( where, "It's a new day: %.2d/%.2d/%d\n", timp->tm_mday,
                         1 + timp->tm_mon, /* 0-based */
                         1900 + timp->tm_year ); /* 1900-based */
            }

            pthread_t me = pthread_self();

            fprintf( where, "<%p>%.2d:%.2d:%.2d: ", (void*)me, timp->tm_hour, 
                     timp->tm_min, timp->tm_sec );

            va_list ap;
            va_start( ap, format );
            vfprintf( where, format, ap );
            va_end(ap);
            fprintf( where, "\n" );

            if ( useFile && !!where ) {
                fclose( where );
            }
        }
#endif
    }
} /* logf */

const char*
cmdToStr( XWRELAY_Cmd cmd )
{
# define CASESTR(s)  case s: return #s
    switch( cmd ) {
        CASESTR(XWRELAY_NONE);
        CASESTR(XWRELAY_GAME_CONNECT);
        CASESTR(XWRELAY_GAME_RECONNECT);
        CASESTR(XWRELAY_ACK);
        CASESTR(XWRELAY_GAME_DISCONNECT);
        CASESTR(XWRELAY_CONNECT_RESP);
        CASESTR(XWRELAY_RECONNECT_RESP);
        CASESTR(XWRELAY_ALLHERE);
        CASESTR(XWRELAY_DISCONNECT_YOU);
        CASESTR(XWRELAY_DISCONNECT_OTHER);
        CASESTR(XWRELAY_CONNECTDENIED);
#ifdef RELAY_HEARTBEAT
        CASESTR(XWRELAY_HEARTBEAT);
#endif
        CASESTR(XWRELAY_MSG_FROMRELAY);
        CASESTR(XWRELAY_MSG_TORELAY);
    default:
        logf( XW_LOGERROR, "%s: unknown command %d", __func__, cmd );
        return "<unknown>";
    }
}

static bool
parseRelayID( const unsigned char** const inp, const unsigned char* const end,
              char* buf, int buflen, HostID* hid )
{
    const char* hidp = strchr( (char*)*inp, '/' );

    bool ok = NULL != hidp;
    int connNameLen;

    if ( ok ) {
        connNameLen = hidp - (char*)*inp;
        ok = connNameLen < buflen;
    }
    if ( ok ) {
        strncpy( buf, (char*)*inp, connNameLen );
        buf[connNameLen] = '\0';

        ++hidp; 	        // skip '/'
        *hid = *hidp - '0';	// assume it's one byte, as should be in range '0'--'4'
        // logf( XW_LOGERROR, "%s: read hid of %d from %s", __func__, *hid, hidp );

        if ( *hid >= 0 && *hid <= 4 ) {
            const char* endptr = hidp + 1;
            if ( '\n' == *endptr ) {
                ++endptr;
            }
            *inp = (unsigned char*)endptr;
        } else {
            ok = false;

            int len = end - *inp;
            char buf[len+1];
            memcpy( buf, *inp, len);
            buf[len] = '\0';
            logf( XW_LOGERROR, "%s: got bad hid %d from str \"%s\"", __func__,
                  *hid, buf );
        }
    }
    if ( !ok ) {
        logf( XW_LOGERROR, "%s failed", __func__ );
    }
    return ok;
}

static bool
getNetLong( const unsigned char** bufpp, const unsigned char* end, 
            uint32_t* out )
{
    uint32_t tmp;
    bool ok = *bufpp + sizeof(tmp) <= end;
    if ( ok ) {
        memcpy( &tmp, *bufpp, sizeof(tmp) );
        *bufpp += sizeof(tmp);
        *out = ntohl( tmp );
    }
    return ok;
} /* getNetLong */

static bool
getNetShort( const unsigned char** bufpp, const unsigned char* end, 
             unsigned short* out )
{
    unsigned short tmp;
    bool ok = *bufpp + sizeof(tmp) <= end;
    if ( ok ) {
        memcpy( &tmp, *bufpp, sizeof(tmp) );
        *bufpp += sizeof(tmp);
        *out = ntohs( tmp );
    }
    return ok;
} /* getNetShort */

static bool
getNetByte( const unsigned char** bufpp, const unsigned char* end, 
            unsigned char* out )
{
    bool ok = *bufpp < end;
    if ( ok ) {
        *out = **bufpp;
        ++*bufpp;
    }
    return ok;
} /* getNetByte */

static bool
getNetString( const unsigned char** bufpp, const unsigned char* end, string& out )
{
    char* str = (char*)*bufpp;
    size_t len = 1 + strlen( str );
    bool success = str + len <= (char*)end;
    if ( success ) {
        out = str;
        *bufpp += len;
    }
    // logf( XW_LOGERROR, "%s => %d", __func__, out.c_str() );
    return success;
}

static bool
getShortInitString( const unsigned char** bufpp, const unsigned char* end, 
                    string& out )
{
    bool success;
    uint16_t len;
    success = getNetShort( bufpp, end, &len );
    if ( success ) {
        success = *bufpp + len <= end;
        if ( success ) {
            out.append( (const char*)*bufpp, len );
            *bufpp += len;
        }
    }
    return success;
}

static bool
getRelayDevID( const unsigned char** bufpp, const unsigned char* end, 
               DevID& devID )
{
    return getShortInitString( bufpp, end, devID.m_devIDString );
}

static bool
getHeader( const unsigned char** bufpp, const unsigned char* end,
           UDPHeader* header )
{
    unsigned char byt;
    bool success = getNetByte( bufpp, end, &header->proto )
        && getNetLong( bufpp, end, &header->packetID )
        && getNetByte( bufpp, end, &byt )
        && XWPDEV_PROTO_VERSION == header->proto;
    if ( success ) {
        header->cmd = (XWRelayReg)byt;
    } else {
        logf( XW_LOGERROR, "%s: bad packet header", __func__ );
    }
    return success;
}

static void
getDevID( const unsigned char** bufpp, const unsigned char* end,
          unsigned short flags, DevID* devID ) 
{
    if ( XWRELAY_PROTO_VERSION_CLIENTID <= flags ) {
        unsigned char devIDType = 0;
        if ( getNetByte( bufpp, end, &devIDType ) && 0 != devIDType ) {
            if ( getNetString( bufpp, end, devID->m_devIDString )
                 && 0 < devID->m_devIDString.length() ) {
                devID->m_devIDType = (DevIDType)devIDType;
            }
        }
    }
}

#ifdef RELAY_HEARTBEAT
static bool
processHeartbeat( unsigned char* buf, int bufLen, int socket )
{
    unsigned char* end = buf + bufLen;
    CookieID cookieID; 
    HostID hostID;
    bool success = false;

    if ( getNetShort( &buf, end, &cookieID ) /* may be wrong if ALLCONN hasn't been sent */
         && getNetByte( &buf, end, &hostID ) ) {
        logf( XW_LOGINFO, "processHeartbeat: cookieID 0x%lx, hostID 0x%x", 
              cookieID, hostID );

        {
            SafeCref scr( socket );
            success = scr.HandleHeartbeat( hostID, socket );
        }
    }
    return success;
} /* processHeartbeat */
#endif

static bool
readStr( const unsigned char** bufp, const unsigned char* end, 
         char* outBuf, int bufLen )
{
    unsigned char clen = **bufp;
    ++*bufp;
    if ( ((*bufp + clen) <= end) && (clen < bufLen) ) {
        memcpy( outBuf, *bufp, clen );
        outBuf[clen] = '\0';
        *bufp += clen;
        return true;
    }
    return false;
} /* readStr */

static XWREASON
flagsOK( const unsigned char** bufp, unsigned char const* end, 
         unsigned short* clientVersion, unsigned short* flagsp )
{
    XWREASON err = XWRELAY_ERROR_OLDFLAGS;
    unsigned char flags;
    if ( getNetByte( bufp, end, &flags ) ) {
        *flagsp = flags;
        switch ( flags ) {
        case XWRELAY_PROTO_VERSION_CLIENTID:
        case XWRELAY_PROTO_VERSION_CLIENTVERS:
            if ( getNetShort( bufp, end, clientVersion ) ) {
                err = XWRELAY_ERROR_NONE;
            }
            break;
        case XWRELAY_PROTO_VERSION_NOCLIENT:
            *clientVersion = 0;
            err = XWRELAY_ERROR_NONE;
            break;
        default:
            break;
        }
    }
    return err;
} /* flagsOK */

void
denyConnection( const AddrInfo* addr, XWREASON err )
{
    unsigned char buf[2];

    buf[0] = XWRELAY_CONNECTDENIED;
    buf[1] = err;

    send_with_length_unsafe( addr, buf, sizeof(buf), NULL );
}

static ssize_t
send_via_udp_impl( int socket, const struct sockaddr* dest_addr, 
                   uint32_t* packetIDP, XWRelayReg cmd, va_list* app )
{
    uint32_t packetNum = UDPAckTrack::nextPacketID( cmd );
    struct iovec vec[10];
    unsigned int iocount = 0;

    unsigned char header[1 + sizeof(packetNum) + 1];
    header[0] = XWPDEV_PROTO_VERSION;
    if ( NULL != packetIDP ) {
        *packetIDP = packetNum;
    }
    packetNum = htonl( packetNum );
    memcpy( &header[1], &packetNum, sizeof(packetNum) );
    header[5] = cmd;
    vec[iocount].iov_base = header;
    vec[iocount].iov_len = sizeof(header);
    ++iocount;

    for ( ; ; ) {
        unsigned char* ptr = va_arg(*app, unsigned char*);
        if ( !ptr ) {
            break;
        }
        assert( iocount < VSIZE(vec) );
        vec[iocount].iov_base = ptr;
        vec[iocount].iov_len = va_arg(*app, int);
        ++iocount;
    }

#ifdef LOG_UDP_PACKETS
    gsize size = 0;
    gint state = 0;
    gint save = 0;
    gchar out[1024];
    for ( unsigned int ii = 0; ii < iocount; ++ii ) {
        size += g_base64_encode_step( (const guchar*)vec[ii].iov_base, 
                                      vec[ii].iov_len,
                                      FALSE, &out[size], &state, &save );
    }
    size += g_base64_encode_close( FALSE, &out[size], &state, &save );
    assert( size < sizeof(out) );
    out[size] = '\0';
#endif

    struct msghdr mhdr = {0};
    mhdr.msg_iov = vec;
    mhdr.msg_iovlen = iocount;
    mhdr.msg_name = (void*)dest_addr;
    mhdr.msg_namelen = sizeof(*dest_addr);

    ssize_t nSent = sendmsg( socket, &mhdr, 0 /* flags */);
    if ( 0 > nSent ) {
        logf( XW_LOGERROR, "%s: sendmsg->errno %d (%s)", __func__, errno, 
              strerror(errno) );
    }

#ifdef LOG_UDP_PACKETS
    gchar* b64 = g_base64_encode( (unsigned char*)dest_addr, 
                                  sizeof(*dest_addr) );
    logf( XW_LOGINFO, "%s()=>%d; addr='%s'; msg='%s'", __func__, nSent, 
          b64, out );
    g_free( b64 );
#else
    logf( XW_LOGINFO, "%s()=>%d", __func__, nSent );
#endif

    return nSent;
} // send_via_udp_impl

static ssize_t
send_via_udp( const AddrInfo* addr, uint32_t* packetIDP, XWRelayReg cmd, ... )
{
    ssize_t result = 0;
    if ( addr->isCurrent() ) {
        int socket = addr->socket();
        assert( g_udpsock == socket || socket == -1 );
        if ( -1 == socket ) {
            socket = g_udpsock;
        }

        va_list ap;
        va_start( ap, cmd );
        result = send_via_udp_impl( socket, addr->sockaddr(), packetIDP, 
                                    cmd, &ap );
        va_end( ap );
    } else {
        logf( XW_LOGINFO, "%s: not sending to out-of-date packet", __func__ );
    }
    return result;
}

static ssize_t
send_via_udp( int socket, const struct sockaddr* dest_addr, 
              uint32_t* packetIDP, XWRelayReg cmd, ... )
{
    va_list ap;
    va_start( ap, cmd );
    ssize_t result = send_via_udp_impl( socket, dest_addr, packetIDP, 
                                        cmd, &ap );
    va_end( ap );
    return result;
}

static bool
send_msg_via_udp( const AddrInfo* addr, AddrInfo::ClientToken clientToken,
                  const unsigned char* buf, const size_t bufLen, 
                  uint32_t* packetIDP )
{
    bool result = AddrInfo::NULL_TOKEN != clientToken;
    if ( result ) {
        uint32_t asNetTok = htonl(clientToken);
        ssize_t nSent = send_via_udp( addr, packetIDP, XWPDEV_MSG, &asNetTok, 
                                      sizeof(asNetTok), buf, bufLen, NULL );
        logf( XW_LOGINFO, "%s: sent %d bytes (plus header) on UDP socket, "
              "token=%x(%d)", __func__, bufLen, clientToken, 
              clientToken );
        result = 0 < nSent;
        logf( XW_LOGINFO, "%s()=>%d", __func__, result );
    }
    return result;
}

static bool
send_msg_via_udp( const AddrInfo* addr, const unsigned char* buf, 
                  const size_t bufLen, uint32_t* packetIDP )
{
    return send_msg_via_udp( addr, addr->clientToken(), buf, 
                             bufLen, packetIDP );
}

/* No mutex here.  Caller better be ensuring no other thread can access this
 * socket. */
bool
send_with_length_unsafe( const AddrInfo* addr, const unsigned char* buf, 
                         const size_t bufLen, uint32_t* packetIDP )
{
    assert( !!addr );
    bool ok = false;

    if ( addr->isTCP() ) {
        int socket = addr->socket();
        if ( addr->isCurrent() ) {
            unsigned short len = htons( bufLen );
            ssize_t nSent = send( socket, &len, sizeof(len), 0 );
            if ( nSent == sizeof(len) ) {
                nSent = send( socket, buf, bufLen, 0 );
                if ( nSent == ssize_t(bufLen) ) {
                    logf( XW_LOGINFO, "%s: sent %d bytes on socket %d", __func__, 
                          nSent, socket );
                    ok = true;
                } else {
                    logf( XW_LOGERROR, "%s: send failed: %s (errno=%d)", __func__, 
                          strerror(errno), errno );
                }
            }
        } else {
            logf( XW_LOGINFO, "%s: dropping packet: socket %d reused", 
                  __func__, socket );
        }
        if ( NULL != packetIDP ) {
            *packetIDP = UDPAckTrack::PACKETID_NONE;
        }
    } else {
        ok = send_msg_via_udp( addr, buf, bufLen, packetIDP );
    }

    if ( !ok ) {
        logf( XW_LOGERROR, "%s(socket=%d) failed", __func__, socket );
    }

    return ok;
} /* send_with_length_unsafe */

void
send_havemsgs( const AddrInfo* addr )
{
    logf( XW_LOGINFO, "%s()", __func__ );
    send_via_udp( addr, NULL, XWPDEV_HAVEMSGS, NULL );
}

class MsgClosure {
public:
    MsgClosure( DevIDRelay devid, gpointer msg, uint16_t len, 
                OnMsgAckProc proc, void* procClosure )
    {
        m_devid = devid;
        m_len = len;
        m_msg = g_malloc(len);
        memcpy( m_msg, msg, len );
        m_proc = proc;
        m_procClosure = procClosure;
    }
    ~MsgClosure() {
        g_free( m_msg );
    }
    DevIDRelay m_devid;
    uint16_t m_len;
    gpointer m_msg;
    OnMsgAckProc m_proc;
    void* m_procClosure;
};

static void
onPostedMsgAcked( bool acked, uint32_t packetID, void* data )
{
    MsgClosure* mc = (MsgClosure*)data;
    if ( !acked ) {
        DBMgr::Get()->StoreMessage( mc->m_devid, 
                                    (const unsigned char*)mc->m_msg, 
                                    mc->m_len );
    }
    if ( NULL != mc->m_proc ) {
        (*mc->m_proc)( acked, mc->m_devid, packetID, mc->m_procClosure );
    }
    delete mc;
}

bool
post_message( DevIDRelay devid, const char* message, OnMsgAckProc proc,
              void* procClosure )
{
    const AddrInfo::AddrUnion* addru = DevMgr::Get()->get( devid );
    bool success = !!addru;
    if ( success ) {
        AddrInfo addr( addru );
        short len = strlen(message);
        short netLen = htons(len);
        uint32_t packetID;
        uint8_t buf[1 + sizeof(netLen) + len];
        XWRelayReg cmd = XWPDEV_ALERT;
        buf[0] = cmd;
        memcpy( &buf[1], &netLen, sizeof(netLen) );
        memcpy( &buf[1 + sizeof(netLen)], message, len );

        bool sent = send_via_udp( &addr, &packetID, cmd, &buf[1], 
                                  VSIZE(buf)-1, NULL );
        if ( sent ) {
            MsgClosure* mc = new MsgClosure( devid, buf, VSIZE(buf),
                                             proc, procClosure );
            UDPAckTrack::setOnAck( onPostedMsgAcked, packetID, (void*)mc );
        } else {
            DBMgr::Get()->StoreMessage( devid, (const unsigned char*)buf, 
                                        VSIZE(buf) );
        }
    }
    return success;
}

/* A CONNECT message from a device gives us the hostID and socket we'll
 * associate with one participant in a relayed session.  We'll store this
 * information with the cookie where other participants can find it when they
 * arrive.
 *
 * What to do if we already have a game going?  In that case the connection ID
 * passed in will be non-zero.  If the device can be associated with an
 * ongoing game, with its new socket, associate it and forward any messages
 * outstanding.  Otherwise close down the socket.  And maybe the others in the
 * game?
 */
static bool
processConnect( const unsigned char* bufp, int bufLen, const AddrInfo* addr )
{
    char cookie[MAX_INVITE_LEN+1];
    const unsigned char* end = bufp + bufLen;
    bool success = false;

    cookie[0] = '\0';

    unsigned short clientVersion;
    unsigned short flags;
    XWREASON err = flagsOK( &bufp, end, &clientVersion, &flags );
    if ( err == XWRELAY_ERROR_NONE ) {
        /* HostID srcID; */
        unsigned char nPlayersH;
        unsigned char nPlayersT;
        unsigned short seed;
        unsigned char langCode;
        unsigned char makePublic, wantsPublic;
        if ( readStr( &bufp, end, cookie, sizeof(cookie) ) 
             && getNetByte( &bufp, end, &wantsPublic )
             && getNetByte( &bufp, end, &makePublic )
             /* && getNetByte( &bufp, end, &srcID ) */
             && getNetByte( &bufp, end, &nPlayersH )
             && getNetByte( &bufp, end, &nPlayersT )
             && getNetShort( &bufp, end, &seed )
             && getNetByte( &bufp, end, &langCode ) ) {

            DevID devID;
            getDevID( &bufp, end, flags, &devID );

            logf( XW_LOGINFO, "%s(): langCode=%d; nPlayersT=%d; "
                  "wantsPublic=%d; seed=%.4X",
                  __func__, langCode, nPlayersT, wantsPublic, seed );

            /* Make sure second thread can't create new cref for same cookie
               this one just handled.*/
            static pthread_mutex_t s_newCookieLock = PTHREAD_MUTEX_INITIALIZER;
            MutexLock ml( &s_newCookieLock );

            SafeCref scr( cookie, addr, clientVersion, &devID, 
                          nPlayersH, nPlayersT, seed, langCode, 
                          wantsPublic, makePublic );
            /* nPlayersT etc could be slots in SafeCref to avoid passing
               here */
            success = scr.Connect( nPlayersH, nPlayersT, seed );
        } else {
            err = XWRELAY_ERROR_BADPROTO;
        }
    }

    if ( err != XWRELAY_ERROR_NONE ) {
        denyConnection( addr, err );
    }
    return success;
} /* processConnect */

static bool
processReconnect( const unsigned char* bufp, int bufLen, const AddrInfo* addr )
{
    const unsigned char* end = bufp + bufLen;
    bool success = false;

    logf( XW_LOGINFO, "%s()", __func__ );

    unsigned short clientVersion;
    unsigned short flags;
    XWREASON err = flagsOK( &bufp, end, &clientVersion, &flags );
    if ( err == XWRELAY_ERROR_NONE ) {
        char cookie[MAX_INVITE_LEN+1];
        char connName[MAX_CONNNAME_LEN+1] = {0};
        HostID srcID;
        unsigned char nPlayersH;
        unsigned char nPlayersT;
        unsigned short gameSeed;
        unsigned char makePublic, wantsPublic;
        unsigned char langCode;

        if ( readStr( &bufp, end, cookie, sizeof(cookie) )
             && getNetByte( &bufp, end, &wantsPublic )
             && getNetByte( &bufp, end, &makePublic )
             && getNetByte( &bufp, end, &srcID )
             && getNetByte( &bufp, end, &nPlayersH )
             && getNetByte( &bufp, end, &nPlayersT )
             && getNetShort( &bufp, end, &gameSeed )
             && getNetByte( &bufp, end, &langCode )
             && readStr( &bufp, end, connName, sizeof(connName) ) ) {

            DevID devID;
            getDevID( &bufp, end, flags, &devID );

            SafeCref scr( connName[0]? connName : NULL, 
                          cookie, srcID, addr, clientVersion, &devID,
                          nPlayersH, nPlayersT, gameSeed, langCode,
                          wantsPublic, makePublic );
            success = scr.Reconnect( nPlayersH, nPlayersT, gameSeed, 
                                     &err );
            // if ( !success ) {
            //     assert( err != XWRELAY_ERROR_NONE );
            // }
        } else { 
            err = XWRELAY_ERROR_BADPROTO;
        }
    }

    if ( err != XWRELAY_ERROR_NONE ) {
        denyConnection( addr, err );
    }

    return success;
} /* processReconnect */

static bool
processAck( const unsigned char* bufp, int bufLen, const AddrInfo* addr )
{
    bool success = false;
    const unsigned char* end = bufp + bufLen;
    HostID srcID;
    if ( getNetByte( &bufp, end, &srcID ) ) {
        SafeCref scr( addr );
        success = scr.HandleAck( srcID );
    }
    return success;
}

static bool
processDisconnect( const unsigned char* bufp, int bufLen, const AddrInfo* addr )
{
    const unsigned char* end = bufp + bufLen;
    CookieID cookieID;
    HostID hostID;
    bool success = false;

    if ( getNetShort( &bufp, end, &cookieID ) 
         && getNetByte( &bufp, end, &hostID ) ) {

        SafeCref scr( addr );
        scr.Disconnect( addr, hostID );
        success = true;
    } else {
        logf( XW_LOGERROR, "dropping XWRELAY_GAME_DISCONNECT; wrong length" );
    }
    return success;
} /* processDisconnect */

static void
killSocket( const AddrInfo* addr )
{
    logf( XW_LOGINFO, "%s(addr.socket=%d)", __func__, addr->socket() );
    CRefMgr::Get()->RemoveSocketRefs( addr );
}

time_t
uptime( void ) 
{
    static time_t startTime = time(NULL);
    return time(NULL) - startTime;
}

void
blockSignals( void )
{
    sigset_t set;
    sigemptyset( &set );
    sigaddset( &set, SIGINT );
    sigaddset( &set, SIGTERM);
    int s = pthread_sigmask( SIG_BLOCK, &set, NULL );
    assert( 0 == s );
}

int
GetNSpawns(void)
{
    return s_nSpawns;
}

/* forward the message.  Need only change the command after looking up the
 * socket and it's ready to go. */
static bool
forwardMessage( const unsigned char* buf, int buflen, const AddrInfo* addr )
{
    bool success = false;
    const unsigned char* bufp = buf + 1; /* skip cmd */
    const unsigned char* end = buf + buflen;
    CookieID cookieID;
    HostID src;
    HostID dest;

    if ( getNetShort( &bufp, end, &cookieID )
         && getNetByte( &bufp, end, &src ) 
         && getNetByte( &bufp, end, &dest ) 
         && 0 < src && 0 < dest  ) {

        if ( COOKIE_ID_NONE == cookieID ) {
            SafeCref scr( addr );
            success = scr.Forward( src, addr, dest, buf, buflen );
        } else {
            /* won't work if not allcon; will be 0 */
            SafeCref scr( cookieID, true );
            success = scr.Forward( src, addr, dest, buf, buflen );
        }
    }
    return success;
} /* forwardMessage */

static bool
processMessage( const unsigned char* buf, int bufLen, const AddrInfo* addr )
{
    bool success = false;            /* default is failure */
    XWRELAY_Cmd cmd = *buf;

    logf( XW_LOGINFO, "%s got %s", __func__, cmdToStr(cmd) );

    switch( cmd ) {
    case XWRELAY_GAME_CONNECT: 
        success = processConnect( buf+1, bufLen-1, addr );
        break;
    case XWRELAY_GAME_RECONNECT: 
        success = processReconnect( buf+1, bufLen-1, addr );
        break;
    case XWRELAY_ACK:
        success = processAck( buf+1, bufLen-1, addr );
        break;
    case XWRELAY_GAME_DISCONNECT:
        success = processDisconnect( buf+1, bufLen-1, addr );
        break;
#ifdef RELAY_HEARTBEAT
    case XWRELAY_HEARTBEAT:
        success = processHeartbeat( buf + 1, bufLen - 1, socket );
        break;
#endif
    case XWRELAY_MSG_TORELAY:
        success = forwardMessage( buf, bufLen, addr );
        break;
    default:
        logf( XW_LOGERROR, "%s bad: %d", __func__, cmd );
        break;
        /* just drop it */
    }

    if ( !success ) {
        XWThreadPool::GetTPool()->EnqueueKill( addr, "failure" );
    }

    return success;
} /* processMessage */

int 
make_socket( unsigned long addr, unsigned short port )
{
    int sock = socket( AF_INET, SOCK_STREAM, 0 );
    assert( sock );

    /* We may be relaunching after crashing with sockets open.  SO_REUSEADDR
       allows them to be immediately rebound. */
    int t = true;
    if ( 0 != setsockopt( sock, SOL_SOCKET, SO_REUSEADDR, &t, sizeof(t) ) ) {
        logf( XW_LOGERROR, "setsockopt failed. errno = %s (%d)\n", 
              strerror(errno), errno );
        return -1;
    }

    sockaddr_in sockAddr;
    sockAddr.sin_family = AF_INET;
    sockAddr.sin_addr.s_addr = htonl(addr);
    sockAddr.sin_port = htons(port);

    int result = bind( sock, (struct sockaddr*)&sockAddr, sizeof(sockAddr) );
    if ( result != 0 ) {
        logf( XW_LOGERROR, "exiting: unable to bind port %d: %d, "
              "errno = %s (%d)\n", port, result, strerror(errno), errno );
        return -1;
    }
    logf( XW_LOGINFO, "bound socket %d on port %d", sock, port );

    result = listen( sock, 5 );
    if ( result != 0 ) {
        logf( XW_LOGERROR, "exiting: unable to listen: %d, "
              "errno = %s (%d)\n", result, strerror(errno), errno );
        return -1;
    }
    return sock;
} /* make_socket */

static void
usage( char* arg0 )
{
    fprintf( stderr, "usage: %s \\\n", arg0 );

    fprintf( stderr,
             "\t-?                   (print this help)\\\n"
             "\t-c <cport>           (localhost port for control console)\\\n"
#ifdef DO_HTTP
             "\t-w <cport>           (localhost port for web interface)\\\n"
#endif
             "\t-D                   (don't become daemon)\\\n"
             "\t-F                   (don't fork and wait to respawn child)\\\n"
             "\t-f <conffile>        (config file)\\\n"
             "\t-h                   (print this help)\\\n"
             "\t-i <idfile>          (file where next global id stored)\\\n"
             "\t-l <logfile>         (write logs here, not stderr)\\\n"
             "\t-M <message>         (Put in maintenance mode, and return this string to all callers)\\\n"
             "\t-m <num_sockets>     (max number of simultaneous sockets to have open)\\\n"
             "\t-n <serverName>      (used in permID generation)\\\n"
             "\t-p <port>            (port to listen on)\\\n"
#ifdef DO_HTTP
             "\t-s <path>            (path to css file for http iface)\\\n"
#endif
             "\t-t <nWorkerThreads>  (how many worker threads to use)\\\n"
             );
    fprintf( stderr, "git rev. %s\n", SVN_REV );
}

/* sockets that need to be closable from interrupt handler */
ListenerMgr g_listeners;
int g_control;
#ifdef DO_HTTP
static int g_http = -1;
#endif

void
shutdown()
{
    XWThreadPool* tPool = XWThreadPool::GetTPool();
    if ( tPool != NULL ) {
        tPool->Stop();
    }

    CRefMgr* cmgr = CRefMgr::Get();
    if ( cmgr != NULL ) {
        cmgr->CloseAll();
        delete cmgr;
    }

    delete tPool;

    //stop_ctrl_threads();

    g_listeners.RemoveAll();
    close( g_control );
#ifdef DO_HTTP
    close( g_http );
#endif
    exit( 0 );
    logf( XW_LOGINFO, "exit done" );
}

static void
SIGINT_handler( int sig )
{
    logf( XW_LOGERROR, "%s", __func__ );
    shutdown();
}

#ifdef SPAWN_SELF
static void
printWhy( int status ) 
{
    if ( WIFEXITED(status) ) {
        logf( XW_LOGINFO, "why: exited" );
    } else if ( WIFSIGNALED(status) ) {
        logf( XW_LOGINFO, "why: signaled; signal: %d", WTERMSIG(status) );
    } else if ( WCOREDUMP(status) ) {
        logf( XW_LOGINFO, "why: core" );
    } else if ( WIFSTOPPED(status) ) {
        logf( XW_LOGINFO, "why: traced" );
    }
} /* printWhy */
#endif

static void
parentDied( int sig )
{
    logf( XW_LOGINFO, "%s", __func__ );
    exit(0);
}

static void
handlePipe( int sig )
{
    logf( XW_LOGINFO, "%s", __func__ );
}

static void
pushShort( vector<unsigned char>& out, unsigned short num )
{
    num = htons( num );
    out.insert( out.end(), (unsigned char*)&num, ((unsigned char*)&num) + 2 );
}

static void
pushMsgs( vector<unsigned char>& out, DBMgr* dbmgr, const char* connName, 
          HostID hid, vector<DBMgr::MsgInfo>& msgs, vector<int>& msgIDs )
{
    vector<DBMgr::MsgInfo>::const_iterator iter;
    for ( iter = msgs.begin(); msgs.end() != iter; ++iter ) {
        DBMgr::MsgInfo msg = *iter;
        int len = msg.msg.length();
        uint8_t* ptr = (uint8_t*)msg.msg.c_str();
        pushShort( out, len );
        out.insert( out.end(), ptr, ptr + len );
        msgIDs.push_back( msg.msgID );
    }
}

static void
handleMsgsMsg( const AddrInfo* addr, bool sendFull,
               const unsigned char* bufp, const unsigned char* end )
{
    unsigned short nameCount;
    int ii;
    if ( getNetShort( &bufp, end, &nameCount ) ) {
        DBMgr* dbmgr = DBMgr::Get();
        vector<unsigned char> out(4); /* space for len and n_msgs */
        assert( out.size() == 4 );
        vector<int> msgIDs;
        for ( ii = 0; ii < nameCount && bufp < end; ++ii ) {

            // See NetUtils.java for reply format
            // message-length: 2
            // nameCount: 2
            // name count reps of:
            //    counts-this-name: 2
            //    counts-this-name reps of
            //       len: 2
            //       msg: <len>

            // pack msgs for one game
            HostID hid;
            char connName[MAX_CONNNAME_LEN+1];
            if ( !parseRelayID( &bufp, end, connName, sizeof(connName),
                                &hid ) ) {
                break;
            }

            dbmgr->RecordAddress( connName, hid, addr );

            /* For each relayID, write the number of messages and then
               each message (in the getmsg case) */
            vector<DBMgr::MsgInfo> msgs;
            dbmgr->GetStoredMessages( connName, hid, msgs );
            pushShort( out, msgs.size() );
            if ( sendFull ) {
                pushMsgs( out, dbmgr, connName, hid, msgs, msgIDs );
            }
        }

        unsigned short tmp = htons( out.size() - sizeof(tmp) );
        memcpy( &out[0], &tmp, sizeof(tmp) );
        tmp = htons( nameCount );
        memcpy( &out[2], &tmp, sizeof(tmp) );
        ssize_t nwritten = write( addr->socket(), &out[0], out.size() );
        logf( XW_LOGVERBOSE0, "%s: wrote %d bytes", __func__, nwritten );
        if ( sendFull && nwritten >= 0 && (size_t)nwritten == out.size() ) {
            dbmgr->RecordSent( &msgIDs[0], msgIDs.size() );
            dbmgr->RemoveStoredMessages( msgIDs );
        }
    }
} // handleMsgsMsg

#define NUM_PER_LINE 8
void
log_hex( const unsigned char* memp, int len, const char* tag )
{
    const char* hex = "0123456789ABCDEF";
    int i, j;
    int offset = 0;

    while ( offset < len ) {
        char buf[128];
        unsigned char vals[NUM_PER_LINE*3];
        unsigned char* valsp = vals;
        unsigned char chars[NUM_PER_LINE+1];
        unsigned char* charsp = chars;
        int oldOffset = offset;

        for ( i = 0; i < NUM_PER_LINE && offset < len; ++i ) {
            unsigned char byte = memp[offset];
            for ( j = 0; j < 2; ++j ) {
                *valsp++ = hex[(byte & 0xF0) >> 4];
                byte <<= 4;
            }
            *valsp++ = ':';

            byte = memp[offset];
            if ( (byte >= 'A' && byte <= 'Z')
                 || (byte >= 'a' && byte <= 'z')
                 || (byte >= '0' && byte <= '9') ) {
                /* keep it */
            } else {
                byte = '.';
            }
            *charsp++ = byte;
            ++offset;
        }
        *(valsp-1) = '\0';      /* -1 to overwrite ':' */
        *charsp = '\0';

        if ( (NULL == tag) || (strlen(tag) + sizeof(vals) >= sizeof(buf)) ) {
            tag = "<tag>";
        }
        snprintf( buf, sizeof(buf), "%s[%d]: %s %s", tag, oldOffset, 
                  vals, chars );
        fprintf( stderr, "%s\n", buf );
    }
} // log_hex

static bool
handlePutMessage( SafeCref& scr, HostID hid, const AddrInfo* addr, 
                  unsigned short len, const unsigned char** bufp, 
                  const unsigned char* end )
{
    bool success = false;
    const unsigned char* start = *bufp;
    HostID src;
    HostID dest;
    XWRELAY_Cmd cmd;
    // sanity check that cmd and hostids are there
    if ( getNetByte( bufp, end, &cmd )
         && getNetByte( bufp, end, &src )
         && getNetByte( bufp, end, &dest ) ) {
        success = true;		// meaning, buffer content looks ok
        *bufp = start + len;
        if ( ( cmd == XWRELAY_MSG_TORELAY_NOCONN ) && ( hid == dest ) ) {
            scr.PutMsg( src, addr, dest, start, len );
        }
    }
    logf( XW_LOGINFO, "%s()=>%d", __func__, success );
    return success;
}

static void
handleProxyMsgs( int sock, const AddrInfo* addr, const unsigned char* bufp, 
                 const unsigned char* end )
{
    // log_hex( bufp, end-bufp, __func__ );
    unsigned short nameCount;
    int ii;
    if ( getNetShort( &bufp, end, &nameCount ) ) {
        for ( ii = 0; ii < nameCount && bufp < end; ++ii ) {

            // See NetUtils.java for reply format
            // message-length: 2
            // nameCount: 2
            // name count reps of:
            //    counts-this-name: 2
            //    counts-this-name reps of
            //       len: 2
            //       msg: <len>

            // pack msgs for one game
            HostID hid;
            char connName[MAX_CONNNAME_LEN+1];
            if ( !parseRelayID( &bufp, end, connName, sizeof(connName),
                                &hid ) ) {
                break;
            }
            unsigned short nMsgs;
            if ( getNetShort( &bufp, end, &nMsgs ) ) {
                SafeCref scr( connName );
                while ( scr.IsValid() && nMsgs-- > 0 ) {
                    unsigned short len;
                    if ( getNetShort( &bufp, end, &len ) ) {
                        if ( handlePutMessage( scr, hid, addr, len, &bufp, end ) ) {
                            continue;
                        }
                    }
                    break;
                }
            }
        }
        if ( end - bufp != 1 ) {
            logf( XW_LOGERROR, "%s: buf != end: %p vs %p (+1)", __func__, bufp, end );
        }
        // assert( bufp == end );  // don't ship with this!!!
    }
} // handleProxyMsgs

static void
game_thread_proc( UdpThreadClosure* utc )
{
    if ( !processMessage( utc->buf(), utc->len(), utc->addr() ) ) {
        XWThreadPool::GetTPool()->CloseSocket( utc->addr() );
    }
}

static void
proxy_thread_proc( UdpThreadClosure* utc )
{
    const int len = utc->len();
    const AddrInfo* addr = utc->addr();

    if ( len > 0 ) {
        assert( addr->isTCP() );
        int socket = addr->socket();
        const unsigned char* bufp = utc->buf();
        const unsigned char* end = bufp + len;
        if ( (0 == *bufp++) ) { /* protocol */
            XWPRXYCMD cmd = (XWPRXYCMD)*bufp++;
            switch( cmd ) {
            case PRX_NONE:
                break;
            case PRX_PUB_ROOMS:
                if ( len >= 4 ) {
                    int lang = *bufp++;
                    int nPlayers = *bufp++;
                    string names;
                    int nNames;

                    // sleep(2);   /* use this to test when running locally */

                    DBMgr::Get()->PublicRooms( lang, nPlayers, &nNames, names );
                    unsigned short netshort = htons( names.size()
                                                     + sizeof(unsigned short) );
                    write( socket, &netshort, sizeof(netshort) );
                    netshort = htons( (unsigned short)nNames );
                    write( socket, &netshort, sizeof(netshort) );
                    write( socket, names.c_str(), names.size() );
                }
                break;
            case PRX_HAS_MSGS:
            case PRX_GET_MSGS:
                if ( len >= 2 ) {
                    handleMsgsMsg( addr, PRX_GET_MSGS == cmd, bufp, end );
                }
                break;          /* PRX_HAS_MSGS */

            case PRX_PUT_MSGS:
                handleProxyMsgs( socket, addr, bufp, end );
                break;

            case PRX_DEVICE_GONE: {
                logf( XW_LOGINFO, "%s: got PRX_DEVICE_GONE", __func__ );
                if ( len >= 2 ) {
                    unsigned short nameCount;
                    if ( getNetShort( &bufp, end, &nameCount ) ) {
                        int ii;
                        for ( ii = 0; ii < nameCount; ++ii ) {
                            unsigned short seed;
                            if ( !getNetShort( &bufp, end, &seed ) ) {
                                break;
                            }

                            HostID hid;
                            char connName[MAX_CONNNAME_LEN+1];
                            if ( !parseRelayID( &bufp, end, connName, 
                                                sizeof( connName ), &hid ) ) {
                                break;
                            }
                            SafeCref scr( connName );
                            scr.DeviceGone( hid, seed );
                        }
                    }
                }
                int olen = 0;        /* return a 0-length message */
                write( socket, &olen, sizeof(olen) );
                break;          /* PRX_DEVICE_GONE */
            }
            default:
                logf( XW_LOGERROR, "unexpected command %d", __func__, cmd );
                break;
            }
        }
    }
    XWThreadPool::GetTPool()->CloseSocket( addr );
} // proxy_thread_proc

static short
addRegID( unsigned char* ptr, DevIDRelay relayID )
{
    short used = 0;
    char idbuf[9];
    int idLen = snprintf( idbuf, sizeof(idbuf), "%.8X", relayID );
    short lenNBO = htons(idLen);
    memcpy( &ptr[used], &lenNBO, sizeof(lenNBO) );
    used += sizeof(lenNBO);
    memcpy( &ptr[used], idbuf, idLen );
    used += idLen;
    return used;
}

static void 
registerDevice( const DevID* devID, const AddrInfo* addr, int clientVers,
                string devDesc )
{
    DevIDRelay relayID;
    DBMgr* dbMgr = DBMgr::Get();
    short indx = 0;
    unsigned char buf[32];

    if ( ID_TYPE_RELAY == devID->m_devIDType ) { // known to us; just update the time
        relayID = devID->asRelayID();
        if ( dbMgr->UpdateDevice( relayID, clientVers, devDesc.c_str(), 
                                  true ) ) {
            int nMsgs = dbMgr->CountStoredMessages( relayID );
            if ( 0 < nMsgs ) {
                send_havemsgs( addr );
            }
        } else {
            indx += addRegID( &buf[indx], relayID );
            send_via_udp( addr, NULL, XWPDEV_BADREG, buf, indx, NULL );

            relayID = DBMgr::DEVID_NONE;
        } 
    } else {
        relayID = dbMgr->RegisterDevice( devID, clientVers, devDesc.c_str() );
    }

    if ( DBMgr::DEVID_NONE != relayID ) {
        // send it back to the device
        indx += addRegID( &buf[indx], relayID );

        uint16_t maxInterval = UDPAger::Get()->MaxIntervalSeconds();
        maxInterval = ntohs(maxInterval);

        send_via_udp( addr, NULL, XWPDEV_REGRSP, buf, indx, 
                      &maxInterval, sizeof(maxInterval), NULL );

        // Map the address to the devid for future sending purposes.
        DevMgr::Get()->Remember( relayID, addr );
    }
}

void
onMsgAcked( bool acked, uint32_t packetID, void* data )
{
    logf( XW_LOGINFO, "%s(packetID=%d)", __func__, packetID );
    if ( acked ) {
        int msgID = (int)data;
        DBMgr::Get()->RemoveStoredMessage( msgID );
    }
}

static void
retrieveMessages( DevID& devID, const AddrInfo* addr )
{
    logf( XW_LOGINFO, "%s()", __func__ );
    DBMgr* dbMgr = DBMgr::Get();
    vector<DBMgr::MsgInfo> msgs;
    dbMgr->GetStoredMessages( devID.asRelayID(), msgs );

    vector<DBMgr::MsgInfo>::const_iterator iter;
    for ( iter = msgs.begin(); iter != msgs.end(); ++iter ) {
        DBMgr::MsgInfo msg = *iter;
        uint32_t packetID;
        if ( !send_msg_via_udp( addr, msg.token, 
                                (unsigned char*)msg.msg.c_str(), 
                                msg.msg.length(), &packetID ) ) {
            logf( XW_LOGERROR, "%s: unable to send to devID %d", 
                  __func__, devID.asRelayID() );
            break;
        }
        UDPAckTrack::setOnAck( onMsgAcked, packetID, (void*)msg.msgID );
    }
}

static const char*
msgToStr( XWRelayReg msg )
{
    const char* str;
# define CASE_STR(c)  case c: str = #c; break
    switch( msg ) {
    CASE_STR(XWPDEV_UNAVAIL);
    CASE_STR(XWPDEV_REG);
    CASE_STR(XWPDEV_REGRSP);
    CASE_STR(XWPDEV_KEEPALIVE);
    CASE_STR(XWPDEV_HAVEMSGS);
    CASE_STR(XWPDEV_RQSTMSGS);
    CASE_STR(XWPDEV_MSG);
    CASE_STR(XWPDEV_MSGNOCONN);
    CASE_STR(XWPDEV_MSGRSP);
    CASE_STR(XWPDEV_BADREG);
    CASE_STR(XWPDEV_ALERT);     // should not receive this....
    CASE_STR(XWPDEV_ACK);
    CASE_STR(XWPDEV_DELGAME);
    default:
        str = "<unknown>";
        break;
    }
# undef CASE_STR
    return str;

}

static void
ackPacketIf( const UDPHeader* header, const AddrInfo* addr )
{
    if ( UDPAckTrack::shouldAck( header->cmd ) ) {
        uint32_t packetID = header->packetID;
        logf( XW_LOGINFO, "%s: acking packet %d", __func__, packetID );
        packetID = htonl( packetID );
        send_via_udp( addr, NULL, XWPDEV_ACK, 
                      &packetID, sizeof(packetID), NULL );
    }
}

static void
handle_udp_packet( UdpThreadClosure* utc )
{
    const unsigned char* ptr = utc->buf();
    const unsigned char* end = ptr + utc->len();

    UDPHeader header;
    if ( getHeader( &ptr, end, &header ) ) {
        logf( XW_LOGINFO, "%s(msg=%s)", __func__, msgToStr( header.cmd ) );
        switch( header.cmd ) {
        case XWPDEV_REG: {
            DevIDType typ = (DevIDType)*ptr++;
            DevID devID( typ );
            if ( getRelayDevID( &ptr, end, devID ) ) {
                uint16_t clientVers;
                string devDesc;
                if ( getNetShort( &ptr, end, &clientVers )
                     && getShortInitString( &ptr, end, devDesc ) ) {
                    registerDevice( &devID, utc->addr(), clientVers, devDesc );
                }
            }
            break;
        }
        case XWPDEV_MSG: {
            AddrInfo::ClientToken clientToken;
            memcpy( &clientToken, ptr, sizeof(clientToken) );
            ptr += sizeof(clientToken);
            clientToken = ntohl( clientToken );
            if ( AddrInfo::NULL_TOKEN != clientToken ) {
                AddrInfo addr( g_udpsock, clientToken, utc->saddr() );
                (void)processMessage( ptr, end - ptr, &addr );
            } else {
                logf( XW_LOGERROR, "%s: dropping packet with token of 0" );
            }
            break;
        }
        case XWPDEV_MSGNOCONN: {
            AddrInfo::ClientToken clientToken;
            if ( getNetLong( &ptr, end, &clientToken ) 
                 && AddrInfo::NULL_TOKEN != clientToken ) {
                HostID hid;
                char connName[MAX_CONNNAME_LEN+1];
                if ( !parseRelayID( &ptr, end, connName, 
                                    sizeof( connName ), &hid ) ) {
                    logf( XW_LOGERROR, "parse failed!!!" );
                    break;
                }
                SafeCref scr( connName );
                if ( scr.IsValid() ) {
                    AddrInfo addr( g_udpsock, clientToken, utc->saddr() );
                    handlePutMessage( scr, hid, &addr, end - ptr, &ptr, end );
                    assert( ptr == end ); // DON'T CHECK THIS IN!!!
                } else {
                    logf( XW_LOGERROR, "%s: invalid scr for %s", __func__, 
                          connName );
                }
            } else {
                logf( XW_LOGERROR, "no clientToken found!!!" );
            }
            break;
        }
        case XWPDEV_KEEPALIVE:
        case XWPDEV_RQSTMSGS: {
            unsigned short idLen;
            if ( !getNetShort( &ptr, end, &idLen ) ) {
                break;
            }
            if ( end - ptr > idLen ) {
                logf( XW_LOGERROR, "full devID not received" );
                break;
            }
            DevID devID( ID_TYPE_RELAY );
            devID.m_devIDString.append( (const char*)ptr, idLen );
            ptr += idLen;

            const AddrInfo* addr = utc->addr();
            DevMgr::Get()->Remember( devID.asRelayID(), addr );

            if ( XWPDEV_RQSTMSGS == header.cmd ) {
                retrieveMessages( devID, addr );
            }
            break;
        }
        case XWPDEV_ACK: {
            uint32_t packetID;
            if ( getNetLong( &ptr, end, &packetID ) ) {
                logf( XW_LOGINFO, "ack for packet %d", packetID );
                UDPAckTrack::recordAck( packetID );
            }
            break;
        }
        case XWPDEV_DELGAME: {
            DevID devID( ID_TYPE_RELAY );
            if ( !getRelayDevID( &ptr, end, devID ) ) {
                break;
            }
            AddrInfo::ClientToken clientToken;
            if ( getNetLong( &ptr, end, &clientToken ) 
                 && AddrInfo::NULL_TOKEN != clientToken ) {
                unsigned short seed;
                HostID hid;
                string connName;
                if ( DBMgr::Get()->FindPlayer( devID.asRelayID(), clientToken, 
                                               connName, &hid, &seed ) ) {
                    SafeCref scr( connName.c_str() );
                    scr.DeviceGone( hid, seed );
                }
            }
            break;
        }
        default:
            logf( XW_LOGERROR, "%s: unexpected msg %d", __func__, header.cmd );
        }

        // Do this after the device and address are registered
        ackPacketIf( &header, utc->addr() );
    }
}

static void
read_udp_packet( int udpsock )
{
    uint8_t buf[MAX_MSG_LEN];
    AddrInfo::AddrUnion saddr;
    memset( &saddr, 0, sizeof(saddr) );
    socklen_t fromlen = sizeof(saddr.u.addr_in);

    ssize_t nRead = recvfrom( udpsock, buf, sizeof(buf), 0 /*flags*/,
                              &saddr.u.addr, &fromlen );
    if ( 0 < nRead ) {
#ifdef LOG_UDP_PACKETS
        gchar* b64 = g_base64_encode( (unsigned char*)&saddr, sizeof(saddr) );
        logf( XW_LOGINFO, "%s: recvfrom=>%d (saddr='%s')", __func__, nRead, b64 );
        g_free( b64 );
#else
        logf( XW_LOGINFO, "%s: recvfrom=>%d", __func__, nRead );
#endif

        AddrInfo addr( udpsock, &saddr, false );
        UDPAger::Get()->Refresh( &addr );
        UdpQueue::get()->handle( &addr, buf, nRead, handle_udp_packet );
    }
}

/* From stack overflow, toward a snprintf with an expanding buffer.
 */
string&
string_printf( string& str, const char* fmt, ... )
{
    const int origsiz = str.size();
    int addsiz = 100;
    va_list ap;
    for ( ; ; ) {
        str.resize( origsiz + addsiz );

        va_start( ap, fmt );
        int len = vsnprintf( (char *)str.c_str() + origsiz, addsiz, fmt, ap );
        va_end( ap );

        if ( len >= addsiz ) {   // needs more space
            addsiz = len + 1;
        } else if ( -1 == len ) {
            assert(0);          // should be impossible
        } else {
            str.resize( origsiz + len );
            break;
        }
    }
    return str;
}

// Going with non-blocking instead
#if 0
static void
set_timeouts( int sock )
{
    struct timeval tv;
    int result;

    int timeout = 5;
    (void)RelayConfigs::GetConfigs()->GetValueFor( "SOCK_TIMEOUT_SECONDS", 
                                                   &timeout );

    tv.tv_sec = timeout;     /* seconds */
    tv.tv_usec = 0;    /* microseconds */

    result = setsockopt( sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv) );
    if ( 0 != result ) {
        logf( XW_LOGERROR, "setsockopt=>%d (%s)", errno, strerror(errno) );
        assert( 0 );
    }
    result = setsockopt( sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv) );
    if ( 0 != result ) {
        logf( XW_LOGERROR, "setsockopt=>%d (%s)", errno, strerror(errno) );
        assert( 0 );
    }
}
#endif

static void
enable_keepalive( int sock )
{
    int optval = 1;
    if ( 0 > setsockopt( sock, SOL_SOCKET, SO_KEEPALIVE, 
                         &optval, sizeof( optval ) ) ) {
        logf( XW_LOGERROR, "setsockopt(sock=%d, SO_KEEPALIVE)=>%d (%s)", sock, errno, 
              strerror(errno) );
        assert( 0 );
    }
    /*
      The above will kill sockets, eventually, whose remote ends have died
      without notifying us.  (Duplicate by pulling a phone's battery while it
      has an open connection.)  It'll take nearly three hours, however.  The
      info below appears to allow for significantly shortening the time,
      though at the expense of greater network traffic.  I'm going to let it
      run this way before bothering with anything more.

      from http://tldp.org/HOWTO/html_single/TCP-Keepalive-HOWTO/

      "There are also three other socket options you can set for keepalive
      when you write your application. They all use the SOL_TCP level instead
      of SOL_SOCKET, and they override system-wide variables only for the
      current socket.  If you read without writing first, the current
      system-wide parameters will be returned."

      * TCP_KEEPCNT: overrides tcp_keepalive_probes
      * TCP_KEEPIDLE: overrides tcp_keepalive_time
      * TCP_KEEPINTVL: overrides tcp_keepalive_intvl
      */
}

static void
maint_str_loop( int udpsock, const char* str )
{
    logf( XW_LOGINFO, "%s()", __func__ );
    assert( -1 != udpsock );
    short len = strlen(str);
    unsigned char outbuf[sizeof(len) + len];
    short lenNS = htons( len );
    memcpy( &outbuf[0], &lenNS, sizeof(lenNS) );
    memcpy( &outbuf[0+sizeof(len)], str, len );

    fd_set rfds;
    for ( ; ; ) {
        FD_ZERO(&rfds);
        FD_SET( udpsock, &rfds );
        int retval = select( udpsock + 1, &rfds, NULL, NULL, NULL );
        if ( 0 > retval ) {
            logf( XW_LOGERROR, "%s: select=>%d (errno=%d/%s)", __func__, retval,
                  errno, strerror(errno) );
            break;
        }
        if ( FD_ISSET( udpsock, &rfds ) ) {
            unsigned char buf[512];
            AddrInfo::AddrUnion saddr;
            memset( &saddr, 0, sizeof(saddr) );
            socklen_t fromlen = sizeof(saddr.u.addr_in);

            ssize_t nRead = recvfrom( udpsock, buf, sizeof(buf), 0 /*flags*/,
                                      &saddr.u.addr, &fromlen );
            logf( XW_LOGINFO, "%s(); got %d bytes", __func__, nRead);

            UDPHeader header;
            const unsigned char* ptr = buf;
            uint32_t unavail = 0; // temp!
            if ( getHeader( &ptr, ptr + nRead, &header ) ) {
                send_via_udp( udpsock, &saddr.u.addr, NULL, XWPDEV_UNAVAIL,
                              &unavail, sizeof(unavail),
                              outbuf, sizeof(outbuf), 
                              NULL );
            } else {
                logf( XW_LOGERROR, "unexpected data" );
            }
        }
    } // for
} // maint_str_loop

int
main( int argc, char** argv )
{
    int port = 0;
    int ctrlport = 0;
    int udpport = -1;
#ifdef DO_HTTP
    int httpport = 0;
    const char* cssFile = NULL;
#endif
    int nWorkerThreads = 0;
    char* conffile = NULL;
    const char* serverName = NULL;
    // const char* idFileName = NULL;
    const char* logFile = NULL;
    const char* maint_str = NULL;
    bool doDaemon = true;
    bool doFork = true;

    (void)uptime();                /* force capture of start time */

    /* Verify sizes here... */
    assert( sizeof(CookieID) == 2 );

    /* Read options. Options trump config file values when they conflict, but
       the name of the config file is an option so we have to get that
       first. */

    for ( ; ; ) {
       int opt = getopt(argc, argv, "h?c:p:M:m:n:f:l:t:s:u:w:"
                        "DF" );

       if ( opt == -1 ) {
           break;
       }
       switch( opt ) {
       case '?':
       case 'h':
           usage( argv[0] );
           exit( 0 );
       case 'c':
           ctrlport = atoi( optarg );
           break;
#ifdef DO_HTTP
       case 'w':
           httpport = atoi( optarg );
           break;
       case 's':
           cssFile = optarg;
           break;
#else
       case 'w':
       case 's':
           fprintf( stderr, "option -%c disabled and ignored\n", opt );
           break;
#endif
       case 'D':
           doDaemon = false;
           break;
       case 'F':
           doFork = false;
           break;
       case 'f':
           conffile = optarg;
           break;
       // case 'i':
       //     idFileName = optarg;
       //     break;
       case 'l':
           logFile = optarg;
           break;
       case 'M':
           maint_str = optarg;
           break;
       case 'm':
           g_maxsocks = atoi( optarg );
           break;
       case 'n':
           serverName = optarg;
           break;
       case 'p':
           port = atoi( optarg );
           break;
       case 't':
           nWorkerThreads = atoi( optarg );
           break;
       case 'u':
           udpport = atoi( optarg );
           break;
       default:
           usage( argv[0] );
           exit( 1 );
       }
    }

    /* Did we consume all the options passed in? */
    if ( optind != argc ) {
        usage( argv[0] );
        exit( 1 );
    }

    RelayConfigs::InitConfigs( conffile );
    RelayConfigs* cfg = RelayConfigs::GetConfigs();

    if ( NULL != logFile ) {
        cfg->SetValueFor( "LOGFILE_PATH", logFile );
    }

    if ( ctrlport == 0 ) {
        (void)cfg->GetValueFor( "CTLPORT", &ctrlport );
    }
    if ( -1 == udpport ) {
        (void)cfg->GetValueFor( "UDP_PORT", &udpport );
    }
#ifdef DO_HTTP
    if ( httpport == 0 ) {
        (void)cfg->GetValueFor( "WWW_PORT", &httpport );
    }
#endif
    if ( nWorkerThreads == 0 ) {
        (void)cfg->GetValueFor( "NTHREADS", &nWorkerThreads );
    }
    if ( g_maxsocks == -1 && !cfg->GetValueFor( "MAXSOCKS", &g_maxsocks ) ) {
        g_maxsocks = 100;
    }
    char serverNameBuf[128];
    if ( serverName == NULL ) {
        if ( cfg->GetValueFor( "SERVERNAME", serverNameBuf, 
                               sizeof(serverNameBuf) ) ) {
            serverName = serverNameBuf;
        }
    }

#ifdef DO_HTTP
    /* http module uses this */
    if ( !!cssFile ) {
        cfg->SetValueFor( "WWW_CSS_PATH", cssFile );
    }
#endif

    PermID::SetServerName( serverName );
    /* add signal handling here */

    /*
      The daemon() function is for programs wishing to detach themselves from
      the controlling terminal and run in the background as system daemons.
 
      Unless the argument nochdir is non-zero, daemon() changes the current
      working directory to the root ("/").
 
      Unless the argument noclose is non-zero, daemon() will redirect standard
      input, standard output and standard error to /dev/null.

      (This function forks, and if the fork() succeeds, the parent does
      _exit(0), so that further errors are seen by the child only.)  On
      success zero will be returned.  If an error occurs, daemon() returns -1
      and sets the global variable errno to any of the errors specified for
      the library functions fork(2) and setsid(2).
    */
    if ( doDaemon ) {
        if ( 0 != daemon( true, false ) ) {
            logf( XW_LOGERROR, "dev() => %s", strerror(errno) );
            exit( -1 );
        }
    }

#ifdef SPAWN_SELF
    /* loop forever, relaunching children as they die. */
    while ( doFork && !maint_str ) {
        ++s_nSpawns;             /* increment in parent *before* copy */
        pid_t pid = fork();
        if ( pid == 0 ) {       /* child */
            break;
        } else if ( pid > 0 ) {
            int status;
            logf( XW_LOGINFO, "parent waiting on child pid=%d", pid );
            time_t time_before = time( NULL );
            waitpid( pid, &status, 0 );
            printWhy( status );
            time_t time_after = time( NULL );
            doFork = time_after > time_before;
            if ( !doFork ) {
                logf( XW_LOGERROR, "exiting b/c respawned too quickly" );
            }
        } else {
            logf( XW_LOGERROR, "fork() => %s", strerror(errno) );
        }
    }
#endif

    if ( -1 != udpport ) {
        struct sockaddr_in saddr;
        g_udpsock = socket( AF_INET, SOCK_DGRAM, IPPROTO_UDP );
        saddr.sin_family = PF_INET;
        saddr.sin_addr.s_addr = htonl(INADDR_ANY);
        saddr.sin_port = htons(udpport);
        int err = bind( g_udpsock, (struct sockaddr*)&saddr, sizeof(saddr) );
        if ( 0 == err ) {
            err = fcntl( g_udpsock, F_SETFL, O_NONBLOCK );
        } else {
            logf( XW_LOGERROR, "bind()=>%s", strerror(errno) );
            g_udpsock = -1;
        }
    }

    if ( !!maint_str ) {
        maint_str_loop( g_udpsock, maint_str );
        exit( 1 );              // should never exit
    }

    /* Needs to be reset after a crash/respawn */
    PermID::SetStartTime( time(NULL) );

    logf( XW_LOGERROR, "***** forked %dth new process *****", s_nSpawns );

    /* Arrange to be sent SIGUSR1 on death of parent. */
    prctl( PR_SET_PDEATHSIG, SIGUSR1 );

    struct sigaction sact;
    memset( &sact, 0, sizeof(sact) );
    sact.sa_handler = parentDied;
    (void)sigaction( SIGUSR1, &sact, NULL );

    memset( &sact, 0, sizeof(sact) );
    sact.sa_handler = handlePipe;
    (void)sigaction( SIGPIPE, &sact, NULL );

    if ( port != 0 ) {
        g_listeners.AddListener( port, true );
    }
    vector<int> ints_game;
    if ( !cfg->GetValueFor( "GAME_PORTS", ints_game ) ) {
        exit( 1 );
    }

    DBMgr::Get()->ClearCIDs();  /* get prev boot's state in db */

    vector<int>::const_iterator iter_game;
    for ( iter_game = ints_game.begin(); iter_game != ints_game.end(); 
          ++iter_game ) {
        int port = *iter_game;
        if ( !g_listeners.PortInUse( port ) ) {
            if ( !g_listeners.AddListener( port, true ) ) {
                exit( 1 );
            }
        } else {
            logf( XW_LOGERROR, "port %d was in use", port );
        }
    }

    vector<int> ints_device;
    if ( cfg->GetValueFor( "DEVICE_PORTS", ints_device ) ) {

        vector<int>::const_iterator iter;
        for ( iter = ints_device.begin(); iter != ints_device.end(); ++iter ) {
            int port = *iter;
            if ( !g_listeners.PortInUse( port ) ) {
                if ( !g_listeners.AddListener( port, false ) ) {
                    exit( 1 );
                }
            } else {
                logf( XW_LOGERROR, "port %d was in use", port );
            }
        }
    }

    g_control = make_socket( INADDR_LOOPBACK, ctrlport );
    if ( g_control == -1 ) {
        exit( 1 );
    }

#ifdef DO_HTTP
    HttpState http_state;
    int addr;

    memset( &http_state, 0, sizeof(http_state) );
    if ( cfg->GetValueFor( "WWW_SAMPLE_INTERVAL", 
                           &http_state.m_sampleInterval )
         && cfg->GetValueFor( "WWW_LISTEN_ADDR", &addr ) ) {
        g_http = make_socket( addr, httpport );
        if ( g_http == -1 ) {
            exit( 1 );
        }
        http_state.ctrl_sock = g_http;
    }
    if ( -1 != g_http ) {
        pthread_mutex_init( &http_state.m_dataMutex, NULL );
    }
#endif

    struct sigaction act;
    memset( &act, 0, sizeof(act) );
    act.sa_handler = SIGINT_handler;
    (void)sigaction( SIGINT, &act, NULL );

    XWThreadPool* tPool = XWThreadPool::GetTPool();
    tPool->Setup( nWorkerThreads, killSocket );

    /* set up select call */
    fd_set rfds;
    for ( ; ; ) {
        FD_ZERO(&rfds);
        g_listeners.AddToFDSet( &rfds );
        FD_SET( g_control, &rfds );
        if ( -1 != g_udpsock ) {
            FD_SET( g_udpsock, &rfds );
        }
#ifdef DO_HTTP
        if ( -1 != g_http ) {
            FD_SET( g_http, &rfds );
        }
#endif
        int highest = g_listeners.GetHighest();
        if ( g_control > highest ) {
            highest = g_control;
        }
        if ( g_udpsock > highest ) {
            highest = g_udpsock;
        }
#ifdef DO_HTTP
        if ( g_http > highest ) {
            highest = g_http;
        }
#endif
        ++highest;

        int retval = select( highest, &rfds, NULL, NULL, NULL );
        if ( retval < 0 ) {
            if ( errno != 4 ) { /* 4's what we get when signal interrupts */
                logf( XW_LOGINFO, "errno: %s (%d)", strerror(errno), errno );
            }
        } else {
            ListenersIter iter(&g_listeners, true);
            while ( retval > 0 ) {
                bool perGame;
                int listener = iter.next( &perGame );
                if ( listener < 0 ) {
                    break;
                }

                if ( FD_ISSET( listener, &rfds ) ) {
                    AddrInfo::AddrUnion saddr;
                    socklen_t siz = sizeof(saddr.u.addr_in);
                    int newSock = accept( listener, &saddr.u.addr, &siz );
                    if ( newSock < 0 ) {
                        logf( XW_LOGERROR, "accept failed: errno(%d)=%s",
                              errno, strerror(errno) );
                        assert( 0 ); // we're leaking files or load has grown
                    } else {
                        // I've seen a bug where we accept but never service
                        // connections.  Sockets are not closed, and so the
                        // number goes up.  Probably need a watchdog instead,
                        // but this will work around it.
                        assert( g_maxsocks > newSock );

                        /* Set timeout so send and recv won't block forever */
                        // set_timeouts( newSock );

                        int err = fcntl( newSock, F_SETFL, O_NONBLOCK );
                        assert( 0 == err );
                        enable_keepalive( newSock );

                        logf( XW_LOGINFO, 
                              "%s: accepting connection from %s on socket %d", 
                              __func__, inet_ntoa(saddr.u.addr_in.sin_addr), newSock );

                        AddrInfo addr( newSock, &saddr, true );
                        tPool->AddSocket( perGame ? XWThreadPool::STYPE_GAME
                                          : XWThreadPool::STYPE_PROXY,
                                          perGame ? game_thread_proc
                                          : proxy_thread_proc,
                                          &addr );
                        UdpQueue::get()->newSocket( &addr );
                    }
                    --retval;
                }
            }
            if ( FD_ISSET( g_control, &rfds ) ) {
                run_ctrl_thread( g_control );
                --retval;
            }
            if ( -1 != g_udpsock && FD_ISSET( g_udpsock, &rfds ) ) {
                // This will need to be done in a separate thread, or pushed
                // to the existing thread pool
                read_udp_packet( g_udpsock );
                --retval;
            }
#ifdef DO_HTTP
            if ( FD_ISSET( g_http, &rfds ) ) {
                FD_CLR( g_http, &rfds );
                run_http_thread( &http_state );
                --retval;
            }
#endif
            assert( retval == 0 );
        }
    }

    g_listeners.RemoveAll();
    close( g_control );

    delete cfg;

    return 0;
} // main
