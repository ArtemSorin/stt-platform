(() => {
  const panel = document.getElementById('archive-result');
  let session = new URLSearchParams(location.search).get('session');
  let current = null;
  async function refresh() {
    if (!session || session === current) return;
    current = session;
    try {
      const response = await fetch('/api/interviews/' + encodeURIComponent(session));
      if (response.status === 404) { panel.hidden = true; return; }
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const data = await response.json();
      panel.hidden = false;
      document.getElementById('archive-version').textContent = data.catalogVersion;
      document.getElementById('archive-call').textContent = data.callStatus === 'finished' ? 'завершён' : 'идёт';
      document.getElementById('archive-audio').textContent = data.audioStatus === 'saved' ? 'сохранена' : data.audioStatus === 'error' ? 'ошибка проверки записи' : data.audioStatus === 'unavailable' ? 'хранилище недоступно' : 'ещё не сохранена';
      document.getElementById('archive-report').textContent = data.reportStatus === 'ready' ? 'готов' : data.reportStatus === 'unavailable' ? 'хранилище недоступно' : 'ещё не готов';
      const audio = document.getElementById('archive-audio-link');
      const audioKind = data.artifacts.includes('audio_wav') ? 'audio_wav' : 'audio_mono_wav';
      audio.hidden = data.audioStatus !== 'saved';
      audio.href = '/api/interviews/' + encodeURIComponent(session) + '/artifacts/' + audioKind;
      const report = document.getElementById('archive-report-link');
      report.hidden = data.reportStatus !== 'ready';
      report.href = '/api/interviews/' + encodeURIComponent(session) + '/artifacts/report_xlsx';
      document.getElementById('archive-link').href = location.pathname + '?session=' + encodeURIComponent(session);
    } catch (_) {
      if (!panel.hidden) document.getElementById('archive-audio').textContent = 'статус недоступен';
    } finally { current = null; }
  }
  window.archiveView = {update(id) { if (id) session = id; refresh(); }};
  setInterval(refresh, 5000);
  refresh();
})();
