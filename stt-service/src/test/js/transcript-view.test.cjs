const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('result becomes available only after completion and old links load after restart', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/transcript-view.js'), 'utf8');
  const nodes = new Map();
  const element = id => { if (!nodes.has(id)) nodes.set(id, {hidden:true, textContent:'', href:''}); return nodes.get(id); };
  const id = 'd34150f4-4fa3-495f-9f7d-df696ba46601';
  const make = search => {
    const window = {};
    vm.runInNewContext(source, {window, document:{getElementById:element},
      location:{pathname:'/test.html', search}, URLSearchParams, encodeURIComponent,
      fetch:async url => ({ok:true,text:async()=>url.includes('timed') ? 'время' : 'текст'})});
    return window.transcriptView;
  };
  const view = make('');
  view.update('live', null, null, id);
  assert.equal(element('transcript-result').hidden, true);
  view.update('stopping', null, null, id, {done:2,total:8});
  assert.equal(element('transcript-result').hidden, false);
  assert.equal(element('transcript-actions').hidden, true);
  assert.match(element('transcript-result-state').textContent, /2 из 8/);
  const base = '/api/interviews/' + id + '/transcripts/';
  view.update('finished', {plain:base+'transcript.md', timed:base+'transcript-timed.md', zip:base+'transcripts.zip'}, null, id);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(element('transcript-full').textContent, 'время');
  assert.equal(element('transcript-zip').href, base+'transcripts.zip');
  element('transcript-show-plain').onclick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(element('transcript-full').textContent, 'текст');
  make('?session=' + id);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(element('transcript-full').textContent, 'время');
});
