(function () {
  'use strict';

  var POLL_MS = 2000;
  var RECHECK_MS = 2000;
  var SLOW_AFTER_MS = 2 * 60 * 1000;
  var PAY_TIMEOUT_MS = 15000;
  var URGENT_SECONDS = 60;
  var SHOWS_HREF = '/booking/shows';
  var BOOKINGS_HREF = '/booking/bookings';
  var METHOD_NAMES = { CARD: '신용·체크카드', EASY_PAY: '간편결제' };

  var body = document.body;
  var holdId = body.dataset.holdId;
  var demoPg = body.dataset.demoPg === 'true';
  var showId = bk.positiveId(bk.query('show'));
  var showHref = showId ? '/booking/shows/' + showId : SHOWS_HREF;
  var keyName = 'bk_idem_' + holdId;
  var methodName = 'bk_method_' + holdId;
  var el = bk.el;

  var back = document.getElementById('page-back');
  var clock = document.getElementById('clock');
  var clockMain = document.getElementById('clock-main');
  var clockSub = document.getElementById('clock-sub');
  var loading = document.getElementById('co-loading');
  var co = document.getElementById('co');
  var seatLines = document.getElementById('seat-lines');
  var amountRows = document.getElementById('amount-rows');
  var total = document.getElementById('total');
  var payBar = document.getElementById('pay-bar');
  var coNote = document.getElementById('co-note');
  var actions = document.getElementById('actions');
  var payButton = document.getElementById('pay');
  var releaseButton = document.getElementById('release');
  var ask = document.getElementById('ask');
  var askNo = document.getElementById('ask-no');
  var askYes = document.getElementById('ask-yes');
  var pend = document.getElementById('pend');
  var pendNote = document.getElementById('pend-note');
  var pendLead = document.getElementById('pend-lead');
  var pendFacts = document.getElementById('pend-facts');
  var pendAfter = document.getElementById('pend-after');
  var result = document.getElementById('result');
  var resultTitle = document.getElementById('result-title');
  var resultText = document.getElementById('result-text');
  var resultTicket = document.getElementById('result-ticket');
  var resultNumber = document.getElementById('result-number');
  var resultFacts = document.getElementById('result-facts');
  var resultMain = document.getElementById('result-main');
  var resultSub = document.getElementById('result-sub');

  // loading → pay → paying → pending / result
  var stage = 'loading';
  var hold = null;
  var headerDone = false;
  var deadlineAt = 0;
  var timeUp = false;
  var tickTimer = null;
  var recheckTimer = null;
  var pollTimer = null;
  var pollSeq = 0;
  var pendingPaymentId = null;
  var pendingSince = 0;
  var memoryKey = null;

  // 브라우저 시계를 바꿔도 흔들리지 않는 시계
  function mono() {
    return window.performance && performance.now ? performance.now() : Date.now();
  }

  // ---- 결제 요청 번호: 같은 자리의 재시도는 같은 번호로 보낸다 ----

  function uuid() {
    var c = window.crypto;
    if (c && typeof c.randomUUID === 'function') {
      try { return c.randomUUID(); } catch (e) { /* http 주소에서는 막혀 있어 아래로 만든다 */ }
    }
    var b = new Uint8Array(16);
    if (c && typeof c.getRandomValues === 'function') c.getRandomValues(b);
    else for (var i = 0; i < 16; i++) b[i] = Math.floor(Math.random() * 256);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var hex = Array.prototype.map.call(b, function (x) { return (x < 16 ? '0' : '') + x.toString(16); }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  // 저장소를 못 쓰면 이 화면이 떠 있는 동안만 같은 번호를 쓴다
  function idempotencyKey() {
    var key = bk.session.get(keyName) || memoryKey;
    if (!key) {
      key = uuid();
      bk.session.set(keyName, key);
    }
    memoryKey = key;
    return key;
  }

  // ---- 글 만들기 ----

  function byPlace(a, b) {
    if (a.section !== b.section) return a.section < b.section ? -1 : 1;
    return a.rowNo - b.rowNo || a.seatNo - b.seatNo;
  }

  // [{grade, count, price, sum}] 비싼 등급부터
  function grades(seats) {
    var map = {};
    seats.forEach(function (s) {
      var g = map[s.grade] || (map[s.grade] = { grade: s.grade, count: 0, price: s.price, sum: 0 });
      g.count++;
      g.sum += s.price;
    });
    return Object.keys(map).map(function (k) { return map[k]; }).sort(function (a, b) {
      return b.price - a.price || (a.grade < b.grade ? -1 : 1);
    });
  }

  // R석 2매 · S석 1매
  function gradesText(seats) {
    return grades(seats).map(function (g) { return g.grade + '석 ' + g.count + '매'; }).join(' · ');
  }

  // A구역 3열 9번, 10번 / B구역 1열 2번
  function placesText(seats) {
    var groups = [];
    seats.slice().sort(byPlace).forEach(function (s) {
      var last = groups[groups.length - 1];
      if (last && last.section === s.section && last.rowNo === s.rowNo) last.nos.push(s.seatNo);
      else groups.push({ section: s.section, rowNo: s.rowNo, nos: [s.seatNo] });
    });
    return groups.map(function (g) {
      return g.section + '구역 ' + g.rowNo + '열 ' + g.nos.map(function (n) { return n + '번'; }).join(', ');
    }).join(' / ');
  }

  // rows: [{k, v, small, num}]
  function fillFacts(dl, rows) {
    dl.textContent = '';
    rows.forEach(function (row) {
      if (!row) return;
      dl.appendChild(el('div', null,
        el('dt', { text: row.k }),
        el('dd', { class: row.num ? 'num' : null }, row.v, row.small ? el('small', { text: row.small }) : null)));
    });
  }

  function seatFacts(withMethod) {
    var seats = hold ? hold.seats || [] : [];
    var method = METHOD_NAMES[bk.session.get(methodName)];
    return [
      seats.length ? { k: '자리', v: placesText(seats), small: gradesText(seats) } : null,
      hold ? { k: '결제 금액', v: bk.won(hold.totalAmount), num: true } : null,
      withMethod && method ? { k: '결제 수단', v: method } : null
    ];
  }

  // ---- 화면 전환 ----

  function setBack(href) {
    if (!back) return;
    back.hidden = !href;
    if (href) back.setAttribute('href', href);
  }

  function stopTimers() {
    clearTimeout(tickTimer);
    clearTimeout(recheckTimer);
    clearTimeout(pollTimer);
    pollSeq++;
  }

  function showOnly(node) {
    [loading, co, pend, pendAfter, result].forEach(function (n) { n.hidden = n !== node; });
    if (node === pend) pendAfter.hidden = false;
    clock.hidden = node !== co;
    body.classList.toggle('is-narrow', node !== co);
  }

  function queueHref() {
    if (!hold) return SHOWS_HREF;
    return '/booking/performances/' + hold.performanceId + '/queue' + (showId ? '?show=' + showId : '');
  }

  function forgetQueueToken() {
    if (hold) bk.session.remove('bk_queue_' + hold.performanceId);
  }

  function setLink(node, action) {
    node.hidden = !action;
    if (!action) return;
    node.textContent = action.label;
    node.setAttribute('href', action.href);
    node.onclick = action.before || null;
  }

  // 결제가 끝난 화면. 줄 서기부터 다시 하는 단추는 남은 대기 표를 지우고 간다
  function showResult(opts) {
    stopTimers();
    stage = 'result';
    resultTitle.textContent = opts.title;
    resultText.textContent = opts.text || '';
    resultTicket.hidden = !opts.number;
    resultNumber.textContent = opts.number || '';
    fillFacts(resultFacts, opts.facts || []);
    setLink(resultMain, opts.main);
    setLink(resultSub, opts.sub);
    showOnly(result);
    setBack(showHref);
    window.scrollTo(0, 0);
  }

  function repick() {
    return { label: '자리 다시 고르기', href: queueHref(), before: forgetQueueToken };
  }

  function showsLink() {
    return { label: '공연 목록', href: SHOWS_HREF };
  }

  function showDone(number) {
    showResult({
      title: '예매가 끝났어요',
      text: number ? '내 예매에서 언제든 다시 볼 수 있어요.' : '예매번호는 내 예매에서 볼 수 있어요.',
      number: number,
      facts: seatFacts(false),
      main: { label: '내 예매 보기', href: BOOKINGS_HREF },
      sub: showsLink()
    });
  }

  function showDeclined() {
    showResult({
      title: '결제가 거절됐어요. 자리는 풀렸어요',
      text: '다시 예매하려면 줄을 서서 자리를 골라 주세요.',
      main: repick(), sub: showsLink()
    });
  }

  function showExpired() {
    showResult({
      title: '결제 시간이 지나 자리가 풀렸어요',
      text: '놓친 자리는 10분 뒤에 다시 고를 수 있어요. 다른 자리는 지금 고를 수 있어요.',
      main: repick(), sub: showsLink()
    });
  }

  function showSeatLost() {
    showResult({
      title: '결제는 됐지만 자리를 지키지 못했어요',
      text: '결제 금액은 자동으로 돌려드려요.',
      main: repick(), sub: showsLink()
    });
  }

  function showCancelled() {
    showResult({
      title: '결제가 취소돼 자리가 풀렸어요',
      text: '다시 예매하려면 줄을 서서 자리를 골라 주세요.',
      main: repick(), sub: showsLink()
    });
  }

  function showReleased() {
    showResult({
      title: '이미 놓은 자리예요',
      text: '다시 예매하려면 줄을 서서 자리를 골라 주세요.',
      main: repick(), sub: showsLink()
    });
  }

  function showNotFound() {
    showResult({
      title: '찾을 수 없는 자리예요',
      text: '공연 목록에서 다시 골라 주세요.',
      main: showsLink()
    });
  }

  function showLoadFailed(e) {
    showResult({
      title: '결제 정보를 불러오지 못했어요',
      text: e.code === 'NETWORK' ? bk.NETWORK_MESSAGE : '잠시 뒤 다시 불러와 주세요.',
      main: { label: '다시 불러오기', href: location.pathname + location.search },
      sub: showsLink()
    });
  }

  // ---- 결제 화면 ----

  function renderOrder() {
    var seats = (hold.seats || []).slice().sort(byPlace);
    seatLines.textContent = '';
    seats.forEach(function (s) {
      seatLines.appendChild(el('div', { class: 'seat-line' },
        el('strong', { text: s.section + '구역 ' + s.rowNo + '열 ' + s.seatNo + '번' }),
        el('span', { class: 'num', text: s.grade + '석 ' + bk.won(s.price) })));
    });
    amountRows.textContent = '';
    grades(seats).forEach(function (g) {
      amountRows.appendChild(el('div', { class: 'row' },
        el('span', { class: 'k', text: g.grade + '석 ' + g.count + '매' }),
        el('span', { class: 'num', text: bk.won(g.sum) })));
    });
    total.textContent = bk.won(hold.totalAmount);
  }

  // off는 다시 누를 수 없는 끝난 상태다. 요청 중인 꺼짐과 모양을 나눈다
  function setPayButton(label, enabled, off) {
    payButton.textContent = label;
    payButton.disabled = !enabled;
    payButton.classList.toggle('is-off', !!off);
  }

  function showCoNote(message) {
    coNote.textContent = message || '';
    coNote.hidden = !message;
  }

  function closeAsk() {
    ask.hidden = true;
    actions.hidden = false;
    askYes.disabled = false;
    askNo.disabled = false;
    askYes.textContent = '예, 놓을게요';
  }

  function paintClock(text) {
    clockMain.textContent = '';
    if (text) {
      clockMain.textContent = text;
      clockSub.textContent = '';
      clockSub.hidden = true;
      return;
    }
    clockMain.appendChild(el('b', { class: 'num', id: 'clock-left' }));
    clockMain.appendChild(document.createTextNode(' 안에 결제해 주세요'));
    clockSub.textContent = '지나면 자리가 풀려요';
    clockSub.hidden = false;
  }

  // 남은 시간은 서버가 준 두 시각의 차이로만 정하고 이 화면의 시계로 줄여 간다
  function startClock() {
    clearTimeout(tickTimer);
    deadlineAt = mono() + (bk.epochOf(hold.expiresAt) - bk.epochOf(hold.serverNow));
    timeUp = false;
    paintClock(null);
    tick();
  }

  function tick() {
    clearTimeout(tickTimer);
    var left = deadlineAt - mono();
    var seconds = Math.ceil(left / 1000);
    if (seconds <= 0) {
      // 결제 요청이 나가 있는 동안에는 서버가 결제 마감으로 바꿨을 수 있어 기다린다
      if (stage === 'pay') expire();
      return;
    }
    var shown = document.getElementById('clock-left');
    if (shown) shown.textContent = bk.clockText(seconds * 1000);
    clock.classList.toggle('urgent', seconds < URGENT_SECONDS);
    tickTimer = setTimeout(tick, (left % 1000) + 5);
  }

  // 실제로 푸는 일은 서버가 한다. 화면은 단추를 끄고 서버 상태를 다시 본다
  function expire() {
    timeUp = true;
    clock.classList.add('urgent');
    paintClock('결제 시간이 지나 자리가 풀렸어요');
    closeAsk();
    showCoNote('');
    setPayButton('결제 시간이 지났어요', false, true);
    releaseButton.disabled = true;
    clearTimeout(recheckTimer);
    recheckTimer = setTimeout(reload, RECHECK_MS);
  }

  function showPay(note) {
    clearTimeout(pollTimer);
    clearTimeout(recheckTimer);
    stage = 'pay';
    renderOrder();
    closeAsk();
    showCoNote(note || '');
    setPayButton('결제하기', true);
    releaseButton.disabled = false;
    showOnly(co);
    setBack(showHref);
    idempotencyKey();
    startClock();
  }

  function scenario() {
    var picked = document.querySelector('input[name="scenario"]:checked');
    return picked ? picked.value : null;
  }

  function method() {
    var picked = document.querySelector('input[name="method"]:checked');
    return picked ? picked.value : 'CARD';
  }

  payButton.addEventListener('click', function () {
    if (stage !== 'pay' || timeUp) return;
    stage = 'paying';
    closeAsk();
    showCoNote('');
    setPayButton('결제하고 있어요', false);
    releaseButton.disabled = true;
    setBack(null);
    var chosen = method();
    bk.session.set(methodName, chosen);
    var headers = { 'Idempotency-Key': idempotencyKey() };
    if (demoPg && scenario()) headers['X-Fake-Pg-Scenario'] = scenario();
    bk.post('/api/holds/' + holdId + '/payments', { method: chosen }, { headers: headers, timeoutMs: PAY_TIMEOUT_MS })
      .then(paid, payFailed);
  });

  function paid(r) {
    if (!r || !r.status) {
      payFailed(new bk.ApiError(0, 'NETWORK', bk.NETWORK_MESSAGE, {}));
      return;
    }
    if (r.status === 'CONFIRMED') showDone(r.bookingNumber);
    else startPending(r.paymentId, null);
  }

  function payFailed(e) {
    switch (e.code) {
      case 'PAYMENT_DECLINED': showDeclined(); return;
      case 'HOLD_EXPIRED': showExpired(); return;
      case 'SEAT_LOST_REFUNDING': showSeatLost(); return;
      case 'PAYMENT_CANCELLED': showCancelled(); return;
      case 'HOLD_NOT_FOUND': showNotFound(); return;
      case 'ALREADY_PAYING': startPending(null, '다른 화면에서 결제하고 있어요'); return;
      case 'HOLD_NOT_PAYABLE': stage = 'pay'; reload(); return;
    }
    stage = 'pay';
    releaseButton.disabled = false;
    setBack(showHref);
    if (e.status === 0 || e.status >= 500) {
      // 같은 번호로 다시 보내므로 이미 처리된 결제면 같은 결과가 돌아온다
      showCoNote('결제 결과를 받지 못했어요.\n다시 눌러도 두 번 결제되지 않아요.');
      setPayButton('다시 시도', true);
    } else {
      showCoNote(e.message);
      setPayButton('결제하기', true);
    }
    if (timeUp || deadlineAt - mono() <= 0) expire();
    else tick();
  }

  // ---- 자리 놓기 ----

  releaseButton.addEventListener('click', function () {
    if (stage !== 'pay' || timeUp) return;
    showCoNote('');
    actions.hidden = true;
    ask.hidden = false;
    askNo.focus();
  });

  askNo.addEventListener('click', function () {
    closeAsk();
    releaseButton.focus();
  });

  askYes.addEventListener('click', function () {
    if (stage !== 'pay') return;
    askYes.disabled = true;
    askNo.disabled = true;
    askYes.textContent = '놓고 있어요';
    bk.del('/api/holds/' + holdId).then(function () {
      stopTimers();
      stage = 'result';
      forgetQueueToken();
      bk.session.remove(keyName);
      location.href = showHref;
    }, function (e) {
      closeAsk();
      if (e.code === 'HOLD_NOT_CANCELLABLE') {
        reload('결제를 하고 있는 중이라 놓을 수 없어요');
      } else if (e.code === 'HOLD_NOT_FOUND') {
        showNotFound();
      } else {
        showCoNote(e.message);
      }
    });
  });

  // ---- 결제 확인 중 ----

  function startPending(paymentId, note) {
    clearTimeout(tickTimer);
    clearTimeout(recheckTimer);
    stage = 'pending';
    pendingPaymentId = paymentId;
    pendingSince = mono();
    pendNote.textContent = note || '';
    pendNote.hidden = !note;
    pendLead.textContent = '이 화면을 닫아도 결과는 내 예매에 나와요.';
    fillFacts(pendFacts, seatFacts(true));
    showOnly(pend);
    setBack(null);
    window.scrollTo(0, 0);
    poll();
  }

  function schedulePoll() {
    clearTimeout(pollTimer);
    if (stage === 'pending' && !document.hidden) pollTimer = setTimeout(poll, POLL_MS);
  }

  function poll() {
    clearTimeout(pollTimer);
    if (stage !== 'pending' || document.hidden) return;
    if (mono() - pendingSince >= SLOW_AFTER_MS) {
      pendLead.textContent = '조금 더 걸리고 있어요. 결과는 내 예매에서 볼 수 있어요';
    }
    var seq = ++pollSeq;
    // 다른 화면이 시작한 결제는 번호를 모르므로 자리 상태에서 찾는다
    if (!pendingPaymentId) {
      bk.get('/api/holds/' + holdId).then(function (h) {
        if (seq !== pollSeq) return;
        hold = h;
        if (h.status !== 'PAYING' && h.status !== 'HELD') { route(h); return; }
        pendingPaymentId = h.paymentId || null;
        if (pendingPaymentId) poll(); else schedulePoll();
      }, function (e) {
        if (seq !== pollSeq) return;
        if (e.status === 404) showNotFound(); else schedulePoll();
      });
      return;
    }
    bk.get('/api/payments/' + pendingPaymentId).then(function (p) {
      if (seq !== pollSeq) return;
      settle(p);
    }, function () {
      if (seq !== pollSeq) return;
      schedulePoll();
    });
  }

  // 결론이 난 결제만 화면을 바꾼다. 나머지는 계속 묻는다
  function settle(p) {
    if (p.status === 'APPROVED') {
      if (p.holdStatus === 'CONFIRMED') { showDone(p.bookingNumber); return; }
      if (p.holdStatus === 'REFUNDING' || p.holdStatus === 'RELEASED') { showSeatLost(); return; }
    } else if (p.status === 'DECLINED') {
      showDeclined();
      return;
    } else if (p.status === 'CANCELLED') {
      showCancelled();
      return;
    }
    schedulePoll();
  }

  // ---- 자리 상태로 화면 고르기 ----

  // 풀린 자리는 결제 기록으로 까닭을 가린다
  function explainReleased(h) {
    if (!h.paymentId) { showReleased(); return; }
    bk.get('/api/payments/' + h.paymentId).then(function (p) {
      if (p.status === 'DECLINED') showDeclined();
      else if (p.status === 'CANCELLED') showCancelled();
      else if (p.status === 'APPROVED') showSeatLost();
      else showReleased();
    }, function () { showReleased(); });
  }

  function route(h, note) {
    hold = h;
    if (!headerDone) {
      // 대기 표는 자리를 잡을 때 이미 썼다. 남겨 두면 다음 예매가 쓴 표로 줄을 선다
      forgetQueueToken();
      loadHeader();
    }
    switch (h.status) {
      case 'HELD':
        showPay(note);
        return;
      case 'PAYING':
        startPending(h.paymentId || null, note);
        return;
      case 'CONFIRMED':
        stopTimers();
        if (!h.paymentId) { showDone(null); return; }
        bk.get('/api/payments/' + h.paymentId).then(function (p) { showDone(p.bookingNumber); },
          function () { showDone(null); });
        return;
      case 'EXPIRED':
        showExpired();
        return;
      case 'REFUNDING':
        showSeatLost();
        return;
      case 'RELEASED':
        stopTimers();
        explainReleased(h);
        return;
      default:
        showReleased();
    }
  }

  // 결제 요청이 나가 있는 동안 늦게 온 자리 상태는 버린다
  function reload(note) {
    clearTimeout(recheckTimer);
    bk.get('/api/holds/' + holdId).then(function (h) {
      if (stage !== 'paying') route(h, note);
    }, function (e) {
      if (stage === 'paying') return;
      if (e.status === 404) { showNotFound(); return; }
      if (stage === 'loading') { showLoadFailed(e); return; }
      if (stage !== 'pay') return;
      // 시간이 다 된 뒤 다시 보는 중이면 조금 뒤 또 본다
      if (timeUp) { recheckTimer = setTimeout(reload, RECHECK_MS); return; }
      showCoNote(note || e.message);
      setPayButton('결제하기', true);
      releaseButton.disabled = false;
    });
  }

  // ---- 머리글 ----

  function loadHeader() {
    headerDone = true;
    if (!showId) return;
    bk.get('/api/shows/' + showId).then(function (show) {
      var performance = (show.performances || []).filter(function (p) {
        return hold && p.id === hold.performanceId;
      })[0];
      var sub = performance ? bk.dateTime(performance.startsAt) + ' · ' + show.venueName : show.venueName;
      bk.setHeader(show.title, sub, null);
    }, function () { /* 머리글은 없어도 결제는 된다 */ });
  }

  // ---- 시작 ----

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      clearTimeout(pollTimer);
      pollSeq++;
      return;
    }
    if (stage === 'pending') poll();
    // 숨어 있는 동안 다른 화면에서 결제했을 수 있어 자리 상태를 다시 본다
    else if (stage === 'pay') reload();
  });

  function syncBar() {
    body.style.setProperty('--bar-h', payBar.offsetHeight + 'px');
  }
  if ('ResizeObserver' in window) new ResizeObserver(syncBar).observe(payBar);
  else window.addEventListener('resize', syncBar);

  setBack(showHref);
  reload();
})();
