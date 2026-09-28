// SPDX-License-Identifier: AGPL-3.0-only
//
// Pretends to be rust_note's web editor with a note open: joins the note's
// Yjs collab room, and on stdin commands makes a keystroke (so the room
// autosaves its in-memory copy over whatever is on disk) or leaves.
// Used by NotesServerSyncIntegrationTest to reproduce the "editor autosave
// clobbers a REST write" case against a real server.
//
//   node notes-server-editor-sim.cjs <rust_note web dir> <ws base, e.g. ws://127.0.0.1:18080> <note id>
//   stdin: "type" | "close"      stdout: "ready" | "typed" | "closed"
const { createRequire } = require('module');
const path = require('path');
const readline = require('readline');

const [webDir, wsBase, noteId] = process.argv.slice(2);
const req = createRequire(path.join(webDir, 'package.json'));
const Y = req('yjs');
const { WebsocketProvider } = req('y-websocket');

const doc = new Y.Doc();
const room = noteId.split('/').map(encodeURIComponent).join('/');
const provider = new WebsocketProvider(`${wsBase}/ws/notes`, room, doc, {
	WebSocketPolyfill: globalThis.WebSocket,
	disableBc: true
});
const text = doc.getText('content');

provider.on('sync', (synced) => {
	if (synced) console.log('ready');
});

readline.createInterface({ input: process.stdin }).on('line', (line) => {
	if (line === 'type') {
		// A keystroke and its undo: content ends unchanged, but the room is dirty.
		text.insert(text.length, ' ');
		text.delete(text.length - 1, 1);
		console.log('typed');
	} else if (line === 'close') {
		provider.destroy();
		doc.destroy();
		console.log('closed');
		setTimeout(() => process.exit(0), 200);
	}
});
