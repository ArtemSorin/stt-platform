// Забирает звук с микрофона, понижает частоту до 16 кГц, переводит в Int16 и шлёт кадрами по ~50 мс.
class PcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;      // sampleRate — глобальная переменная контекста (обычно 48000)
    this.phase = 0;
    this.acc = 0;
    this.cnt = 0;
    this.out = new Int16Array(800);       // 50 мс при 16 кГц
    this.n = 0;
    this.active = false;
    this.port.onmessage = event => {
      if (event.data === 'start') { this.n = 0; this.active = true; }
      if (event.data === 'stop') {
        this.active = false;
        if (this.n) this.port.postMessage(this.out.buffer.slice(0, this.n * 2));
        this.n = 0;
        this.port.postMessage({type:'stopped'});
      }
    };
  }

  process(inputs) {
    if (!this.active) return true;
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      this.acc += ch[i];
      this.cnt++;
      this.phase += 1;
      if (this.phase >= this.ratio) {      // усреднение = простой НЧ-фильтр перед децимацией
        const s = Math.max(-1, Math.min(1, this.acc / this.cnt));
        this.out[this.n++] = s < 0 ? s * 0x8000 : s * 0x7fff;
        this.acc = 0;
        this.cnt = 0;
        this.phase -= this.ratio;
        if (this.n === this.out.length) {
          this.port.postMessage(this.out.buffer.slice(0));
          this.n = 0;
        }
      }
    }
    return true;
  }
}
registerProcessor('pcm-processor', PcmProcessor);
