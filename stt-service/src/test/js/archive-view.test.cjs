const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('архив не обещает сохранность до подтверждения и открывает результат по ссылке', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/archive-view.js'), 'utf8');
  const nodes = new Map();
  const element = id => { if (!nodes.has(id)) nodes.set(id, {hidden:true, textContent:'', href:''}); return nodes.get(id); };
  const id = 'd34150f4-4fa3-495f-9f7d-df696ba46601';
  let state = {catalogVersion:1, callStatus:'finished', audioStatus:'pending', reportStatus:'pending', artifacts:[]};
  let repeat;
  vm.runInNewContext(source, {window:{}, document:{getElementById:element},
    location:{pathname:'/integrated.html',search:'?session=' + id}, URLSearchParams, encodeURIComponent,
    setInterval:callback => {repeat = callback;},
    fetch:async () => ({ok:true,json:async () => state})});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(element('archive-call').textContent, 'завершён');
  assert.equal(element('archive-audio').textContent, 'ещё не сохранена');
  assert.equal(element('archive-audio-link').hidden, true);
  state = {catalogVersion:1, callStatus:'finished', audioStatus:'saved', reportStatus:'ready', artifacts:['audio_wav','report_xlsx']};
  await repeat();
  assert.equal(element('archive-audio').textContent, 'сохранена');
  assert.equal(element('archive-report').textContent, 'готов');
  assert.equal(element('archive-audio-link').href, '/api/interviews/' + id + '/artifacts/audio_wav');
  assert.equal(element('archive-report-link').hidden, false);
  state = {catalogVersion:1, callStatus:'finished', audioStatus:'unavailable', reportStatus:'unavailable', artifacts:['audio_wav','report_xlsx']};
  await repeat();
  assert.equal(element('archive-audio').textContent, 'хранилище недоступно');
  assert.equal(element('archive-audio-link').hidden, true);
  assert.equal(element('archive-report-link').hidden, true);
});
