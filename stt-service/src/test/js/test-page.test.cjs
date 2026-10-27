const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'test.html'), 'utf8');
const app = fs.readFileSync(path.join(root, 'test-app.js'), 'utf8');

test('test page uses the STT HTTP API and explains the no-diarization assumption', () => {
  assert.match(html, /без записи эталона и диаризации/);
  assert.match(html, /Весь звук микрофона во время ответа считается речью кандидата/);
  assert.match(html, /\/integrated\.html/);
  assert.match(app, /\/api\/test\/start/);
  assert.match(app, /\/api\/audio/);
  assert.match(app, /\/api\/question\/asked/);
  assert.match(app, /integration\.excel\?\.url/);
  assert.doesNotMatch(app, /\/ws\/interview(?=["'`])/);
});
