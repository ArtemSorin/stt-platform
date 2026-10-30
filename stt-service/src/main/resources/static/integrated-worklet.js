const SYSTEM_GAIN = 1;
const MICROPHONE_GAIN = 0.8;
const PEAK_CEILING = 0.9;
const LOOKAHEAD_SECONDS = 0.003;
const RELEASE_SECONDS = 0.08;
const OUTPUT_SAMPLES = 2560; // 160 мс при 16 кГц.

class PeakLimiter {
  constructor() {
    this.lookahead = Math.max(1, Math.round(sampleRate * LOOKAHEAD_SECONDS));
    this.delay = new Float32Array(this.lookahead + 1);
    this.releaseStep = 1 - Math.exp(-1 / (sampleRate * RELEASE_SECONDS));
    this.reset();
  }

  reset() { this.head = 0; this.buffered = 0; this.gain = 1; }

  push(sample) {
    this.delay[(this.head + this.buffered) % this.delay.length] = sample;
    this.buffered++;
    return this.buffered === this.delay.length ? this.pop() : null;
  }

  pop() {
    let attackGain = 1;
    // Пик ограничивает только свой канал и не меняет громкость другого источника.
    for (let offset = 0; offset < this.buffered; offset++) {
      const peak = Math.abs(this.delay[(this.head + offset) % this.delay.length]);
      if (peak > PEAK_CEILING) {
        const target = PEAK_CEILING / peak;
        attackGain = Math.min(attackGain, target + (1 - target) * offset / this.lookahead);
      }
    }
    this.gain = Math.min(attackGain, this.gain + (1 - this.gain) * this.releaseStep);
    const result = this.delay[this.head] * this.gain;
    this.head = (this.head + 1) % this.delay.length;
    this.buffered--;
    return Math.max(-32768, Math.min(32767, Math.round(result * 32767)));
  }
}

class MonoPcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.phase = 'enroll';
    this.system = new PeakLimiter();
    this.microphone = new PeakLimiter();
    this.output = new ArrayBuffer(OUTPUT_SAMPLES * 4);
    this.view = new DataView(this.output);
    this.used = 0;
    this.port.onmessage = event => {
      if (event.data === 'enroll' || event.data === 'live') {
        this.phase = event.data;
        this.active = true;
        this.used = 0;
        this.system.reset();
        this.microphone.reset();
      } else if (event.data === 'stop') {
        this.active = false;
        // Оба буфера сбрасываются парами, чтобы последние сэмплы сохранили общее время.
        while (this.system.buffered) this.emit(this.system.pop(), this.microphone.pop());
        if (this.used) this.send();
        this.port.postMessage({type: 'stopped'});
      }
    };
  }

  send() {
    const stride = this.phase === 'live' ? 4 : 2;
    const buffer = this.output.slice(0, this.used * stride);
    this.port.postMessage({type: 'audio', buffer}, [buffer]);
    this.used = 0;
  }

  emit(system, mic) {
    const stride = this.phase === 'live' ? 4 : 2;
    this.view.setInt16(this.used * stride, system, true);
    if (this.phase === 'live') this.view.setInt16(this.used * stride + 2, mic, true);
    if (++this.used === OUTPUT_SAMPLES) this.send();
  }

  mono(channels, index) {
    if (!channels.length) return 0;
    let total = 0;
    for (const channel of channels) total += channel[index] || 0;
    return total / channels.length;
  }

  process(inputs, outputs) {
    const system = inputs[0] || [];
    const microphone = inputs[1] || [];
    if (this.active) {
      const frames = system[0]?.length || microphone[0]?.length || outputs[0]?.[0]?.length || 128;
      for (let i = 0; i < frames; i++) {
        const computer = this.system.push(this.mono(system, i) * SYSTEM_GAIN);
        const mic = this.microphone.push(this.phase === 'live' ? this.mono(microphone, i) * MICROPHONE_GAIN : 0);
        if (computer !== null) this.emit(computer, mic);
      }
    }
    for (const output of outputs) for (const channel of output) channel.fill(0);
    return true;
  }
}

registerProcessor('mono-pcm', MonoPcmWorklet);
