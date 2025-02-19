#!/usr/bin/env python3

import re, os, sys, shutil, threading, requests, json, glob
import argparse, datetime, random, signal, subprocess, time
from shutil import rmtree

# LOGDIR=./$(basename $0)_logs
# APP_NEW=""
# DO_CLEAN=""
# APP_NEW_PARAMS=""
# NGAMES = 1
g_UDP_PCT_START = 100
gDeadLaunches = 0
# UDP_PCT_INCR=10
# UPGRADE_ODDS=""
# NROOMS=""
# HOST=""
# PORT=""
# TIMEOUT=""
# SAVE_GOOD=""
# MINDEVS=""
# MAXDEVS=""
# ONEPER=""
# RESIGN_PCT=0
g_DROP_N=0
# MINRUN=2		                # seconds
# ONE_PER_ROOM=""                 # don't run more than one device at a time per room
# USE_GTK=""
# UNDO_PCT=0
# ALL_VIA_RQ=${ALL_VIA_RQ:-FALSE}
# SEED=""
# BOARD_SIZES_OLD=(15)
# BOARD_SIZES_NEW=(15)
g_NAMES = [None, 'Brynn', 'Ariela', 'Kati', 'Eric']
# SEND_CHAT=''
# CORE_COUNT=$(ls core.* 2>/dev/null | wc -l)
# DUP_PACKETS=''
# HTTP_PCT=0

# declare -A PIDS
# declare -A APPS
# declare -A NEW_ARGS
# declare -a ARGS
# declare -A ARGS_DEVID
# declare -A ROOMS
# declare -A FILES
# declare -A LOGS
# declare -A MINEND
# ROOM_PIDS = {}
# declare -a APPS_OLD=()
# declare -a DICTS=				# wants to be =() too?
# declare -A CHECKED_ROOMS

# function cleanup() {
#     APP="$(basename $APP_NEW)"
#     while pidof $APP; do
#         echo "killing existing $APP instances..."
#         killall -9 $APP
#         sleep 1
#     done
#     echo "cleaning everything up...."
#     if [ -d $LOGDIR ]; then
#         mv $LOGDIR /tmp/${LOGDIR}_$$
#     fi
#     if [ -e $(dirname $0)/../../relay/xwrelay.log ]; then
#         mkdir -p /tmp/${LOGDIR}_$$
#         mv $(dirname $0)/../../relay/xwrelay.log /tmp/${LOGDIR}_$$
#     fi

#     echo "DELETE FROM games WHERE room LIKE 'ROOM_%';" | psql -q -t xwgames
#     echo "DELETE FROM msgs WHERE NOT devid in (SELECT unnest(devids) from games);" | psql -q -t xwgames
# }

# function connName() {
#     LOG=$1
#     grep -a 'got_connect_cmd: connName' $LOG | \
#         tail -n 1 | \
#         sed 's,^.*connName: \"\(.*\)\" (reconnect=.)$,\1,'
# }

# function check_room() {
#     ROOM=$1
#     if [ -z ${CHECKED_ROOMS[$ROOM]:-""} ]; then
#         NUM=$(echo "SELECT COUNT(*) FROM games "\
#             "WHERE NOT dead "\
#             "AND ntotal!=sum_array(nperdevice) "\
#             "AND ntotal != -sum_array(nperdevice) "\
#             "AND room='$ROOM'" |
#             psql -q -t xwgames)
#         NUM=$((NUM+0))
#         if [ "$NUM" -gt 0 ]; then
#             echo "$ROOM in the DB has unconsummated games.  Remove them."
#             exit 1
#         else
#             CHECKED_ROOMS[$ROOM]=1
#         fi
#     fi
# }

# print_cmdline() {
#     local COUNTER=$1
#     local LOG=${LOGS[$COUNTER]}
#     echo -n "New cmdline: " >> $LOG
#     echo "${APPS[$COUNTER]} ${NEW_ARGS[$COUNTER]} ${ARGS[$COUNTER]}" >> $LOG
# }

def pick_ndevs(args):
    RNUM = random.randint(0, 99)
    if RNUM > 95 and args.MAXDEVS >= 4:
        NDEVS = 4
    elif RNUM > 90 and args.MAXDEVS >= 3:
        NDEVS = 3
    else:
        NDEVS = 2
    if NDEVS < args.MINDEVS:
        NDEVS = args.MINDEVS
    return NDEVS

# # Given a device count, figure out how many local players per device.
# # "1 1" would be a two-device game with 1 each.  "1 2 1" a
# # three-device game with four players total
def figure_locals(args, NDEVS):
    NPLAYERS = pick_ndevs(args)
    if NPLAYERS < NDEVS: NPLAYERS = NDEVS
    
    EXTRAS = 0
    if not args.ONEPER:
        EXTRAS = NPLAYERS - NDEVS

    LOCALS = []
    for IGNORE in range(NDEVS):
         COUNT = 1
         if EXTRAS > 0:
             EXTRA = random.randint(0, EXTRAS)
             if EXTRA > 0:
                 COUNT += EXTRA
                 EXTRAS -= EXTRA
         LOCALS.append(COUNT)
    assert 0 < sum(LOCALS) <= 4
    return LOCALS

def player_params(args, NLOCALS, NPLAYERS, NAME_INDX):
    assert 0 < NPLAYERS <= 4
    NREMOTES = NPLAYERS - NLOCALS
    PARAMS = []
    while NLOCALS > 0 or NREMOTES > 0:
        if 0 == random.randint(0, 2) and 0 < NLOCALS:
            PARAMS += ['--robot',  g_NAMES[NAME_INDX]]
            if not args.IQS_SAME:
                PARAMS += ['--robot-iq', str(random.randint(1,100))]
            NLOCALS -= 1
            NAME_INDX += 1
        elif 0 < NREMOTES:
            PARAMS += ['--remote-player']
            NREMOTES -= 1
    return PARAMS

def logReaderStub(dev): dev.logReaderMain()

class Device():
    sHasLDevIDMap = {}
    # sConnNamePat = re.compile('.*got_connect_cmd: connName: "([^"]+)".*$')
    sWinnerPat = re.compile('^\[(\#\d|Winner)\] (.*): (\d+)')
    sMsgCountPat = re.compile('.*curses_countChanged.*\(newCount=(\d+)\).*')
    sTilesLeftPoolPat = re.compile('.*pool_r.*Tiles: (\d+) tiles left in pool')
    sTilesLeftTrayPat = re.compile('.*player \d+ now has (\d+) tiles')
    sRelayIDPat = re.compile('.*UPDATE games.*seed=(\d+),.*relayid=\'([^\']+)\'.*')
    sDevIDPat = re.compile('.*storing new devid: ([\da-fA-F]+).*')
    sMQTTDevIDPat = re.compile('.*getMQTTDevID.*: generated id: ([\d[A-F]+).*')
    sConnPat = re.compile('.*linux_util_informMissing\(isServer.*nMissing=0\).*')

    sScoresDup = []
    sScoresReg = []
    
    def __init__(self, args, game, indx, params, room, peers, order,
                 db, log, script, nInGame, inDupMode):
        self.game = game
        self.indx = indx
        self.args = args
        self.pid = 0
        self.winnerFound = False
        self.gameOver = False
        self.params = params
        self.room = room
        self.order = order
        self.db = db
        self.logPath = log
        self.script = script
        self.nInGame = nInGame
        self.inDupMode = inDupMode
        # runtime stuff; init now
        self.app = args.APP_OLD
        self.proc = None
        self.peers = peers
        self.devID = ''
        self.launchCount = 0
        self.allDone = False    # when true, can be killed
        self.nTilesLeftPool = None
        self.nTilesLeftTray = None
        self.relayID = None
        self.inviteeDevID = None
        self.inviteeDevIDs = [] # only servers use this
        self.inviteeMQTTDevID = None
        self.inviteeMQTTDevIDs = []
        self.connected = False
        self.relaySeed = 0
        self.locked = False
        self.msgCount = -1

        self.setApp(args.START_PCT)

        with open(self.logPath, "w") as log:
            log.write('New cmdline: ' + self.app + ' ' + (' '.join([str(p) for p in self.params])))
            log.write(os.linesep)

    def setApp(self, pct):
        if self.app == self.args.APP_OLD and not self.app == self.args.APP_NEW:
            if os.path.exists(self.script) and pct > random.randint(0, 99):
                print('launch(): upgrading {} from {} to {}' \
                      .format(self.devName(), self.app, self.args.APP_NEW))
                self.app = self.args.APP_NEW
                # nuke script to force regeneration
                os.unlink(self.script)

    def devName(self):
        return 'dev_' + str(self.indx)

    def logReaderMain(self):
        assert self and self.proc
        # print('logReaderMain called; opening:', self.logPath)
        stdout, stderr = self.proc.communicate()
        nLines = 0
        with open(self.logPath, 'a') as log:
            for line in stderr.splitlines():
                nLines += 1
                log.write(line + os.linesep)

                self.locked = True

                match = Device.sMsgCountPat.match(line)
                if match:
                    self.msgCount = int(match.group(1))

                # check for game over
                if not self.winnerFound:
                    match = Device.sWinnerPat.match(line)
                    if match:
                        self.winnerFound = True
                        score = int(match.group(3))
                        if self.inDupMode:
                            Device.sScoresDup.append(score)
                        else:
                            Device.sScoresReg.append(score)

                if not self.gameOver and self.winnerFound and 0 == self.msgCount:
                    self.gameOver = True

                # Check every line for tiles left in pool
                match = Device.sTilesLeftPoolPat.match(line)
                if match: self.nTilesLeftPool = int(match.group(1))

                # Check every line for tiles left in tray
                match = Device.sTilesLeftTrayPat.match(line)
                if match: self.nTilesLeftTray = int(match.group(1))

                if not self.relayID:
                    match = Device.sRelayIDPat.match(line)
                    if match:
                        self.relaySeed = int(match.group(1))
                        self.relayID = match.group(2)

                if self.args.WITH_MQTT and not self.inviteeMQTTDevID:
                    match = Device.sMQTTDevIDPat.match(line)
                    if match:
                        self.inviteeMQTTDevID = int(match.group(1), 16)
                        # print('read mqtt devid: {:16X}'.format(self.inviteeMQTTDevID))

                if not self.connected:
                    match = Device.sConnPat.match(line)
                    if match: self.connected = True

                self.locked = False

        # print('logReaderMain done, wrote lines:', nLines, 'to', self.logPath);

    def checkScript(self):
        if not os.path.exists(self.script):
            args = ['exec']     # without exec means terminate() won't work
            if self.args.VALGRIND:
                args += ['valgrind']
                # args += ['--leak-check=full']
                # args += ['--track-origins=yes']
            args += [self.app] + [str(p) for p in self.params]
            if self.devID: args.extend( ' '.split(self.devID))
            args += [ '$*' ]
            with open( self.script, 'w' ) as fil:
                fil.write( "#!/bin/sh\n" )
                fil.write( ' '.join(args) + '\n' )
            os.chmod(self.script, 0o755)

    def launch(self):
        self.setApp(self.args.UPGRADE_PCT)
        self.checkScript()
        self.launchCount += 1
        args = [ self.script, '--close-stdin' ]

        # If I'm an unconnected server and I know a client's relayid,
        # append it so invitation can happen. When more than one
        # device will be invited, the invitations must always go in
        # the same order so channels will be assigned consistently. So
        # keep them in an array as they're encountered, and use in
        # that order
        if self.args.WITH_MQTT:
            if self.order == 1 and not self.connected:
                for peer in self.peers:
                    if peer.inviteeMQTTDevID and not peer == self:
                        if not peer.inviteeMQTTDevID in self.inviteeMQTTDevIDs:
                            self.inviteeMQTTDevIDs.append(peer.inviteeMQTTDevID)
                if self.inviteeMQTTDevIDs:
                    args += [ '--force-invite' ]
                    for idid in self.inviteeMQTTDevIDs:
                        asHexStr = '{:16X}'.format(idid)
                        args += ['--invitee-mqtt-devid', asHexStr]

        self.proc = subprocess.Popen(args, stdout = subprocess.DEVNULL,
                                     stderr = subprocess.PIPE, universal_newlines = True)
        self.pid = self.proc.pid
        self.minEnd = datetime.datetime.now() + datetime.timedelta(seconds = self.args.MINRUN)

        # Now start a thread to read stdio
        self.reader = threading.Thread(target = logReaderStub, args=(self,))
        self.reader.isDaemon = True
        self.reader.start()

    def running(self):
        return self.proc and not self.proc.poll()

    def minTimeExpired(self):
        assert self.proc
        return self.minEnd < datetime.datetime.now()
        
    def kill(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait()
            assert self.proc.poll() is not None

            self.reader.join()
            self.reader = None
        else:
            print('NOT killing')
        self.proc = None
        self.check_game_over()

    def handleAllDone(self):
        global gDeadLaunches
        if self.allDone:
            self.moveFiles()
            gDeadLaunches += self.launchCount
        return self.allDone

    def moveFiles(self):
        assert not self.running()
        for fil in [ self.logPath, self.db, self.script ]:
            shutil.move(fil, self.args.LOGDIR + '/done')

    def getTilesCount(self):
        assert not self.locked
        return {'index': self.indx,
                'nTilesLeftPool': self.nTilesLeftPool,
                'nTilesLeftTray': self.nTilesLeftTray,
                'launchCount': self.launchCount,
                'game': self.game,
        }

    def update_ldevid(self):
        if not self.app in Device.sHasLDevIDMap:
            hasLDevID = False
            proc = subprocess.Popen([self.app, '--help'], stderr=subprocess.PIPE)
            # output, err, = proc.communicate()
            for line in proc.stderr.readlines():
                if b'--ldevid' in line:
                    hasLDevID = True
                    break
            # print('found --ldevid:', hasLDevID);
            Device.sHasLDevIDMap[self.app] = hasLDevID

        if Device.sHasLDevIDMap[self.app]:
            RNUM = random.randint(0, 99)
            if not self.devID:
                if RNUM < 30:
                    self.devID = '--ldevid LINUX_TEST_%.5d_' % (self.indx)
            elif RNUM < 10:
                self.devID += 'x'

    def check_game_over(self):
        if self.gameOver and not self.allDone:
            allDone = True
            for dev in self.peers:
                if dev == self: continue
                if not dev.gameOver:
                    allDone = False
                    break

            if allDone:
                for dev in self.peers:
                    assert self.game == dev.game
                    dev.allDone = True

def makeSMSPhoneNo( game, dev ):
    return '{:03d}{:03d}'.format( game, dev )

def build_cmds(args):
    devs = []
    COUNTER = 0

    for GAME in range(1, args.NGAMES + 1):
        peers = set()
        ROOM = 'ROOM_%.3d' % (GAME % args.NROOMS)
        NDEVS = pick_ndevs(args)
        LOCALS = figure_locals(args, NDEVS) # as array
        NPLAYERS = sum(LOCALS)
        assert(len(LOCALS) == NDEVS)
        DICT = args.DICTS[GAME % len(args.DICTS)]
        # make one in three games public
        useDupeMode = random.randint(0, 100) < args.DUP_PCT
        if args.PHONIES == -1: phonies = GAME % 3
        else: phonies = args.PHONIES
        DEV = 0
        for NLOCALS in LOCALS:
            DEV += 1
            DB = '{}/{:02d}_{:02d}_DB.sql3'.format(args.LOGDIR, GAME, DEV)
            LOG = '{}/{:02d}_{:02d}_LOG.txt'.format(args.LOGDIR, GAME, DEV)
            SCRIPT = '{}/start_{:02d}_{:02d}.sh'.format(args.LOGDIR, GAME, DEV)

            PARAMS = player_params(args, NLOCALS, NPLAYERS, DEV)
            if not args.USE_GTK: PARAMS += ['--curses']
            PARAMS += ['--board-size', '15', '--sort-tiles']
            if not useDupeMode: PARAMS += ['--trade-pct', args.TRADE_PCT]

            # We SHOULD support having both SMS and relay working...
            if args.WITH_SMS:
                PARAMS += [ '--sms-number', makeSMSPhoneNo(GAME, DEV) ]
                if args.SMS_FAIL_PCT > 0:
                    PARAMS += [ '--sms-fail-pct', args.SMS_FAIL_PCT ]
                if DEV == 1:
                    PARAMS += [ '--force-invite' ]
                    for dev in range(2, NDEVS + 1):
                        PARAMS += [ '--invitee-sms-number', makeSMSPhoneNo(GAME, dev) ]

            PARAMS += [ '--mqtt-port', args.MQTT_PORT, '--mqtt-host', args.MQTT_HOST ]
            if args.WITH_MQTT:
                PARAMS += [ '--with-mqtt' ]
                if DEV == 1:
                    PARAMS += [ '--force-invite' ]
            else:
                PARAMS += [ '--without-mqtt' ]

            if args.UNDO_PCT > 0:
                PARAMS += ['--undo-pct', args.UNDO_PCT]
            PARAMS += [ '--game-dict', DICT]
            # Removing --slow-robot for now. With it on (and
            # successfully passed through to the curses client, which
            # hasn't always been happening), 20% of games
            # stall. PENDING...
            # PARAMS += ['--slow-robot', '1:3']
            PARAMS += ['--skip-confirm']
            PARAMS += ['--db', DB]

            PARAMS += ['--drop-nth-packet', g_DROP_N]
            PARAMS += ['--split-packets', '2']
            if args.SEND_CHAT:
                PARAMS += ['--send-chat', args.SEND_CHAT]

            if args.DUP_PACKETS:
                PARAMS += ['--dup-packets']
            # PARAMS += ['--my-port', '1024']
            # PARAMS += ['--savefail-pct', 10]

            # With the --seed param passed, games with more than 2
            # devices don't get going. No idea why. This param is NOT
            # passed in the old bash version of this script, so fixing
            # it isn't a priority.
            # PARAMS += ['--seed', args.SEED]

            if DEV == 1:
                PARAMS += ['--force-game']
                PARAMS += ['--server', '--phonies', phonies ]
                if 0 == args.TRAYSIZE: traySize = random.randint(7, 9)
                else: traySize = args.TRAYSIZE
                PARAMS += ['--tray-size', traySize] # randint() is *inclusive*
                # IFF there are any non-1 player counts, tell inviter which
                if sum(LOCALS) > NDEVS:
                    PARAMS += ['--invitee-counts', ":".join(str(n) for n in LOCALS[1:])]
            else:
                PARAMS += ['--force-channel', DEV - 1]
            if args.PHONY_PCT and phonies == 2: PARAMS += [ '--make-phony-pct', args.PHONY_PCT ]

            if useDupeMode: PARAMS += ['--duplicate-mode']

            PARAMS += ['--board-size', args.BOARD_SIZE]

            # print('PARAMS:', PARAMS)

            dev = Device( args, GAME, COUNTER, PARAMS, ROOM, peers,
                          DEV, DB, LOG, SCRIPT, len(LOCALS), useDupeMode )
            peers.add(dev)
            dev.update_ldevid()
            devs.append(dev)

            COUNTER += 1
    return devs

def summarizeTileCounts(devs, endTime, state, changeSecs):
    global gDeadLaunches
    shouldGoOn = True
    data = [dev.getTilesCount() for dev in devs]
    dupModeFlags = [dev.inDupMode for dev in devs]
    nDevs = len(data)
    totalTilesStd = 0
    totalTilesDup = 0
    colWidth = max(2, len(str(nDevs)))
    headWidth = 0
    fmtData = [{'head' : 'dev', },
               {'head' : 'launches', },
               {'head' : 'tls left', },
    ]
    for datum in fmtData:
        headWidth = max(headWidth, len(datum['head']))
        datum['data'] = []

    # Group devices by game
    games = []
    joinChars = []
    prev = -1
    for datum, inDupMode in zip(data, dupModeFlags):
        gameNo = datum['game']
        if gameNo != prev:
            games.append([])
            if inDupMode: joinChars.append('.')
            else: joinChars.append('+')
            prev = gameNo
        games[-1].append('{:0{width}d}'.format(datum['index'], width=colWidth))

    fmtData[0]['data'] = []
    for game, joinChar in zip(games, joinChars):
        fmtData[0]['data'].append( joinChar.join(game) )

    nLaunches = gDeadLaunches
    for datum, inDupMode in zip(data, dupModeFlags):
        launchCount = datum['launchCount']
        nLaunches += launchCount
        fmtData[1]['data'].append('{:{width}d}'.format(launchCount, width=colWidth))

        # Format tiles left. It's the number in the bag/pool until
        # that drops to 0, then the number in the tray preceeded by
        # '+'. Only the pool number is included in the totalTiles sum.
        nTilesPool = datum['nTilesLeftPool']
        nTilesTray = datum['nTilesLeftTray']
        if nTilesPool is None and nTilesTray is None:
            txt = ('-' * colWidth)
        elif int(nTilesPool) == 0 and not nTilesTray is None:
            txt = '{:+{width}d}'.format(nTilesTray, width=colWidth-1)
        else:
            txt = '{:{width}d}'.format(nTilesPool, width=colWidth)
            if inDupMode: totalTilesDup += int(nTilesPool)
            else: totalTilesStd += int(nTilesPool)
        fmtData[2]['data'].append(txt)

    print('')
    if totalTilesDup: dupDetails = ' (std: {}, dup: {})'.format(totalTilesStd, totalTilesDup)
    else: dupDetails = ''
    # here
    print('devs left: {nDevs}; bag tiles left: {total}{details}; total launches: {nLaunches}; {now}/{endTime}' \
          .format(nDevs=nDevs, total=totalTilesStd + totalTilesDup, details=dupDetails, \
                  nLaunches=nLaunches, now=datetime.datetime.now(), endTime=endTime ))
    fmt = '{head:>%d} {data}' % headWidth
    for datum in fmtData: datum['data'] = ' '.join(datum['data'])
    for datum in fmtData:
        print(fmt.format(**datum))

    # Now let's see if things are stuck: if the tile string hasn't
    # changed in two minutes bail. Note that the count of tiles left
    # isn't enough because it's zero for a long time as devices are
    # using up what's left in their trays and getting killed.
    now = datetime.datetime.now()
    tilesStr = fmtData[2]['data']
    if not 'tilesStr' in state or state['tilesStr'] != tilesStr:
        state['lastChange'] = now
        state['tilesStr'] = tilesStr

    return now - state['lastChange'] < datetime.timedelta(seconds = changeSecs)

def countCores(args):
    count = 0
    if args.CORE_PAT:
        count = len( glob.glob(args.CORE_PAT) )
    return count

gDone = False

def run_cmds(args, devs):
    nCores = countCores(args)
    endTime = datetime.datetime.now() + datetime.timedelta(minutes = args.TIMEOUT_MINS)
    printState = {}
    lastPrint = datetime.datetime.now()

    while len(devs) > 0 and not gDone:
        if countCores(args) > nCores:
            print('core file count increased; exiting')
            break
        now = datetime.datetime.now()
        if now > endTime:
            print('outta time; outta here')
            break

        # print stats every 5 seconds
        if now - lastPrint > datetime.timedelta(seconds = 5):
            lastPrint = now
            if not summarizeTileCounts(devs, endTime, printState, args.NO_CHANGE_SECS):
                print('no change in too long; exiting')
                break

        dev = random.choice(devs)
        if not dev.running():
            if dev.handleAllDone():
                devs.remove(dev)
            else:
                dev.launch()
        elif dev.minTimeExpired():
            dev.kill()
            if dev.handleAllDone():
                devs.remove(dev)
        else:
            time.sleep(1.0)
        print('.', end='', flush=True)

    # if we get here via a break, kill any remaining games
    if devs:
        print('stopping {} remaining games'.format(len(devs)))
        for dev in devs:
            if dev.running(): dev.kill()

# run_via_rq() {
#     # launch then kill all games to give chance to hook up
#     for KEY in ${!ARGS[*]}; do
#         echo "launching $KEY"
#         launch $KEY &
#         PID=$!
#         sleep 1
#         kill $PID
#         wait $PID
#         # add_pipe $KEY
#     done

#     echo "now running via rq"
#     # then run them
#     while :; do
#         COUNT=${#ARGS[*]}
#         [ 0 -ge $COUNT ] && break

#         INDX=$(($RANDOM%COUNT))
#         KEYS=( ${!ARGS[*]} )
#         KEY=${KEYS[$INDX]}
#         CMD=${ARGS[$KEY]}
            
#         RELAYID=$(./scripts/relayID.sh --short ${LOGS[$KEY]})
#         MSG_COUNT=$(../relay/rq -a $HOST -m $RELAYID 2>/dev/null | sed 's,^.*-- ,,')
#         if [ $MSG_COUNT -gt 0 ]; then
#             launch $KEY &
#             PID=$!
#             sleep 2
#             kill $PID || /bin/true
#             wait $PID
#         fi
#         [ "$DROP_N" -ge 0 ] && increment_drop $KEY
#         check_game $KEY
#     done
# } # run_via_rq

# function getArg() {
#     [ 1 -lt "$#" ] || usage "$1 requires an argument"
#     echo $2
# }

def log_scores( devs ):
    if len(Device.sScoresReg) > 0:
        print( "average score for regular games:",
               sum(Device.sScoresReg) // len(Device.sScoresReg) )
    if len(Device.sScoresDup) > 0:
        print( "average score for dup games:",
               sum(Device.sScoresDup) // len(Device.sScoresDup) )

def mkParser():
    parser = argparse.ArgumentParser()
    parser.add_argument('--send-chat', dest = 'SEND_CHAT', type = str, default = None,
                        help = 'the message to send')

    parser.add_argument('--app-new', dest = 'APP_NEW', default = './obj_linux_memdbg/xwords',
                        help = 'the app we\'ll use')
    parser.add_argument('--app-old', dest = 'APP_OLD', default = './obj_linux_memdbg/xwords',
                        help = 'the app we\'ll upgrade from')
    parser.add_argument('--start-pct', dest = 'START_PCT', default = 50, type = int,
                        help = 'odds of starting with the new app, 0 <= n < 100')
    parser.add_argument('--upgrade-pct', dest = 'UPGRADE_PCT', default = 20, type = int,
                        help = 'odds of upgrading at any launch, 0 <= n < 100')

    parser.add_argument('--num-games', dest = 'NGAMES', type = int, default = 1, help = 'number of games')
    parser.add_argument('--num-rooms', dest = 'NROOMS', type = int, default = 0,
                        help = 'number of rooms (default to --num-games)')
    parser.add_argument('--timeout-mins', dest = 'TIMEOUT_MINS', default = 10000, type = int,
                        help = 'minutes after which to timeout')
    parser.add_argument('--nochange-secs', dest = 'NO_CHANGE_SECS', default = 30, type = int,
                        help = 'seconds without change after which to timeout')
    parser.add_argument('--log-root', dest='LOGROOT', default = '.', help = 'where logfiles go')
    parser.add_argument('--dup-packets', dest = 'DUP_PACKETS', default = False, action = 'store_true',
                        help = 'send all packet twice')
    parser.add_argument('--phonies', dest = 'PHONIES', default = -1, type = int,
                        help = '0 (ignore), 1 (warn)) or 2 (lose turn); default is pick at random')
    parser.add_argument('--make-phony-pct', dest = 'PHONY_PCT', default = 20, type = int,
                        help = 'how often a robot should play a phony (only applies when --phonies==2')
    parser.add_argument('--use-gtk', dest = 'USE_GTK', default = False, action = 'store_true',
                        help = 'run games using gtk instead of ncurses')

    parser.add_argument('--dup-pct', dest = 'DUP_PCT', default = 0, type = int,
                        help = 'this fraction played in duplicate mode')

    # # 
    # #     echo "    [--clean-start]                                         \\" >&2
    parser.add_argument('--game-dict', dest = 'DICTS', action = 'append', default = [])
    # #     echo "    [--help]                                                \\" >&2
    # #     echo "    [--max-devs <int>]                                      \\" >&2
    parser.add_argument('--min-devs', dest = 'MINDEVS', type = int, default = 2,
                        help = 'No game will have fewer devices than this')
    parser.add_argument('--max-devs', dest = 'MAXDEVS', type = int, default = 4,
                        help = 'No game will have more devices than this')

    parser.add_argument('--robots-all-same-iq', dest = 'IQS_SAME', default = False,
                        action = 'store_true', help = 'give all robots the same IQ')

    parser.add_argument('--min-run', dest = 'MINRUN', type = int, default = 2,
                        help = 'Keep each run alive at least this many seconds')
    # #     echo "    [--new-app <path/to/app]                                \\" >&2
    # #     echo "    [--new-app-args [arg*]]  # passed only to new app       \\" >&2
    # #     echo "    [--num-rooms <int>]                                     \\" >&2
    # #     echo "    [--old-app <path/to/app]*                               \\" >&2
    parser.add_argument('--one-per', dest = 'ONEPER', default = False,
                        action = 'store_true', help = 'force one player per device')
    parser.add_argument('--resign-pct', dest = 'RESIGN_PCT', default = 0, type = int, \
                        help = 'Odds of resigning [0..100]')
    parser.add_argument('--seed', type = int, dest = 'SEED',
                        default = random.randint(1, 1000000000))
    # #     echo "    [--send-chat <interval-in-seconds>                      \\" >&2
    # #     echo "    [--udp-incr <pct>]                                      \\" >&2
    # #     echo "    [--udp-start <pct>]      # default: $UDP_PCT_START                 \\" >&2
    # #     echo "    [--undo-pct <int>]                                      \\" >&2

    parser.add_argument('--undo-pct', dest = 'UNDO_PCT', default = 0, type = int)
    parser.add_argument('--trade-pct', dest = 'TRADE_PCT', default = 10, type = int)

    parser.add_argument('--with-sms', dest = 'WITH_SMS', action = 'store_true')
    parser.add_argument('--without-sms', dest = 'WITH_SMS', default = False, action = 'store_false')
    parser.add_argument('--sms-fail-pct', dest = 'SMS_FAIL_PCT', default = 0, type = int)

    parser.add_argument('--with-mqtt', dest = 'WITH_MQTT', default = True, action = 'store_true')
    parser.add_argument('--without-mqtt', dest = 'WITH_MQTT', action = 'store_false')
    parser.add_argument('--mqtt-port', dest = 'MQTT_PORT', default = 1883 )
    parser.add_argument('--mqtt-host', dest = 'MQTT_HOST', default = 'localhost' )

    parser.add_argument('--force-tray', dest = 'TRAYSIZE', default = 0, type = int,
                        help = 'Always this many tiles per tray')

    parser.add_argument('--board-size', dest = 'BOARD_SIZE', type = int, default = 15,
                        help = 'Use <n>x<n> size board')

    parser.add_argument('--core-pat', dest = 'CORE_PAT', default = os.environ.get('DISCON_COREPAT'),
                        help = "pattern for core files that should stop the script " \
                        + "(default from env $DISCON_COREPAT)" )

    parser.add_argument('--with-valgrind', dest = 'VALGRIND', default = False,
                        action = 'store_true')

    return parser

# #######################################################
# ##################### MAIN begins #####################
# #######################################################

def parseArgs():
    args = mkParser().parse_args()
    assignDefaults(args)
    print(args)
    return args
    # print(options)

# while [ "$#" -gt 0 ]; do
#     case $1 in
#         --udp-start)
#             UDP_PCT_START=$(getArg $*)
#             shift
#             ;;
#         --udp-incr)
#             UDP_PCT_INCR=$(getArg $*)
#             shift
#             ;;
#         --clean-start)
#             DO_CLEAN=1
#             ;;
#         --num-games)
#             NGAMES=$(getArg $*)
#             shift
#             ;;
#         --num-rooms)
#             NROOMS=$(getArg $*)
#             shift
#             ;;
#         --old-app)
#             APPS_OLD[${#APPS_OLD[@]}]=$(getArg $*)
#             shift
#             ;;
# 		--log-root)
# 			[ -d $2 ] || usage "$1: no such directory $2"
# 			LOGDIR=$2/$(basename $0)_logs
# 			shift
# 			;;
#         --dup-packets)
                  #             DUP_PACKETS=1
#             ;;
#         --new-app)
#             APP_NEW=$(getArg $*)
#             shift
#             ;;
#         --new-app-args)
#             APP_NEW_PARAMS="${2}"
#             echo "got $APP_NEW_PARAMS"
#             shift
#             ;;
#         --game-dict)
#             DICTS[${#DICTS[@]}]=$(getArg $*)
#             shift
#             ;;
#         --min-devs)
#             MINDEVS=$(getArg $*)
#             shift
#             ;;
#         --max-devs)
#             MAXDEVS=$(getArg $*)
#             shift
#             ;;
# 		--min-run)
# 			MINRUN=$(getArg $*)
# 			[ $MINRUN -ge 2 -a $MINRUN -le 60 ] || usage "$1: n must be 2 <= n <= 60"
# 			shift
# 			;;
#         --one-per)
#             ONEPER=TRUE
#             ;;
#         --host)
#             HOST=$(getArg $*)
#             shift
#             ;;
#         --port)
#             PORT=$(getArg $*)
#             shift
#             ;;
#         --seed)
#             SEED=$(getArg $*)
#             shift
#             ;;
#         --undo-pct)
#             UNDO_PCT=$(getArg $*)
#             shift
#             ;;
#         --http-pct)
#             HTTP_PCT=$(getArg $*)
#             [ $HTTP_PCT -ge 0 -a $HTTP_PCT -le 100 ] || usage "$1: n must be 0 <= n <= 100"
#             shift
#             ;;
#         --send-chat)
#             SEND_CHAT=$(getArg $*)
#             shift
#             ;;
#         --resign-pct)
#             RESIGN_PCT=$(getArg $*)
# 			[ $RESIGN_PCT -ge 0 -a $RESIGN_PCT -le 100 ] || usage "$1: n must be 0 <= n <= 100"
#             shift
#             ;;
# 		--no-timeout)
# 			TIMEOUT=0x7FFFFFFF
# 			;;
#         --help)
#             usage
#             ;;
#         *) usage "unrecognized option $1"
#             ;;
#     esac
#     shift
# done

def assignDefaults(args):
    if not args.NROOMS: args.NROOMS = args.NGAMES
    if len(args.DICTS) == 0: args.DICTS.append('CollegeEng_2to8.xwd')
    args.LOGDIR = os.path.splitext(os.path.basename(sys.argv[0]))[0] + '_logs'
    # Move an existing logdir aside
    if os.path.exists(args.LOGDIR):
        shutil.move(args.LOGDIR, '/tmp/' + args.LOGDIR + '_' + str(random.randint(0, 100000)))
    for d in ['', 'done', 'dead',]:
        os.mkdir(args.LOGDIR + '/' + d)
# [ -z "$SAVE_GOOD" ] && SAVE_GOOD=YES
# # [ -z "$RESIGN_PCT" -a "$NGAMES" -gt 1 ] && RESIGN_RATIO=1000 || RESIGN_RATIO=0
# [ -z "$DROP_N" ] && DROP_N=0
# [ -z "$USE_GTK" ] && USE_GTK=FALSE
# [ -z "$UPGRADE_ODDS" ] && UPGRADE_ODDS=10
# #$((NGAMES/50))
# [ 0 -eq $UPGRADE_ODDS ] && UPGRADE_ODDS=1
# [ -n "$SEED" ] && RANDOM=$SEED
# [ -z "$ONEPER" -a $NROOMS -lt $NGAMES ] && usage "use --one-per if --num-rooms < --num-games"

# [ -n "$DO_CLEAN" ] && cleanup

# RESUME=""
# for FILE in $(ls $LOGDIR/*.{xwg,txt} 2>/dev/null); do
#     if [ -e $FILE ]; then
#         echo "Unfinished games found in $LOGDIR; continue with them (or discard)?"
#         read -p "<yes/no> " ANSWER
#         case "$ANSWER" in
#             y|yes|Y|YES)
#                 RESUME=1
#                 ;;
#             *)
#                 ;;
#         esac
#     fi
#     break
# done

# if [ -z "$RESUME" -a -d $LOGDIR ]; then
# 	NEWNAME="$(basename $LOGDIR)_$$"
#     (cd $(dirname $LOGDIR) && mv $(basename $LOGDIR) /tmp/${NEWNAME})
# fi
# mkdir -p $LOGDIR

# if [ "$SAVE_GOOD" = YES ]; then
#     DONEDIR=$LOGDIR/done
#     mkdir -p $DONEDIR
# fi
# DEADDIR=$LOGDIR/dead
# mkdir -p $DEADDIR

# for VAR in NGAMES NROOMS USE_GTK TIMEOUT HOST PORT SAVE_GOOD \
#     MINDEVS MAXDEVS ONEPER RESIGN_PCT DROP_N ALL_VIA_RQ SEED \
#     APP_NEW; do
#     echo "$VAR:" $(eval "echo \$${VAR}") 1>&2
# done
# echo "DICTS: ${DICTS[*]}"
# echo -n "APPS_OLD: "; [ xx = "${APPS_OLD[*]+xx}" ] && echo "${APPS_OLD[*]}" || echo ""

# echo "*********$0 starting: $(date)**************"
# STARTTIME=$(date +%s)
# [ -z "$RESUME" ] && build_cmds || read_resume_cmds
# if [ TRUE = "$ALL_VIA_RQ" ]; then
#     run_via_rq
# else
#     run_cmds
# fi

# wait

# SECONDS=$(($(date +%s)-$STARTTIME))
# HOURS=$((SECONDS/3600))
# SECONDS=$((SECONDS%3600))
# MINUTES=$((SECONDS/60))
# SECONDS=$((SECONDS%60))
# echo "*********$0 finished: $(date) (took $HOURS:$MINUTES:$SECONDS)**************"

def termHandler(signum, frame):
    global gDone
    print('termHandler() called')
    gDone = True

def main():
    startTime = datetime.datetime.now()
    signal.signal(signal.SIGINT, termHandler)

    args = parseArgs()
    # Hack: old files confuse things. Remove is simple fix good for now
    if args.WITH_SMS:
        try: rmtree('/tmp/xw_sms')
        except: None
    devs = build_cmds(args)
    nDevs = len(devs)
    run_cmds(args, devs)
    print('{} finished; took {} for {} devices'.format(sys.argv[0], datetime.datetime.now() - startTime, nDevs))
    log_scores( devs )

##############################################################################
if __name__ == '__main__':
    main()
