(function () {
  'use strict';

  var POLL_MS = 2000;
  var RETRY_MS = 3000;

  var pid = document.body.dataset.performanceId;
  var showId = bk.positiveId(bk.query('show'));
  var tokenKey = 'bk_queue_' + pid;
  var showHref = showId ? '/booking/shows/' + showId : '/booking/shows';
  var seatsHref = '/booking/performances/' + pid + '/seats' + (showId ? '?show=' + showId : '');

  var wait = document.getElementById('q-wait');
  var ahead = document.getElementById('q-ahead');
  var eta = document.getElementById('q-eta');
  var meter = document.getElementById('q-meter');
  var fill = document.getElementById('q-fill');
  var start = document.getElementById('q-start');
  var state = document.getElementById('q-state');
  var stateTitle = document.getElementById('q-state-title');
  var stateText = document.getElementById('q-state-text');
  var action = document.getElementById('q-action');
  var note = document.getElementById('q-note');
  var sub = document.getElementById('q-sub');

  var firstPosition = null;
  var timer = null;
  var onAction = null;

  action.addEventListener('click', function () { if (onAction) onAction(); });

  function later(fn, ms) {
    clearTimeout(timer);
    timer = setTimeout(fn, ms);
  }

  function showNote(message) {
    note.textContent = message || '';
    note.hidden = !message;
  }

  function showState(title, text, button) {
    wait.hidden = true;
    state.hidden = false;
    stateTitle.textContent = title;
    stateText.textContent = text || '';
    stateText.hidden = !text;
    onAction = button ? button.run : null;
    action.hidden = !button;
    if (button) action.textContent = button.label;
  }

  // position은 나를 포함한 순번이라 내 앞 사람 수는 하나 적다
  function showWaiting(position, seconds) {
    var front = Math.max(0, position - 1);
    if (firstPosition === null || front > firstPosition) firstPosition = front;
    var done = firstPosition > 0 ? (firstPosition - front) / firstPosition * 100 : 100;
    ahead.textContent = bk.count(front) + '명';
    eta.textContent = seconds < 60 ? '곧 들어가요' : '약 ' + Math.round(seconds / 60) + '분';
    start.textContent = '줄 선 때 ' + bk.count(firstPosition) + '명';
    fill.style.width = done.toFixed(1) + '%';
    meter.setAttribute('aria-valuenow', String(Math.round(done)));
    state.hidden = true;
    wait.hidden = false;
  }

  function admit() {
    clearTimeout(timer);
    showNote('');
    showState('차례가 됐어요', '자리 고르는 화면으로 넘어가요.');
    // 뒤로 가기가 이 화면에 다시 걸리지 않게 기록을 바꿔 넘어간다
    location.replace(seatsHref);
  }

  function expired() {
    clearTimeout(timer);
    bk.session.remove(tokenKey);
    showNote('');
    showState('입장 순서가 지났어요', '줄을 다시 서면 맨 뒤부터 기다려요.',
      { label: '다시 줄 서기', run: function () { firstPosition = null; enter(); } });
  }

  // 사람이 많거나 연결이 끊기면 지금 화면을 두고 잠시 뒤 같은 일을 다시 한다
  function retryLater(e, retry) {
    var message = e.code === 'NETWORK'
      ? '인터넷 연결이 불안정해요. 연결되면 이어서 기다려요'
      : '사람이 많아 잠시 막혔어요. 잠시 뒤 저절로 다시 이어 가요';
    if (wait.hidden) {
      showState(e.code === 'NETWORK' ? '인터넷 연결이 불안정해요' : '사람이 많아 잠시 막혔어요',
        '잠시 뒤 저절로 다시 이어 가요.');
    } else {
      showNote(message);
    }
    later(retry, RETRY_MS);
  }

  function fail(e, retry) {
    if (e.status === 503 || e.code === 'NETWORK') {
      retryLater(e, retry);
      return;
    }
    clearTimeout(timer);
    showNote('');
    if (e.code === 'NOT_OPEN' || e.code === 'PERFORMANCE_NOT_FOUND') {
      showState('지금은 줄을 설 수 없어요', e.message,
        { label: '공연 정보로 돌아가기', run: function () { location.href = showHref; } });
      return;
    }
    showState('줄을 서지 못했어요', e.message, { label: '다시 시도', run: retry });
  }

  // arrived: 이 화면에 막 들어와 저장된 표를 처음 확인하는 중. 이미 쓴 표일 수 있어 끝났으면 말없이 새로 선다
  function poll(token, arrived) {
    bk.get('/api/performances/' + pid + '/queue/tokens/' + encodeURIComponent(token)).then(function (s) {
      showNote('');
      if (s.status === 'PROCESSING') return admit();
      if (s.status === 'EXPIRED') {
        if (!arrived) return expired();
        bk.session.remove(tokenKey);
        return enter();
      }
      showWaiting(s.position, s.estimatedWaitSeconds);
      later(function () { poll(token, false); }, POLL_MS);
    }, function (e) {
      // 다른 닉네임으로 받은 토큰 등 쓸 수 없는 토큰이면 버리고 새로 선다
      if (e.code === 'QUEUE_TOKEN_NOT_FOUND') {
        bk.session.remove(tokenKey);
        return enter();
      }
      fail(e, function () { poll(token, arrived); });
    });
  }

  function enter() {
    showNote('');
    showState('줄을 서고 있어요', '잠시만 기다려 주세요.');
    bk.post('/api/performances/' + pid + '/queue/tokens').then(function (r) {
      bk.session.set(tokenKey, r.token);
      if (r.status === 'PROCESSING') return admit();
      poll(r.token, false);
    }, function (e) {
      fail(e, enter);
    });
  }

  function loadHeader() {
    bk.setHeader(null, null, showHref);
    if (!showId) return;
    bk.get('/api/shows/' + showId).then(function (show) {
      var performance = (show.performances || []).filter(function (p) { return String(p.id) === pid; })[0];
      if (!performance) {
        bk.setHeader(show.title, show.venueName, showHref);
        return;
      }
      var when = bk.dateTime(performance.startsAt);
      bk.setHeader(show.title, when + ' · ' + show.venueName, showHref);
      sub.textContent = when + ' 회차 줄이에요';
      sub.hidden = false;
    }, function () { /* 머리글은 없어도 줄은 선다 */ });
  }

  loadHeader();
  var saved = bk.session.get(tokenKey);
  if (saved) poll(saved, true); else enter();
})();
