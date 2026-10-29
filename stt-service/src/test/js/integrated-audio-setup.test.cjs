const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness({rate = 16000, echoCancellation = true} = {}) {
  const calls = {connections: [], requests: [], stopped: [], modules: [], closed: 0};
  const nodes = new Map();
  const makeNode = () => ({textContent:'', hidden:false, disabled:false, value:'1', checked:true, children:[],
    classList:{toggle(){}}, replaceChildren(...children){this.children = children;},
    append(...children){this.children.push(...children);}, prepend(child){this.children.unshift(child);}, remove(){}});
  const element = id => { if (!nodes.has(id)) nodes.set(id, makeNode()); return nodes.get(id); };
  const track = (name, settings) => ({readyState:'live', getSettings:() => settings,
    stop(){this.readyState = 'ended'; calls.stopped.push(name);}});
  const video = track('video', {displaySurface:'monitor'});
  const system = track('system', {});
  const mic = track('microphone', {echoCancellation});
  const display = {getVideoTracks:() => [video], getAudioTracks:() => [system], getTracks:() => [video, system]};
  const microphoneStream = {getAudioTracks:() => [mic], getTracks:() => [mic]};

  class FakeAudioContext {
    constructor(options) {
      calls.contextOptions = options;
      this.sampleRate = rate;
      this.destination = {};
      this.audioWorklet = {addModule: async url => calls.modules.push(url)};
    }
    createMediaStreamSource(stream) {
      const name = stream === microphoneStream ? 'microphone' : 'system';
      return {connect:(_node, output, input) => calls.connections.push({name, output, input}), disconnect(){}};
    }
    async resume() { calls.resumed = true; }
    close() { calls.closed++; }
  }
  class FakeWorklet {
    constructor(_context, name, options) {
      calls.workletName = name;
      calls.workletOptions = options;
      this.port = {postMessage:message => {
        if (message === 'stop') Promise.resolve().then(() => this.port.onmessage?.({data:{type:'stopped'}}));
      }};
      calls.worklet = this;
    }
    connect() {}
    disconnect() {}
  }
  class FakeMediaStream {
    constructor(tracks) { this.tracks = tracks; }
    getAudioTracks() { return this.tracks; }
  }

  const sandbox = {
    document:{getElementById:element, createElement:makeNode, createTextNode:text => ({textContent:text})},
    window:{}, AudioContext:FakeAudioContext, AudioWorkletNode:FakeWorklet, MediaStream:FakeMediaStream,
    navigator:{mediaDevices:{
      getDisplayMedia:async options => { calls.displayOptions = options; return display; },
      getUserMedia:async options => { calls.micOptions = options; return microphoneStream; }
    }},
    fetch:async (url, options) => { calls.requests.push({url, options}); return {ok:true, json:async () => ({})}; },
    setInterval(){}, setTimeout(){}, clearTimeout(){}, performance:{now:() => 0}, console
  };
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/integrated-app.js'), 'utf8');
  vm.runInNewContext(source + `
    globalThis.prepareAudioForTest = prepareAudio;
    globalThis.startEnrollmentForTest = startEnrollment;
    globalThis.releaseAudioForTest = releaseAudio;
    globalThis.setModeForTest = value => mode = value;
    globalThis.setSessionForTest = value => sessionId = value;
    globalThis.resetSequenceForTest = () => sequence = 0;
    globalThis.pendingForTest = () => pending;
  `, sandbox);
  return {sandbox, calls, element, video, system, mic};
}

test('захват запрашивает AEC и подключает оба источника к контексту 16 кГц', async () => {
  const h = harness();
  await h.sandbox.prepareAudioForTest();
  assert.equal(h.calls.micOptions.audio.echoCancellation, true);
  assert.equal(h.calls.micOptions.audio.noiseSuppression, false);
  assert.equal(h.calls.micOptions.audio.autoGainControl, false);
  assert.equal(h.calls.contextOptions.sampleRate, 16000);
  assert.equal(h.calls.modules[0], '/integrated-worklet.js');
  assert.equal(h.calls.workletOptions.numberOfInputs, 2);
  assert.equal(h.calls.connections.find(item => item.name === 'system').input, 0);
  assert.equal(h.calls.connections.find(item => item.name === 'microphone').input, 1);
  assert.equal(h.calls.resumed, true);
  h.sandbox.releaseAudioForTest();
  assert.equal(h.calls.stopped.length, 3);
});

test('неподходящая частота останавливает запуск и освобождает устройства', async () => {
  const h = harness({rate:48000});
  await h.sandbox.startEnrollmentForTest();
  assert.match(h.element('error').textContent, /16 кГц/);
  assert.equal(h.calls.requests.length, 0);
  assert.equal(h.calls.closed, 1);
  assert.equal(h.calls.stopped.length, 3);
});

test('неподтверждённое эхоподавление показывается без остановки захвата', async () => {
  const h = harness({echoCancellation:false});
  await h.sandbox.prepareAudioForTest();
  assert.match(h.element('log').children[0].textContent, /не подтвердил эхоподавление/);
  assert.equal(h.calls.resumed, true);
  h.sandbox.releaseAudioForTest();
});

test('кадры эталона и интервью отправляются по прежним маршрутам и по порядку', async () => {
  const h = harness();
  await h.sandbox.prepareAudioForTest();
  h.sandbox.setSessionForTest('session');
  h.sandbox.setModeForTest('enroll');
  for (let i = 0; i < 2; i++)
    h.calls.worklet.port.onmessage({data:{type:'audio', buffer:new Uint8Array([i, 0]).buffer}});
  await h.sandbox.pendingForTest();
  h.sandbox.setModeForTest('live');
  h.sandbox.resetSequenceForTest();
  h.calls.worklet.port.onmessage({data:{type:'audio', buffer:new Uint8Array([2, 0]).buffer}});
  await h.sandbox.pendingForTest();
  assert.deepEqual(h.calls.requests.map(request => request.url), ['/api/enrollment/audio', '/api/enrollment/audio', '/api/audio']);
  assert.deepEqual(h.calls.requests.map(request => request.options.headers['X-Sequence']), ['0', '1', '0']);
  assert.ok(h.calls.requests.every(request => request.options.headers['X-Session-Id'] === 'session'));
  h.sandbox.releaseAudioForTest();
});

test('ошибка Worklet завершает интервью без ожидания ответа от остановленного процессора', async () => {
  const h = harness();
  await h.sandbox.prepareAudioForTest();
  h.sandbox.setSessionForTest('session');
  h.sandbox.setModeForTest('live');
  await h.calls.worklet.onprocessorerror();
  assert.match(h.element('error').textContent, /Обработка звука в браузере остановилась/);
  assert.equal(h.calls.requests.at(-1).url, '/api/finish');
  assert.equal(h.calls.requests.at(-1).options.headers['X-Stop-Reason'], 'capture_error');
  assert.equal(h.calls.stopped.length, 3);
});

test('потеря источника завершает интервью с правильной причиной', async () => {
  for (const [name, reason] of [['system', 'system_audio_lost'], ['mic', 'microphone_lost']]) {
    const h = harness();
    await h.sandbox.prepareAudioForTest();
    h.sandbox.setSessionForTest('session');
    h.sandbox.setModeForTest('live');
    await h[name].onended();
    assert.equal(h.calls.requests.at(-1).url, '/api/finish');
    assert.equal(h.calls.requests.at(-1).options.headers['X-Stop-Reason'], reason);
    assert.equal(h.calls.stopped.length, 3);
  }
});
