#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');

const main = fs.readFileSync('app/src/main/java/com/wayward/launcher/MainActivity.java', 'utf8');
const session = fs.readFileSync('app/src/main/java/com/wayward/launcher/GameSessionActivity.java', 'utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');

const launcherTag = manifest.split('\n').find(line =>
  line.includes('<activity android:name=".MainActivity"')) || '';
const sessionTag = manifest.split('\n').find(line =>
  line.includes('<activity android:name=".GameSessionActivity"')) || '';

assert.ok(launcherTag.includes('android:exported="true"'),
  'MainActivity must remain the exported launcher entry point');
assert.ok(!launcherTag.includes('documentLaunchMode'),
  'the home launcher must use one ordinary Android task');
assert.ok(sessionTag.includes('android:exported="false"'),
  'game session Activity must be internal-only');
assert.ok(sessionTag.includes('android:documentLaunchMode="intoExisting"'),
  'document-task reuse must be scoped to one game profile Activity');
assert.ok(session.includes('extends MainActivity'),
  'the dedicated session Activity must reuse the existing native session implementation');
assert.ok(main.includes('new Intent(this,GameSessionActivity.class)'),
  'profile launches must create GameSessionActivity, not another MainActivity');
assert.ok(main.includes('Intent.FLAG_ACTIVITY_NEW_DOCUMENT'),
  'each game profile must retain its independent Android document task');
assert.ok(main.includes('if(sessionId==null){\n            showHome();'),
  'only the launcher task may create the folder-selection home screen');
assert.ok(main.includes('root=new FrameLayout(this);') &&
  main.includes('openProfileSession(sessionId);'),
  'game session tasks must initialize directly into their own WebView root');
assert.ok(main.includes('if(this instanceof GameSessionActivity){') &&
  main.includes('moveTaskToBack(true);'),
  'returning from a game must background its task and reveal the single home task');

console.log('Android launcher/session task separation checks passed.');
