(() => {
  const el = id => document.getElementById(id);
  const panel = el('transcript-result'), actions = el('transcript-actions');
  let urls, loadedKey = '', requestNumber = 0;
  async function show(kind) {
    const number = ++requestNumber;
    try {
      const response = await fetch(urls[kind]);
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const value = await response.text();
      if (number === requestNumber) {
        el('transcript-full').textContent = value || 'Речь не распознана.';
        actions.hidden = false;
        el('transcript-result-state').textContent = value.includes('(резерв Vosk)') || value.includes('[Аудио этого участка не распознано]')
          ? 'Транскрипция готова с резервом или нераспознанными участками. Проверьте отмеченные фрагменты.'
          : 'Транскрипция готова.';
      }
    } catch (error) {
      if (number === requestNumber) { loadedKey = ''; actions.hidden = true; el('transcript-result-state').textContent = 'Не удалось прочитать транскрипцию: ' + error.message; }
    }
  }
  function ready(id, value) {
    const key = id + ':' + value.zip;
    if (key === loadedKey) return;
    loadedKey = key; urls = value; panel.hidden = false; actions.hidden = true;
    el('transcript-result-state').textContent = 'Загружаем транскрипцию.';
    el('transcript-zip').href = value.zip;
    el('transcript-link').href = location.pathname + '?session=' + encodeURIComponent(id);
    show('timed');
  }
  el('transcript-show-timed').onclick = () => show('timed');
  el('transcript-show-plain').onclick = () => show('plain');
  window.transcriptView = {
    update(phase, value, error, id, progress) {
      if (value && id) ready(id, value);
      else if (error) { panel.hidden = false; actions.hidden = true; el('transcript-result-state').textContent = error; }
      else if (phase === 'stopping') {
        panel.hidden = false; actions.hidden = true;
        el('transcript-result-state').textContent = progress
          ? `Whisper обрабатывает запись: ${progress.done} из ${progress.total} каналов.`
          : 'Готовим полную транскрипцию после интервью.';
      }
      else if (phase === 'live' || phase === 'enrolling' || phase === 'preparing') {
        ++requestNumber; loadedKey = ''; panel.hidden = true; actions.hidden = true;
      }
    }
  };
  const id = new URLSearchParams(location.search).get('session');
  if (id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) {
    const base = '/api/interviews/' + id + '/transcripts/';
    ready(id, {plain:base+'transcript.md', timed:base+'transcript-timed.md', zip:base+'transcripts.zip'});
  }
})();
