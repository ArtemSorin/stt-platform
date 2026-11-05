const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('тестовый микрофон отправляет последний неполный кадр перед остановкой', () => {
  let Processor;
  class AudioWorkletProcessor {
    constructor() { this.port = {messages: [], postMessage: message => this.port.messages.push(message)}; }
  }
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/stt-pcm-worklet.js'), 'utf8');
  vm.runInNewContext(source, {AudioWorkletProcessor, sampleRate:16000, registerProcessor: (_, cls) => { Processor = cls; }});
  const p = new Processor();
  p.port.onmessage({data:'start'});
  p.process([[new Float32Array(900).fill(.25)]]);
  p.port.onmessage({data:'stop'});
  assert.equal(p.port.messages[0].byteLength, 1600);
  assert.equal(p.port.messages[1].byteLength, 200);
  assert.equal(p.port.messages[2].type, 'stopped');
});
