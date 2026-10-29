const SYSTEM_GAIN = 1;
const MICROPHONE_GAIN = 0.8;
const PEAK_CEILING = 0.9;
const LOOKAHEAD_SECONDS = 0.003;
const RELEASE_SECONDS = 0.08;
const OUTPUT_SAMPLES = 2560; // 160 мс при 16 кГц; четыре кадра объединяются для Nemotron.

class MonoPcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.phase = 'enroll';
    this.output = new Int16Array(OUTPUT_SAMPLES);
    this.used = 0;
    this.lookaheadSamples = Math.max(1, Math.round(sampleRate * LOOKAHEAD_SECONDS));
    this.delay = new Float32Array(this.lookaheadSamples + 1);
    this.releaseStep = 1 - Math.exp(-1 / (sampleRate * RELEASE_SECONDS));
    this.resetLimiter();
    this.port.onmessage = event => {
      if (event.data === 'enroll' || event.data === 'live') {
        this.phase = event.data;
        this.active = true;
        this.used = 0;
        this.resetLimiter();
      } else if (event.data === 'stop') {
        this.active = false;
        // Выдаём ожидающие сэмплы до подтверждения остановки, чтобы не потерять конец речи.
        while (this.buffered) this.emitOldest();
        if (this.used) this.send();
        this.port.postMessage({type: 'stopped'});
      }
    };
  }

  resetLimiter() {
    this.head = 0;
    this.buffered = 0;
    this.gain = 1;
  }

  send() {
    const buffer = this.output.buffer.slice(0, this.used * 2);
    this.port.postMessage({type: 'audio', buffer}, [buffer]);
    this.used = 0;
  }

  mono(channels, index) {
    if (!channels.length) return 0;
    let total = 0;
    for (const channel of channels) total += channel[index] || 0;
    return total / channels.length;
  }

  enqueue(sample) {
    this.delay[(this.head + this.buffered) % this.delay.length] = sample;
    this.buffered++;
    if (this.buffered === this.delay.length) this.emitOldest();
  }

  emitOldest() {
    let attackGain = 1;
    // Каждый будущий пик задаёт плавный спуск усиления; к самому пику оно достигает нужного уровня.
    for (let offset = 0; offset < this.buffered; offset++) {
      const peak = Math.abs(this.delay[(this.head + offset) % this.delay.length]);
      if (peak > PEAK_CEILING) {
        const target = PEAK_CEILING / peak;
        const ramp = target + (1 - target) * offset / this.lookaheadSamples;
        attackGain = Math.min(attackGain, ramp);
      }
    }
    // После пика усиление возвращается к единице медленнее, чем уменьшается перед пиком.
    this.gain = Math.min(attackGain, this.gain + (1 - this.gain) * this.releaseStep);
    const limited = this.delay[this.head] * this.gain;
    this.output[this.used++] = Math.max(-32768, Math.min(32767, Math.round(limited * 32767)));
    this.head = (this.head + 1) % this.delay.length;
    this.buffered--;
    if (this.used === this.output.length) this.send();
  }

  process(inputs, outputs) {
    const system = inputs[0] || [];
    const microphone = inputs[1] || [];
    if (this.active) {
      const frames = system[0]?.length || microphone[0]?.length || outputs[0]?.[0]?.length || 128;
      for (let i = 0; i < frames; i++) {
        const computer = this.mono(system, i) * SYSTEM_GAIN;
        // Эталон записывается только из звука компьютера, где слышен кандидат.
        const mic = this.phase === 'live' ? this.mono(microphone, i) * MICROPHONE_GAIN : 0;
        this.enqueue(computer + mic);
      }
    }
    for (const output of outputs) for (const channel of output) channel.fill(0);
    return true;
  }
}

registerProcessor('mono-pcm', MonoPcmWorklet);
