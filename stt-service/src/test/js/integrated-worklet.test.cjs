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
  vm.runInNewContext(source, {
    AudioWorkletProcessor, sampleRate: 16000,
    registerProcessor: (name, value) => { assert.equal(name, 'mono-pcm'); Processor = value; }
  });
  return new Processor();
}

function feed(p, system, microphone) {
  assert.equal(system.length, microphone.length);
  for (let offset = 0; offset < system.length; offset += 128) {
    const a = system.subarray(offset, offset + 128);
    const b = microphone.subarray(offset, offset + 128);
    const output = new Float32Array(a.length);
    p.process([[a], [b]], [[output]]);
    assert.ok(output.every(value => value === 0), 'Worklet не должен воспроизводить захваченный звук');
  }
}

function finish(p) {
  p.port.onmessage({data: 'stop'});
  assert.equal(p.port.messages.at(-1).type, 'stopped');
  return p.port.messages.filter(message => message.type === 'audio');
}

function pcm(messages) {
  return messages.flatMap(message => Array.from(new Int16Array(message.buffer)));
}

function closeTo(actual, expected, tolerance = 2) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} отличается от ${expected}`);
}

test('эталон содержит только звук компьютера и сохраняет все сэмплы', () => {
  const p = processor();
  p.port.onmessage({data: 'enroll'});
  feed(p, new Float32Array(3200).fill(.25), new Float32Array(3200).fill(.75));
  const messages = finish(p);
  const samples = pcm(messages);
  assert.equal(messages[0].buffer.byteLength, 5120);
  assert.equal(samples.length, 3200);
  assert.ok(samples.every(value => Math.abs(value - .25 * 32767) <= 2));
});

test('интервью складывает оба источника с заданными коэффициентами', () => {
  const p = processor();
  p.port.onmessage({data: 'live'});
  feed(p, new Float32Array(3200).fill(.25), new Float32Array(3200).fill(.75));
  const samples = pcm(finish(p));
  assert.equal(samples.length, 3200);
  assert.ok(samples.every(value => Math.abs(value - .85 * 32767) <= 2));
});

test('одиночные источники не делятся пополам', () => {
  for (const [system, mic, expected] of [[.4, 0, .4], [0, .5, .4]]) {
    const p = processor();
    p.port.onmessage({data: 'live'});
    feed(p, new Float32Array(300).fill(system), new Float32Array(300).fill(mic));
    const samples = pcm(finish(p));
    assert.equal(samples.length, 300);
    assert.ok(samples.every(value => Math.abs(value - expected * 32767) <= 2));
  }
});

test('ограничитель заранее снижает пики и плавно возвращает усиление', () => {
  const p = processor();
  const count = 2400, peakIndex = 300;
  const system = new Float32Array(count).fill(.4);
  const mic = new Float32Array(count);
  system[peakIndex] = .8;
  mic[peakIndex] = .75;
  p.port.onmessage({data: 'live'});
  feed(p, system, mic);
  const samples = pcm(finish(p));
  assert.equal(samples.length, count);
  assert.ok(samples.every(value => Math.abs(value) <= Math.ceil(.9 * 32767)));
  closeTo(samples[peakIndex], .9 * 32767);
  closeTo(samples[peakIndex - 60], .4 * 32767);
  assert.ok(samples[peakIndex - 24] < samples[peakIndex - 48]);
  assert.ok(samples[peakIndex + 1200] > samples[peakIndex + 1]);
  assert.ok(samples[peakIndex + 1200] < .4 * 32767);
});

test('перегруженная смесь не выходит за потолок при обоих знаках сигнала', () => {
  const p = processor();
  const count = 4096;
  const system = Float32Array.from({length: count}, (_, i) => .85 * Math.sin(i * .11));
  const mic = Float32Array.from({length: count}, (_, i) => .85 * Math.sin(i * .11));
  p.port.onmessage({data: 'live'});
  feed(p, system, mic);
  const samples = pcm(finish(p));
  assert.equal(samples.length, count);
  assert.ok(samples.some(value => value > 0) && samples.some(value => value < 0));
  assert.ok(samples.every(value => Math.abs(value) <= Math.ceil(.9 * 32767)));
});

test('остановка выдаёт последний пик, а новая фаза очищает буфер ограничителя', () => {
  const p = processor();
  const system = new Float32Array(137).fill(.2), mic = new Float32Array(137);
  system[136] = 1;
  p.port.onmessage({data: 'enroll'});
  feed(p, system, mic);
  const first = pcm(finish(p));
  assert.equal(first.length, 137);
  closeTo(first.at(-1), .9 * 32767);

  p.port.messages.length = 0;
  p.port.onmessage({data: 'live'});
  feed(p, new Float32Array(80).fill(.3), new Float32Array(80));
  const second = pcm(finish(p));
  assert.equal(second.length, 80);
  assert.ok(second.every(value => Math.abs(value - .3 * 32767) <= 2));
});
