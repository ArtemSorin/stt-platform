const $ = id => document.getElementById(id);
let stream, context, source, worklet, sessionId;
let mode = 'idle', captureEnabled = false, sequence = 0, pending = Promise.resolve();
let polling = false, questionKey = '', scheduledQuestion = '', questionTimer, finishRequested = false;

async function request(path, body, headers = {}) {
  const response = await fetch(path, {method: body === undefined ? 'GET' : 'POST', body,
    headers: {...headers, ...(sessionId ? {'X-Session-Id': sessionId} : {})}});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function showError(message) { $('error').textContent = message; $('error').classList.remove('hidden'); }
function log(message) {
  const row = document.createElement('div');
  row.textContent = `${new Date().toLocaleTimeString()} · ${message}`;
  $('log').prepend(row);
  while ($('log').children.length > 30) $('log').lastElementChild.remove();
}
function questionId(question) { return `${question.number}|${question.followUpNumber}|${question.text}`; }
function cleanup() {
  captureEnabled = false;
  clearTimeout(questionTimer);
  stream?.getTracks().forEach(track => { track.onended = null; track.stop(); });
  source?.disconnect(); worklet?.disconnect();
  if (context && context.state !== 'closed') context.close();
  if ('speechSynthesis' in window) speechSynthesis.cancel();
  stream = context = source = worklet = undefined;
}
async function start() {
  $('start').disabled = true;
  $('error').classList.add('hidden'); $('log').replaceChildren();
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('Откройте страницу на localhost в Chrome или Edge.');
    stream = await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
    context = new AudioContext();
    await context.audioWorklet.addModule('/stt-pcm-worklet.js');
    source = context.createMediaStreamSource(stream);
    worklet = new AudioWorkletNode(context, 'pcm-processor');
    worklet.port.onmessage = event => {
      if (!captureEnabled || mode !== 'live') return;
      const number = sequence++;
      pending = pending.then(() => request('/api/audio', event.data, {'X-Sequence':String(number)}));
      pending.catch(error => { showError(error.message); finish('capture_error'); });
    };
    const mute = context.createGain(); mute.gain.value = 0;
    source.connect(worklet).connect(mute).connect(context.destination);
    await context.resume();
    const state = await request('/api/test/start', JSON.stringify({candidate:$('candidate').value.trim(),
      whisperEnabled:$('whisper').checked}), {'Content-Type':'application/json'});
    sessionId = state.session_id; sequence = 0; mode = 'live'; finishRequested = false;
    $('setup').classList.add('hidden'); $('controls').classList.remove('hidden');
    $('interview').classList.remove('hidden'); $('finish').disabled = false;
    stream.getAudioTracks()[0].onended = () => finish('microphone_lost');
    log('Тест запущен: весь микрофонный звук считается речью кандидата.');
  } catch (error) {
    showError(error.message); cleanup(); mode = 'idle'; $('start').disabled = false;
  }
}
async function askQuestion(question) {
  if (mode !== 'live' || !question || scheduledQuestion === questionKey) return;
  scheduledQuestion = questionKey;
  $('skip').disabled = true; $('asked').disabled = true;
  if (!$('tts').checked || !('speechSynthesis' in window)) {
    $('asked').classList.remove('hidden'); $('asked').disabled = false;
    $('status').textContent = 'Задайте вопрос вслух и нажмите кнопку, чтобы начать ответ.';
    return;
  }
  $('asked').classList.add('hidden'); captureEnabled = false;
  $('status').textContent = 'Озвучивается вопрос';
  const utterance = new SpeechSynthesisUtterance(question.spoken || question.text);
  utterance.lang = 'ru-RU';
  utterance.onstart = () => request('/api/tts/event', JSON.stringify({event:'start',question:question.text}),
    {'Content-Type':'application/json'}).catch(error => showError(error.message));
  const ready = async event => {
    if (mode !== 'live') return;
    request('/api/tts/event', JSON.stringify({event,question:question.text}), {'Content-Type':'application/json'})
      .catch(error => showError(error.message));
    try {
      await pending;
      await request('/api/question/asked', new Uint8Array());
      captureEnabled = true; $('status').textContent = 'Слушаю ответ'; $('skip').disabled = false;
      log('Вопрос задан, начался приём ответа.');
    } catch (error) { showError(error.message); }
  };
  utterance.onend = () => ready('finish');
  utterance.onerror = () => ready('error');
  speechSynthesis.cancel(); speechSynthesis.speak(utterance);
}
function render(state) {
  const phase = state.phase || 'idle', integration = state.integration || {};
  $('status').textContent = ({live:'Интервью идёт · без диаризации',stopping:'Формируется отчёт',finished:'Интервью завершено',error:'Ошибка обработки'})[phase] || phase;
  if (state.error || integration.bridge_error) showError(state.error || integration.bridge_error);
  $('partial').textContent = integration.mixed_partial || 'Ожидание речи';
  $('preview').textContent = integration.preview
    ? `${integration.preview.action || 'Подсказка'} · ${integration.preview.reason || ''} · покрытие ${integration.preview.coveragePercent ?? '—'}%`
    : 'Пока нет предварительной подсказки';
  $('decision').textContent = integration.decision
    ? `${integration.decision.action || 'Решение'} · ${integration.decision.reason || ''} · ${integration.decision.recognized || ''}`
    : integration.decision_retracted ? 'Решение отменено: ответ продолжается.' : 'Ожидание ответа';
  $('whisper-state').textContent = integration.refined?.text
    ? `Уточнённый текст: ${integration.refined.text}` : `Состояние: ${integration.whisper_state || 'ожидание'}`;
  const question = integration.question;
  if (question) {
    const key = questionId(question);
    $('question-meta').textContent = `Вопрос ${question.number || ''} · ${question.level || ''} · ${question.topic || ''}`;
    $('question').textContent = question.text || '';
    if (key !== questionKey) {
      questionKey = key; scheduledQuestion = ''; clearTimeout(questionTimer);
      log(`Поступил вопрос ${question.number || ''}.`);
    }
    const wait = integration.question_wait_ms || 0;
    if (integration.decision_retracted) {
      clearTimeout(questionTimer); scheduledQuestion = key; captureEnabled = true;
      $('status').textContent = 'Ответ продолжается; решение отменено.';
    } else if (wait > 0 && scheduledQuestion !== key) {
      $('status').textContent = `Ожидание окна отмены: ${(wait / 1000).toFixed(1)} с`;
      scheduledQuestion = key;
      questionTimer = setTimeout(() => { scheduledQuestion = ''; askQuestion(question); }, wait + 30);
    } else if (wait === 0 && scheduledQuestion !== key) askQuestion(question);
  }
  if (phase === 'finished' || phase === 'error') {
    cleanup(); mode = 'done'; $('finish').disabled = true; $('skip').disabled = true;
    if (integration.report) {
      const panel = $('report');
      panel.replaceChildren(document.createTextNode(`Отчёт интервью\n${JSON.stringify(integration.report, null, 2)}`));
      if (integration.excel?.url) {
        const link = document.createElement('a');
        link.href = integration.excel.url;
        link.textContent = `Скачать XLSX-отчёт${integration.excel.file ? ` · ${integration.excel.file}` : ''}`;
        panel.append(document.createElement('br'), link);
      }
      panel.classList.remove('hidden');
    }
    $('start').disabled = false;
  }
}
async function poll() {
  if (polling || !sessionId || mode === 'idle' || mode === 'done') return;
  polling = true;
  try {
    const state = await request('/api/status');
    if (state.session_id === sessionId) render(state);
  } catch (error) { showError(error.message); }
  finally { polling = false; }
}
async function finish(reason = 'requested') {
  if (mode !== 'live' || finishRequested) return;
  finishRequested = true; mode = 'stopping'; captureEnabled = false;
  $('finish').disabled = true; $('status').textContent = 'Завершение и ожидание отчёта';
  try {
    await pending;
    await request('/api/finish', new Uint8Array(), {'X-Stop-Reason':reason});
    cleanup(); mode = 'stopping';
  } catch (error) { showError(error.message); cleanup(); mode = 'done'; $('start').disabled = false; }
}
$('start').onclick = start;
$('finish').onclick = () => finish();
$('asked').onclick = async () => {
  $('asked').disabled = true;
  try { await pending; await request('/api/question/asked', new Uint8Array()); captureEnabled = true;
    $('asked').classList.add('hidden'); $('skip').disabled = false; $('status').textContent = 'Слушаю ответ'; }
  catch (error) { showError(error.message); $('asked').disabled = false; }
};
$('skip').onclick = async () => {
  captureEnabled = false; $('skip').disabled = true;
  try { await pending; await request('/api/question/skip', new Uint8Array()); log('Вопрос пропущен.'); }
  catch (error) { showError(error.message); }
};
setInterval(poll, 250);
