

#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <assert.h>
#include <arpa/inet.h>

#include "wasmutls.h"

typedef bool XP_Bool;

typedef int8_t XP_S8;
typedef uint8_t XP_U8;
typedef int16_t XP_S16;
typedef uint16_t XP_U16;
typedef int32_t XP_S32;
typedef uint32_t XP_U32;

typedef char XP_UCHAR;

typedef void* XWEnv;

#define XP_TRUE  ((XP_Bool)(1==1))
#define XP_FALSE ((XP_Bool)(1==0))



#define XP_MEMSET(src, val, nbytes)     memset( (src), (val), (nbytes) )
#define XP_MEMCPY(d,s,l) memcpy((d),(s),(l))
#define XP_MEMMOVE(d,s,l) memmove((d),(s),(l))
#define XP_MEMCMP( a1, a2, l )  memcmp((a1),(a2),(l))
#define XP_STRLEN(s) strlen(s)
#define XP_STRCAT(d,s) strcat((d),(s))
#define XP_STRNCMP(s1,s2,len) strncmp((s1),(s2),(len))
#define XP_STRNCPY(s1,s2,len) strncpy((s1),(s2),(len))
#define XP_STRCMP(s1,s2)       strcmp((s1),(s2))

#ifdef MEM_DEBUG

# define XP_PLATMALLOC(nbytes)       malloc(nbytes)
# define XP_PLATREALLOC(p,s)         realloc((p),(s))
# define XP_PLATFREE(p)              free(p)

#else

# define XP_MALLOC(pool,nbytes)       malloc(nbytes)
# define XP_CALLOC(pool,nbytes)       calloc(1,nbytes)
# define XP_REALLOC(pool,p,s)         realloc((p),(s))
# define XP_FREE(pool,p)              free(p)
void linux_freep( void** ptrp );
# define XP_FREEP(pool,p)             linux_freep((void**)p)
#endif

#ifdef DEBUG
extern void linux_debugf(const char*, ...)
    __attribute__ ((format (printf, 1, 2)));
# define XP_DEBUGF(...) wasm_debugf(__VA_ARGS__)

extern void linux_debugff(const char* func, const char* file, const char* fmt, ...)
    __attribute__ ((format (printf, 3, 4)));
# define XP_LOGFF( FMT, ... ) \
    wasm_debugff( __func__, __FILE__, FMT, ##__VA_ARGS__ )
#define XP_LOG(STR) \
    wasm_debugff( __func__, __FILE__, "%s", STR )

#else
# define XP_DEBUGF(ch,...)
# define XP_LOGFF(fmt,...)
# define XP_LOG(fmt)
#endif

#ifdef DEBUG
# define XP_ASSERT(B) do { if (!(B)) { XP_LOGFF( "firing assert"); } assert(B); } while (0)
void linux_backtrace( void );
# define XP_BACKTRACE linux_backtrace
#else
# define XP_ASSERT(b)
# define XP_BACKTRACE
#endif

#define XP_STATUSF XP_DEBUGF
#define XP_LOGF XP_DEBUGF
#define XP_SNPRINTF snprintf
#define XP_WARNF XP_DEBUGF

#define XP_L(s) s
#define XP_S XP_L("%s")
#define XP_CR XP_L("\n")

#define XP_RANDOM() random()

#define XP_NTOHL(l) ntohl(l)
#define XP_NTOHS(s) ntohs(s)
#define XP_HTONL(l) htonl(l)
#define XP_HTONS(s) htons(s)

#define XP_MIN(a,b) ((a)<(b)?(a):(b))
#define XP_MAX(a,b) ((a)>(b)?(a):(b))
#define XP_ABS(a)   ((a)>=0?(a):-(a))

#define XP_LD "%d"
#define XP_P "%p"
