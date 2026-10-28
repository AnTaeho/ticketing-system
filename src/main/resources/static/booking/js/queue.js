(function () {
  'use strict';

  var POLL_MS = 2000;
  var RETRY_MS = 3000;

  var pid = document.body.dataset.performanceId;
  var showId = bk.positiveId(bk.query('show'));
  var tokenKey = bk.keys.queueToken(pid);

  var wait = document.getElementById('q-wait');
  var ahead = document.getElementById('q-ahead');
  var eta = document.getElementById('q-eta');
  var meter = document.getElementById('q-meter');
  var fill = document.getElementById('q-fill');
  var start = document.getElementById('q-start');
  var note = document.getElementById('q-note');
  var sub = document.getElementById('q-sub');
  var stateBox = bk.stateBox('q-state');

  var token = bk.session.get(tokenKey);
  // 이 화면에 막 들어와 저장된 표를 처음 확인하는 중. 이미 쓴 표일 수 있어 끝났으면 말없이 새로 선다
  var arrived = !!token;
  var firstPosition = null;

  // 숨은 탭에서도 차례를 놓치지 않게 계속 묻는다. 차례가 되면 숨은 채로 좌석 화면으로 넘어간다
  var poller = bk.poller({ interval: POLL_MS, run: step, onData: stepDone, onError: stepFailed, keepWhenHidden: true });
  // 좌석 화면으로 넘어갈 때는 자리를 줄이면 안 되므로 떠남 신호를 보내지 않는다
  var toSeats = false;

  function showNote(message) {
    note.textContent = message || '';
    note.hidden = !message;
  }

  function showState(title, text, button) {
    wait.hidden = true;
    stateBox.show(title, text, button);
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
    stateBox.box.hidden = true;
    wait.hidden = false;
  }

  function forgetToken() {
    token = null;
    bk.session.remove(tokenKey);
  }

  // 표가 없으면 줄을 서고, 있으면 순서를 묻는다
  function step() {
    if (token) {
      return bk.get(bk.queueTokenPath(pid, token))
        .then(function (status) { return { status: status }; });
    }
    showNote('');
    showState('줄을 서고 있어요', '잠시만 기다려 주세요.');
    return bk.post('/api/performances/' + pid + '/queue/tokens')
      .then(function (entered) { return { entered: entered }; });
  }

  function stepDone(r) {
    if (r.entered) {
      token = r.entered.token;
      arrived = false;
      bk.session.set(tokenKey, token);
      if (r.entered.status === 'PROCESSING') admit();
      else poller.now();
      return;
    }
    var s = r.status;
    showNote('');
    if (s.status === 'PROCESSING') {
      admit();
    } else if (s.status === 'SOLD_OUT') {
      soldOut();
    } else if (s.status === 'EXPIRED') {
      if (arrived) { forgetToken(); poller.now(); } else expired();
    } else {
      arrived = false;
      showWaiting(s.position, s.estimatedWaitSeconds);
    }
  }

  function admit() {
    poller.stop();
    showNote('');
    showState('차례가 됐어요', '자리 고르는 화면으로 넘어가요.');
    toSeats = true;
    // 뒤로 가기가 이 화면에 다시 걸리지 않게 기록을 바꿔 넘어간다
    location.replace(bk.links.seats(pid, showId));
  }

  function expired() {
    poller.stop();
    forgetToken();
    showNote('');
    showState('입장 순서가 지났어요', '줄을 다시 서면 맨 뒤부터 기다려요.',
      { label: '다시 줄 서기', run: function () { firstPosition = null; poller.now(); } });
  }

  // 더 기다려도 고를 자리가 없으니 줄에서 빠지고 공연 정보로 돌려보낸다
  function soldOut() {
    poller.stop();
    bk.leaveQueue(pid);
    forgetToken();
    showNote('');
    showState('매진됐어요', '모든 자리가 팔렸어요.', { label: '공연 정보로 돌아가기', run: backToShow });
  }

  function backToShow() {
    location.href = bk.links.show(showId);
  }

  // 사람이 많거나 연결이 끊기면 지금 화면을 두고 잠시 뒤 같은 일을 다시 한다
  function retryLater(e) {
    var network = bk.isNetworkError(e);
    var words = network ? bk.WORDS.network : bk.WORDS.busy;
    if (wait.hidden) {
      showState(words, '잠시 뒤 저절로 다시 이어 가요.');
    } else {
      showNote(words + (network ? '. 연결되면 이어서 기다려요' : '. 잠시 뒤 저절로 다시 이어 가요'));
    }
    poller.later(RETRY_MS);
  }

  function stepFailed(e) {
    // 다른 닉네임으로 받은 토큰 등 쓸 수 없는 토큰이면 버리고 새로 선다
    if (token && e.code === 'QUEUE_TOKEN_NOT_FOUND') {
      forgetToken();
      poller.now();
      return;
    }
    if (e.status === 503 || bk.isNetworkError(e)) {
      retryLater(e);
      return;
    }
    if (e.code === 'SOLD_OUT') {
      soldOut();
      return;
    }
    poller.stop();
    showNote('');
    if (e.code === 'NOT_OPEN' || e.code === 'PERFORMANCE_NOT_FOUND') {
      showState('지금은 줄을 설 수 없어요', e.message, { label: '공연 정보로 돌아가기', run: backToShow });
      return;
    }
    showState('줄을 서지 못했어요', e.message, { label: '다시 시도', run: poller.now });
  }

  bk.loadPerformanceHeader(showId, pid).then(function (found) {
    if (!found || !found.performance) return;
    sub.textContent = bk.dateTime(found.performance.startsAt) + ' 회차 줄이에요';
    sub.hidden = false;
  });
  window.addEventListener('pagehide', function () { if (!toSeats) bk.leaveQueue(pid); });
  poller.now();
})();
