const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function processor() {
  let Processor;
  class AudioWorkletProcessor {
    constructor() { this.port = {messages: [], postMessage: message => this.port.messages.push(message)}; }
  }
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/integrated-worklet.js'), 'utf8');
  vm.runInNewContext(source, {AudioWorkletProcessor, sampleRate:16000,
    registerProcessor: (name, value) => { assert.equal(name, 'mono-pcm'); Processor = value; }});
  return new Processor();
}

function feed(p, system, microphone) {
  assert.equal(system.length, microphone.length);
  for (let offset = 0; offset < system.length; offset += 128) {
    const a = system.subarray(offset, offset + 128), b = microphone.subarray(offset, offset + 128);
    const output = new Float32Array(a.length);
    p.process([[a], [b]], [[output]]);
    assert.ok(output.every(value => value === 0));
  }
}

function finish(p, channels) {
  p.port.onmessage({data:'stop'});
  assert.equal(p.port.messages.at(-1).type, 'stopped');
  const result = Array.from({length:channels}, () => []);
  for (const message of p.port.messages.filter(item => item.type === 'audio')) {
    const view = new DataView(message.buffer);
    for (let i = 0; i < view.byteLength; i += channels * 2)
      for (let channel = 0; channel < channels; channel++) result[channel].push(view.getInt16(i + channel * 2, true));
  }
  return result;
}

function closeTo(actual, expected, tolerance = 2) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} отличается от ${expected}`);
}

test('эталон содержит только звук компьютера и сохраняет все сэмплы', () => {
  const p = processor();
  p.port.onmessage({data:'enroll'});
  feed(p, new Float32Array(3200).fill(.25), new Float32Array(3200).fill(.75));
  const [system] = finish(p, 1);
  assert.equal(system.length, 3200);
  assert.ok(system.every(value => Math.abs(value - .25 * 32767) <= 2));
});

test('одновременная речь сохраняется в двух синхронных каналах без смешивания', () => {
  const p = processor();
  p.port.onmessage({data:'live'});
  feed(p, new Float32Array(3200).fill(.25), new Float32Array(3200).fill(.75));
  const [system, microphone] = finish(p, 2);
  assert.equal(system.length, 3200);
  assert.equal(microphone.length, 3200);
  assert.equal(p.port.messages[0].buffer.byteLength, 2560 * 4);
  assert.ok(system.every(value => Math.abs(value - .25 * 32767) <= 2));
  assert.ok(microphone.every(value => Math.abs(value - .6 * 32767) <= 2));
});

test('пик микрофона не приглушает голос кандидата', () => {
  const p = processor();
  const count = 2400, peak = 300;
  const system = new Float32Array(count).fill(.4), microphone = new Float32Array(count).fill(.2);
  microphone[peak] = 1.5;
  p.port.onmessage({data:'live'});
  feed(p, system, microphone);
  const [candidate, interviewer] = finish(p, 2);
  assert.ok(candidate.every(value => Math.abs(value - .4 * 32767) <= 2));
  closeTo(interviewer[peak], .9 * 32767);
  assert.ok(interviewer[peak - 24] < interviewer[peak - 48]);
  assert.ok(interviewer[peak + 1200] > interviewer[peak + 1]);
  assert.ok(interviewer.every(value => Math.abs(value) <= Math.ceil(.9 * 32767)));
});

test('пики обоих знаков ограничиваются отдельно, а остановка сохраняет последние сэмплы', () => {
  const p = processor();
  const system = new Float32Array(137).fill(.2), microphone = new Float32Array(137).fill(-.3);
  system[136] = 1;
  microphone[136] = -1.5;
  p.port.onmessage({data:'live'});
  feed(p, system, microphone);
  const [candidate, interviewer] = finish(p, 2);
  assert.equal(candidate.length, 137);
  assert.equal(interviewer.length, 137);
  closeTo(candidate.at(-1), .9 * 32767);
  closeTo(interviewer.at(-1), -.9 * 32767);

  p.port.messages.length = 0;
  p.port.onmessage({data:'live'});
  feed(p, new Float32Array(80).fill(.3), new Float32Array(80));
  const [nextSystem, nextMic] = finish(p, 2);
  assert.equal(nextSystem.length, 80);
  assert.ok(nextSystem.every(value => Math.abs(value - .3 * 32767) <= 2));
  assert.ok(nextMic.every(value => value === 0));
});
