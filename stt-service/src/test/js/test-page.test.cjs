const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

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

test('test page resets the question on restart and recovers when TTS fails', async () => {
  const nodes = new Map();
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, {textContent:'', disabled:false, checked:true, value:'',
      classList:{add(){}, remove(){}}, replaceChildren(){}, prepend(){}, children:[]});
    return nodes.get(id);
  };
  const timers = [], utterances = [], requests = [];
  const synth = {cancel(){}, speak(u){utterances.push(u);}};
  const context = {
    document:{getElementById:element, createElement:()=>({}), createTextNode:()=>({})},
    window:{speechSynthesis:synth}, speechSynthesis:synth,
    SpeechSynthesisUtterance:function(text){this.text=text;},
    navigator:{}, setInterval(){}, clearTimeout(){},
    setTimeout(fn,ms){timers.push({fn,ms});}, Date, console,
    fetch:async url => { requests.push(url); return {ok:true, json:async()=>({})}; }
  };
  vm.runInNewContext(app + '\n' +
    'globalThis.renderForTest = render; globalThis.startForTest = start; globalThis.liveForTest = () => mode = "live";', context);
  const state = {phase:'live', integration:{question:{number:1, text:'Вопрос', followUpNumber:0}}};
  context.liveForTest(); context.renderForTest(state);
  assert.equal(utterances.length, 1);
  timers.at(-1).fn();
  assert.equal(element('asked').disabled, false);
  const before = requests.filter(url => url === '/api/question/asked').length;
  utterances[0].onend();
  assert.equal(requests.filter(url => url === '/api/question/asked').length, before);
  await context.startForTest(); // Ошибка доступа к микрофону после сброса старого состояния.
  context.liveForTest(); context.renderForTest(state);
  assert.equal(utterances.length, 2, 'одинаковый первый вопрос нового интервью озвучивается снова');
});
