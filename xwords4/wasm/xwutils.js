var state = {client: null,
             closure: null,
			 connected: false,
			 disconnTimeMS: 0,
			 connTimeMS: 0,
			 connChangeStamp: 0,
			};

function fontSize(adj=1.0) {
	return (adj * state.height / 500) + 'rem';
}

function ccallString(proc, closure, str) {
	Module.ccall('cbckString', null, ['number', 'number', 'string'],
				 [proc, closure, str]);
}

function registerOnce(devid, gitrev, now, dbg) {
	let nextTimeKey = 'next_reg';
	let gitKey = 'last_write';
	let nextTime = parseInt(localStorage.getItem(nextTimeKey));
	let prevGit = localStorage.getItem(gitKey);
	if ( prevGit == gitrev && now < nextTime ) {
		if ( dbg ) {
			console.log('registerOnce(): next in ' + (nextTime - now) + ' secs');
		}
	} else {
		let vrntName = window.location.host;
		vrntName += window.location.pathname.split('/').slice(0, -1).join('/');
		let args = { devid: devid,
					 gitrev: gitrev,
					 loc: navigator.language,
					 os: navigator.appName,
					 vers: '0.0',
					 dbg: dbg,
					 myNow: now,
					 vrntName: vrntName,
				   };
		let body = JSON.stringify(args);

		fetch('/xw4/api/v1/register', {
			method: 'post',
			body: body,
			headers: {
				'Content-Type': 'application/json'
			},
		}).then(handleFetchErrors)
		  .then(res => {
			return res.json();
		}).then(data => {
			// console.log('data: ' + JSON.stringify(data));
			if ( data.success ) {
				localStorage.setItem(nextTimeKey, data.atNext);
				localStorage.setItem(gitKey, gitrev);
			}
		}).catch(ex => {
			console.error('registerOnce(): fetch()=>' + ex);
		});
	}
}

function handleFetchErrors(response) {
	if ( response.ok ) {
		return response;
	} else {
		throw Error(response.statusText);
	}
}

function getDict(langs, proc, closure) {
	function callWhenDone(xwd, lc, langName, data, len) {
		Module.ccall('gotDictBinary', null,
					 ['number', 'number', 'string', 'string', 'string', 'array', 'number'],
					 [proc, closure, xwd, lc, langName, data, len ]);
	}

	let gots = {};				// for later

	console.log('langs: ' + langs + '; langs[0]: ' + langs[0]);
	let args = '?lc=' + langs.join('|');
	console.log('args: ' + args);
	fetch('/xw4/info.py/listDicts' + args, {
		method: 'post',
		headers: {
			'Content-Type': 'application/json',
		},
	}).then(handleFetchErrors)
      .then(response => {
			return response.json();
	}).then(data => {
		// console.log('data: ' + JSON.stringify(data));
		for ( lang of data.langs ) {
			let dict = null;
			for ( one of lang.dicts ) {
				if ( !dict || one.nBytes > dict.nBytes ) {
					dict = one;
				}
			}
			if ( dict ) {
				gots.xwd = dict.xwd;
				gots.langName = lang.lang;
				gots.lc = lang.lc;
				let path = '/' + ['and_wordlists', gots.langName, gots.xwd].join('/');
				return fetch(path);
			}
		}
	}).then(handleFetchErrors)
	  .then(response => {
		// console.log('got here!!!' + response);
		return response.arrayBuffer();
	}).then(data=> {
		let len = data.byteLength;
		let dataPtr = Module._malloc(len);
		// Copy data to Emscripten heap
		var dataHeap = new Uint8Array(Module.HEAPU8.buffer, dataPtr, len);
		dataHeap.set( new Uint8Array(data) );
		callWhenDone(gots.xwd, gots.lc, gots.langName, dataHeap, len);
		Module._free(dataPtr);
	}).catch(ex => {
		callWhenDone(null, null, null, [], 0);
	});
}

// Called from main() asap after things are initialized etc.
function jssetup(closure, dbg, devid, gitrev, now, noTabProc, focusProc, msgProc) {
	// Set a unique tag so we know if somebody comes along later
	let tabID = Math.random();
	let item = 'tabID/' + dbg;
	localStorage.setItem(item, tabID);
	let listener = function () {
		newTabID = 	localStorage.getItem(item);
		if ( newTabID != tabID ) {
			state.client.disconnect();
			ccallString(noTabProc, state.closure, '');
			window.removeEventListener('storage', listener);
		}
	};
	window.addEventListener('storage', listener);

	window.onfocus = function () {
		ccallString(focusProc, state.closure, 'focus');
	};
	window.onblur = function () {
		ccallString(focusProc, state.closure, 'blur');
	};

	state.closure = closure;
	state.msgProc = msgProc;

	registerOnce(devid, gitrev, now, dbg);

	document.getElementById("mqtt_span").textContent=devid;

	function onConnChange(isConn) {
		state.connected = isConn;

		const now = Date.now();
		if ( 0 != state.connChangeStamp ) {
			const incr = now - state.connChangeStamp;
			if ( isConn ) {
				state.disconnTimeMS += incr;
			} else {
				state.connTimeMS += incr;
			}
		}
		state.connChangeStamp = now;

		let stateStr = isConn ? 'Connected' : 'Disconnected';
		document.getElementById("mqtt_status").textContent = stateStr;

		Module.ccall('MQTTConnectedChanged', null, ['number', 'boolean'],
					 [state.closure, isConn]);

		console.error('new conn state: ', isConn, '; total conn: ', state.connTimeMS,
					  '; total disconn: ', state.disconnTimeMS );
	}

	state.client = new Paho.MQTT.Client("eehouse.org", 9001, '/wss', devid);

	// set callback handlers
	state.client.onConnectionLost = function onConnectionLost(responseObject) {
		onConnChange(false);
		if (responseObject.errorCode !== 0) {
			console.log("onConnectionLost:"+responseObject.errorMessage);
		}
	};
	state.client.onMessageArrived = function onMessageArrived(message) {
		let payload = message.payloadBytes;
		let length = payload.length;
		Module.ccall('cbckBinary', null, ['number', 'number', 'number', 'array'],
					 [state.msgProc, state.closure, length, payload]);
	};

	function onConnect() {
		onConnChange(true);

		var subscribeOptions = {
			qos: 2,  // QoS
			// invocationContext: {foo: true},  // Passed to success / failure callback
			// onSuccess: function() { alert('subscribe succeeded'); },
			onFailure: function() { alert('subscribe failed'); },
			timeout: 10,
		};
		state.client.subscribe('xw4/device/' + devid, subscribeOptions);
	}

	state.client.connect({mqttVersion: 3,
						  userName: "xwuser",
						  password: "xw4r0cks",
						  useSSL: true,
						  reconnect: true,
						  onSuccess: onConnect,
						  onFailure: function() { console.error('mqtt.client.connect.onFailure'); },
						 });

	function callResize() {
        state.width = window.innerWidth;
        state.height = state.width * 2;
        if ( state.height > window.innerHeight ) {
            state.height = window.innerHeight;
            state.width = state.height / 2;
        }
        state.height = state.height * 100 / 151;

		for ( const id of ['nbalert', 'gamename'] ) {
			document.getElementById(id).style['font-size'] = fontSize();
		}

        ccall('onResize', null, ['number', 'number', 'number'],
            [state.closure, state.width, state.height]);
	}
	window.addEventListener('resize', function() {
		const innerWidth = window.innerWidth;
		const innerHeight = window.innerHeight;
		window.setTimeout( function() {
			if ( innerWidth == window.innerWidth && innerHeight == window.innerHeight ) {
				callResize();
			}
		}, 500 );
	});
	callResize();				// so client knows initial size
}

function mqttSend( topic, ptr ) {
	let canSend = null != state.client && state.connected;
	if ( canSend ) {
		message = new Paho.MQTT.Message(ptr);
		message.destinationName = topic;
		message.qos = 2;
		state.client.send(message);
	} else {
		console.error('mqttSend: not connected');
	}
	return canSend;
}

function addDepthNote(dlg) {
	let depth = dlg.parentNode.childElementCount;
	if ( depth > 1 ) {
		let div = document.createElement('div');
		div.textContent = '(Depth: ' + depth + ')';
		dlg.appendChild(div);
	}
}

function newDlgWMsg(msg) {
	let container = document.getElementById('nbalert');

	let dlg = document.createElement('div');
	dlg.classList.add('nbalert');
	dlg.style.zIndex = 10000 + container.childElementCount;
	container.appendChild( dlg );

	let txtDiv = document.createElement('div');
	txtDiv.textContent = msg
	dlg.appendChild( txtDiv );

	return dlg;
}

function newButtonDiv(buttons, proc, asDivs) {
	let div = document.createElement('div');
	div.classList.add('buttonRow');
	for ( let ii = 0; ii < buttons.length; ++ii ) {
		let buttonTxt = buttons[ii];
		let button = document.createElement('button');
		button.classList.add('xwbutton');
		button.textContent = buttonTxt;
		button.style['font-size'] = fontSize(1.2);
		button.onclick = function() { proc(ii); };
		if ( asDivs ) {
			let bdiv = document.createElement('div');
			bdiv.appendChild(button);
			button = bdiv;
		}
		div.appendChild(button);
	}

	return div;
}

function nbDialog(msg, buttons, proc, closure) {
	const dlg = newDlgWMsg( msg );

	const butProc = function(indx) {
		dlg.parentNode.removeChild(dlg);
		ccallString(proc, closure, buttons[indx]);
	}
	dlg.appendChild( newButtonDiv( buttons, butProc, false ) );
	addDepthNote(dlg);
}

function nbBlankPick(title, buttons, proc, closure) {
	let dlg = newDlgWMsg( title );

	const butProc = function(indx) {
		dlg.parentNode.removeChild(dlg);
		ccallString(proc, closure, indx.toString());
	}

	dlg.appendChild( newButtonDiv( buttons, butProc, false ) );
	addDepthNote(dlg);
}

// To enable sorting of names on buttons while keeping existing code,
// I'm creating an array of pairs then sorting that.
function nbGamePick(title, gameMap, proc, closure) {
	let dlg = newDlgWMsg( title );

	let pairs = [];
	Object.keys(gameMap).forEach( function(key) {
		pairs.push({id:key, txt:gameMap[key]});
	});

	pairs.sort(function(a, b){
		var stra = a.txt.toLowerCase();
		var strb = b.txt.toLowerCase();
		if (stra < strb) {return -1;}
		if (stra > strb) {return 1;}
		return parseInt(a.id) - parseInt(b.id);
	});
	let buttons = [];
	for ( pair of pairs ) {
		buttons.push(pair.txt);
	}
	
	butProc = function(indx) {
		dlg.parentNode.removeChild(dlg);
		ccallString(proc, closure, pairs[indx].id);
	}
	dlg.appendChild( newButtonDiv( buttons, butProc, true ) );

	cancelProc = function() {
		dlg.parentNode.removeChild(dlg);
		ccallString(proc, closure, null);
	};
	dlg.appendChild( newButtonDiv( ['Cancel'], cancelProc, false ) );

	addDepthNote(dlg);
}

function setDivButtons(divid, buttons, proc, closure) {
	let parent = document.getElementById(divid);
	while ( parent.lastElementChild ) {
		parent.removeChild(parent.lastElementChild);
	}

	butProc = function(indx) {
		ccallString(proc, closure, buttons[indx]);
	}

	parent.appendChild( newButtonDiv( buttons, butProc, false ) );
}

function nbGetString(msg, dflt, proc, closure) {
	let dlg = newDlgWMsg( msg );

	let div = document.createElement('div');
	div.classList.add('emscripten');
	dlg.appendChild(div);

	let tarea = document.createElement('textarea');
	tarea.classList.add('stringedit');
	tarea.style['font-size'] = fontSize();
	tarea.value = dflt;
	div.appendChild( tarea );

	dismissed = function(str) {
		dlg.parentNode.removeChild(dlg);
		ccallString(proc, closure, str);
	}

	let buttons = ["Cancel", "OK"];
	butProc = function(indx) {
		if ( buttons[indx] == 'Cancel' ) {
			dismissed(null);
		} else if ( buttons[indx] == 'OK' ) {
			dismissed(tarea.value);
		}
	}
	dlg.appendChild( newButtonDiv( buttons, butProc, false ) );
	addDepthNote(dlg);
}

function newRadio(txt, id, isSet, proc) {
	let span = document.createElement('span');
	let radio = document.createElement('input');
	radio.type = 'radio';
	radio.id = id;
	radio.name = id;
	radio.onclick = proc;
	radio.checked = isSet;

	let label = document.createElement('label')
	label.htmlFor = id;
	var description = document.createTextNode(txt);
	label.appendChild(description);

	span.appendChild(label);
	span.appendChild(radio);

	return span;
}

function newCheckbox(txt, id, val) {
	let span = document.createElement('span');
	let checkbox = document.createElement('input');
	checkbox.type = 'checkbox';
	checkbox.id = id;
	checkbox.checked = val;

	let label = document.createElement('label')
	var description = document.createTextNode(txt);
	label.appendChild(description);

	span.appendChild(label);
	span.appendChild(checkbox);

	return span;
}

function nbGetNewGame(closure, msg, allowHints, isRobot, langs, langName) {
	const dlg = newDlgWMsg(msg);

	let hintsDiv = document.createElement('div');
	dlg.appendChild(hintsDiv);
	hintsDiv.appendChild(newCheckbox("Allow hints", 'allowHints', allowHints));

	const explDiv = document.createElement('div');
	dlg.appendChild( explDiv );
	explDiv.textContent = '>> Is your opponent a robot or someone you will invite?';

	const radioDiv = document.createElement('div');
	dlg.appendChild( radioDiv );
	let robotSet = isRobot;
	radioDiv.appendChild(newRadio('Robot', 'newgame', robotSet,
								  function() {robotSet = true;}));
	radioDiv.appendChild(newRadio('Remote player', 'newgame', !robotSet,
								  function() {robotSet = false;} ));

	let chosenLang = langName ? langName : langs[0];
	if ( 1 < langs.length ) {
		const langsExplDiv = document.createElement('div');
		dlg.appendChild( langsExplDiv );
		langsExplDiv.textContent = ">> Choose your game language";
		const langsDiv = document.createElement('div');
		dlg.appendChild( langsDiv );
		for ( let ii = 0; ii < langs.length; ++ii ) {
			let langName = langs[ii];
			let isSet = langName == chosenLang;
			langsDiv.appendChild(newRadio(langName, 'lang', isSet,
										  function() {chosenLang = langName;}));
		}
	}

	const buttons = ['Cancel', 'OK'];
	const butProc = function(indx) {
		if ( buttons[indx] == 'OK' ) {
			let allowHints = document.getElementById('allowHints').checked;
			const types = ['number', 'boolean', 'string', 'boolean'];
			const params = [closure, robotSet, chosenLang, allowHints];
			Module.ccall('onNewGame', null, types, params);
		}
		dlg.parentNode.removeChild(dlg);
	}

	dlg.appendChild( newButtonDiv( buttons, butProc, false ) );
	addDepthNote(dlg);
}

for ( let one of ['paho-mqtt.js'] ) {
	let script = document.createElement('script');
	script.src = one;
	document.body.appendChild(script);
}
