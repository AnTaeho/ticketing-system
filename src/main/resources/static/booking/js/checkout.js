(function () {
  'use strict';

  var POLL_MS = 2000;
  var RECHECK_MS = 2000;
  var SLOW_AFTER_MS = 2 * 60 * 1000;
  var PAY_TIMEOUT_MS = 15000;
  var URGENT_SECONDS = 60;
  var METHOD_NAMES = { CARD: '신용·체크카드', EASY_PAY: '간편결제' };
  var AGAIN = '다시 예매하려면 줄을 서서 자리를 골라 주세요.';

  var body = document.body;
  var holdId = body.dataset.holdId;
  var demoPg = body.dataset.demoPg === 'true';
  var showId = bk.positiveId(bk.query('show'));
  var showHref = bk.links.show(showId);
  var idemKey = bk.keys.idempotency(holdId);
  var methodKey = bk.keys.payMethod(holdId);
  var el = bk.el;

  var ui = {
    back: document.getElementById('page-back'),
    clock: document.getElementById('co-clock'),
    clockMain: document.getElementById('co-clock-main'),
    clockSub: document.getElementById('co-clock-sub'),
    loading: document.getElementById('co-loading'),
    order: document.getElementById('co-order'),
    seatLines: document.getElementById('co-seat-lines'),
    amountRows: document.getElementById('co-amount-rows'),
    total: document.getElementById('co-total'),
    payBar: document.getElementById('co-pay-bar'),
    note: document.getElementById('co-note'),
    actions: document.getElementById('co-actions'),
    pay: document.getElementById('co-pay'),
    release: document.getElementById('co-release'),
    pend: document.getElementById('co-pend'),
    pendNote: document.getElementById('co-pend-note'),
    pendLead: document.getElementById('co-pend-lead'),
    pendFacts: document.getElementById('co-pend-facts'),
    pendAfter: document.getElementById('co-pend-after'),
    result: document.getElementById('co-result'),
    resultTitle: document.getElementById('co-result-title'),
    resultText: document.getElementById('co-result-text'),
    resultTicket: document.getElementById('co-result-ticket'),
    resultNumber: document.getElementById('co-result-number'),
    resultFacts: document.getElementById('co-result-facts'),
    resultMain: document.getElementById('co-result-main'),
    resultSub: document.getElementById('co-result-sub')
  };

  var state = {
    stage: 'loading',        // loading → pay → paying → pending / result
    hold: null,
    headerDone: false,
    deadlineAt: 0,           // 결제 마감의 이 화면 시계 값
    timeUp: false,
    pendingPaymentId: null,
    pendingSince: 0,
    memoryKey: null          // 저장소를 못 쓸 때의 결제 요청 번호
  };
  var timers = { tick: null, recheck: null };

  var ask = bk.confirmBox({
    id: 'co-ask', title: '자리를 놓을까요?', text: '놓으면 다른 분이 바로 고를 수 있어요.',
    no: '아니요', yes: '예, 놓을게요', busy: '놓고 있어요', focus: 'no',
    onNo: function () { closeAsk(); ui.release.focus(); },
    onYes: releaseHold
  });
  ui.payBar.insertBefore(ask.node, ui.actions);

  var pendingPoller = bk.poller({ interval: POLL_MS, run: checkPending, onData: pendingChecked, onError: pendingFailed });

  function has(table, key) {
    return Object.prototype.hasOwnProperty.call(table, key);
  }

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
    var key = bk.session.get(idemKey) || state.memoryKey;
    if (!key) {
      key = uuid();
      bk.session.set(idemKey, key);
    }
    state.memoryKey = key;
    return key;
  }

  function seatFacts(withMethod) {
    var hold = state.hold;
    var seats = hold ? hold.seats || [] : [];
    var method = METHOD_NAMES[bk.session.get(methodKey)];
    return [
      seats.length ? { k: '자리', v: bk.seatText(seats), small: bk.gradeText(seats) } : null,
      hold ? { k: '결제 금액', v: bk.won(hold.totalAmount), num: true } : null,
      withMethod && method ? { k: '결제 수단', v: method } : null
    ];
  }

  // ---- 화면 전환 ----

  // 단추 자리는 남겨 두어 결제 중에 머리글 글자가 옆으로 밀리지 않게 한다
  function setBack(href) {
    if (!ui.back) return;
    ui.back.style.visibility = href ? '' : 'hidden';
    if (href) ui.back.setAttribute('href', href);
  }

  function stopTimers() {
    clearTimeout(timers.tick);
    clearTimeout(timers.recheck);
    pendingPoller.stop();
  }

  function showOnly(node) {
    [ui.loading, ui.order, ui.pend, ui.pendAfter, ui.result].forEach(function (n) { n.hidden = n !== node; });
    if (node === ui.pend) ui.pendAfter.hidden = false;
    ui.clock.hidden = node !== ui.order;
    body.classList.toggle('is-narrow', node !== ui.order);
  }

  function queueHref() {
    if (!state.hold) return bk.links.shows;
    return bk.links.queue(state.hold.performanceId, showId);
  }

  function forgetQueueToken() {
    if (state.hold) bk.session.remove(bk.keys.queueToken(state.hold.performanceId));
  }

  // 결과 화면의 단추. 줄 서기부터 다시 하는 단추는 남은 대기 표를 지우고 간다
  var LINKS = {
    repick: function () { return { label: '자리 다시 고르기', href: queueHref(), before: forgetQueueToken }; },
    shows: function () { return { label: '공연 목록', href: bk.links.shows }; },
    bookings: function () { return { label: '내 예매 보기', href: bk.links.bookings }; },
    reload: function () { return { label: '다시 불러오기', href: location.pathname + location.search }; }
  };

  // 결제가 끝난 화면들. facts는 고른 자리와 금액을 함께 보일지
  var RESULTS = {
    done: { title: '예매가 끝났어요', facts: true, main: 'bookings', sub: 'shows' },
    declined: { title: '결제가 거절됐어요. 자리는 풀렸어요', text: AGAIN, main: 'repick', sub: 'shows' },
    expired: { title: '결제 시간이 지나 자리가 풀렸어요',
      text: '놓친 자리는 10분 뒤에 다시 고를 수 있어요. 다른 자리는 지금 고를 수 있어요.', main: 'repick', sub: 'shows' },
    seatLost: { title: '결제는 됐지만 자리를 지키지 못했어요', text: '결제 금액은 자동으로 돌려드려요.', main: 'repick', sub: 'shows' },
    cancelled: { title: '결제가 취소돼 자리가 풀렸어요', text: AGAIN, main: 'repick', sub: 'shows' },
    released: { title: '이미 놓은 자리예요', text: AGAIN, main: 'repick', sub: 'shows' },
    alreadyFreed: { title: '이미 풀린 자리예요', text: AGAIN, main: 'repick', sub: 'shows' },
    alreadyBooked: { title: '이미 예매가 끝난 자리예요', text: '취소는 내 예매에서 할 수 있어요.', facts: true, main: 'bookings', sub: 'shows' },
    notFound: { title: '찾을 수 없는 자리예요', text: '공연 목록에서 다시 골라 주세요.', main: 'shows' },
    loadFailed: { title: '결제 정보를 불러오지 못했어요', main: 'reload', sub: 'shows' }
  };

  function setLink(node, action) {
    node.hidden = !action;
    if (!action) return;
    node.textContent = action.label;
    node.setAttribute('href', action.href);
    node.onclick = action.before || null;
  }

  // extra: {text, number}로 표의 글을 바꾼다
  function showResult(kind, extra) {
    var r = RESULTS[kind];
    var x = extra || {};
    stopTimers();
    state.stage = 'result';
    ui.resultTitle.textContent = r.title;
    ui.resultText.textContent = x.text || r.text || '';
    ui.resultTicket.hidden = !x.number;
    ui.resultNumber.textContent = x.number || '';
    bk.fillFacts(ui.resultFacts, r.facts ? seatFacts(false) : []);
    setLink(ui.resultMain, r.main ? LINKS[r.main]() : null);
    setLink(ui.resultSub, r.sub ? LINKS[r.sub]() : null);
    showOnly(ui.result);
    setBack(showHref);
    window.scrollTo(0, 0);
  }

  function showDone(number) {
    showResult('done', {
      number: number,
      text: number ? '내 예매에서 언제든 다시 볼 수 있어요.' : '예매번호는 내 예매에서 볼 수 있어요.'
    });
  }

  function showLoadFailed(e) {
    showResult('loadFailed', { text: bk.isNetworkError(e) ? bk.NETWORK_MESSAGE : '잠시 뒤 다시 불러와 주세요.' });
  }

  // ---- 결제 화면 ----

  function renderOrder() {
    var seats = (state.hold.seats || []).slice().sort(bk.bySeat);
    ui.seatLines.textContent = '';
    seats.forEach(function (s) {
      ui.seatLines.appendChild(el('div', { class: 'seat-line' },
        el('strong', { text: bk.seatName(s) }),
        el('span', { class: 'num', text: bk.seatPrice(s) })));
    });
    ui.amountRows.textContent = '';
    bk.gradeGroups(seats).forEach(function (g) {
      ui.amountRows.appendChild(el('div', { class: 'row' },
        el('span', { class: 'k', text: g.grade + '석 ' + g.count + '매' }),
        el('span', { class: 'num', text: bk.won(g.sum) })));
    });
    ui.total.textContent = bk.won(state.hold.totalAmount);
  }

  // off는 다시 누를 수 없는 끝난 상태다. 요청 중인 꺼짐과 모양을 나눈다
  function setPayButton(label, enabled, off) {
    ui.pay.textContent = label;
    ui.pay.disabled = !enabled;
    ui.pay.classList.toggle('is-off', !!off);
  }

  function showCoNote(message) {
    ui.note.textContent = message || '';
    ui.note.hidden = !message;
  }

  function closeAsk() {
    ask.close();
    ui.actions.hidden = false;
  }

  function paintClock(text) {
    ui.clockMain.textContent = '';
    if (text) {
      ui.clockMain.textContent = text;
      ui.clockSub.textContent = '';
      ui.clockSub.hidden = true;
      return;
    }
    ui.clockMain.appendChild(el('b', { class: 'num', id: 'co-clock-left' }));
    ui.clockMain.appendChild(document.createTextNode(' 안에 결제해 주세요'));
    ui.clockSub.textContent = '지나면 자리가 풀려요';
    ui.clockSub.hidden = false;
  }

  // 남은 시간은 서버가 준 두 시각의 차이로만 정하고 이 화면의 시계로 줄여 간다
  function startClock() {
    clearTimeout(timers.tick);
    state.deadlineAt = mono() + (bk.epochOf(state.hold.expiresAt) - bk.epochOf(state.hold.serverNow));
    state.timeUp = false;
    paintClock(null);
    tick();
  }

  function tick() {
    clearTimeout(timers.tick);
    var left = state.deadlineAt - mono();
    var seconds = Math.ceil(left / 1000);
    if (seconds <= 0) {
      // 결제 요청이 나가 있는 동안에는 서버가 결제 마감으로 바꿨을 수 있어 기다린다
      if (state.stage === 'pay') expire();
      return;
    }
    var shown = document.getElementById('co-clock-left');
    if (shown) shown.textContent = bk.clockText(seconds * 1000);
    ui.clock.classList.toggle('urgent', seconds < URGENT_SECONDS);
    timers.tick = setTimeout(tick, (left % 1000) + 5);
  }

  // 실제로 푸는 일은 서버가 한다. 화면은 단추를 끄고 서버 상태를 다시 본다
  function expire() {
    state.timeUp = true;
    ui.clock.classList.add('urgent');
    paintClock('결제 시간이 지나 자리가 풀렸어요');
    closeAsk();
    showCoNote('');
    setPayButton('결제 시간이 지났어요', false, true);
    ui.release.disabled = true;
    clearTimeout(timers.recheck);
    timers.recheck = setTimeout(reload, RECHECK_MS);
  }

  // 결제를 다시 누를 수 있게 단추와 고른 것을 연다
  function unlockPay() {
    ui.release.disabled = false;
    lockChoices(false);
  }

  function showPay(note) {
    pendingPoller.stop();
    clearTimeout(timers.recheck);
    state.stage = 'pay';
    renderOrder();
    closeAsk();
    showCoNote(note || '');
    setPayButton('결제하기', true);
    unlockPay();
    showOnly(ui.order);
    setBack(showHref);
    idempotencyKey();
    startClock();
  }

  function checkedValue(name) {
    var picked = document.querySelector('input[name="' + name + '"]:checked');
    return picked ? picked.value : null;
  }

  // 결제 요청이 나가 있는 동안 고른 것을 바꿔도 그 요청에는 반영되지 않으므로 함께 끈다
  function lockChoices(locked) {
    document.querySelectorAll('input[name="method"], input[name="scenario"]').forEach(function (input) {
      input.disabled = locked;
    });
    ui.order.classList.toggle('is-locked', locked);
  }

  function startPay() {
    if (state.stage !== 'pay' || state.timeUp) return;
    state.stage = 'paying';
    closeAsk();
    showCoNote('');
    setPayButton('결제하고 있어요', false);
    ui.release.disabled = true;
    lockChoices(true);
    setBack(null);
    var chosen = checkedValue('method') || 'CARD';
    bk.session.set(methodKey, chosen);
    var headers = { 'Idempotency-Key': idempotencyKey() };
    var scenario = checkedValue('scenario');
    if (demoPg && scenario) headers['X-Fake-Pg-Scenario'] = scenario;
    bk.post('/api/holds/' + holdId + '/payments', { method: chosen }, { headers: headers, timeoutMs: PAY_TIMEOUT_MS })
      .then(paid, payFailed);
  }

  ui.pay.addEventListener('click', startPay);

  function paid(r) {
    if (!r || !r.status) {
      payFailed(new bk.ApiError(0, 'NETWORK', bk.NETWORK_MESSAGE, {}));
      return;
    }
    if (r.status === 'CONFIRMED') showDone(r.bookingNumber);
    else startPending(r.paymentId, null);
  }

  // 결과가 정해진 결제 실패는 결과 화면으로 간다
  var PAY_FAILURES = {
    PAYMENT_DECLINED: 'declined',
    HOLD_EXPIRED: 'expired',
    SEAT_LOST_REFUNDING: 'seatLost',
    PAYMENT_CANCELLED: 'cancelled',
    HOLD_NOT_FOUND: 'notFound'
  };

  function payFailed(e) {
    if (has(PAY_FAILURES, e.code)) {
      showResult(PAY_FAILURES[e.code]);
    } else if (e.code === 'ALREADY_PAYING') {
      startPending(null, '다른 화면에서 결제하고 있어요');
    } else if (e.code === 'HOLD_NOT_PAYABLE') {
      state.stage = 'pay';
      reload();
    } else {
      payAgain(e);
    }
  }

  // 결과를 모르거나 요청이 막힌 결제는 같은 화면에서 다시 누르게 한다
  function payAgain(e) {
    state.stage = 'pay';
    unlockPay();
    setBack(showHref);
    if (e.status === 0 || e.status >= 500) {
      // 같은 번호로 다시 보내므로 이미 처리된 결제면 같은 결과가 돌아온다
      showCoNote('결제 결과를 받지 못했어요.\n다시 눌러도 두 번 결제되지 않아요.');
      setPayButton('다시 시도', true);
    } else {
      showCoNote(e.message);
      setPayButton('결제하기', true);
    }
    if (state.timeUp || state.deadlineAt - mono() <= 0) expire();
    else tick();
  }

  // ---- 자리 놓기 ----

  // 놓지 못한 까닭은 서버가 알려 준 그 순간의 자리 상태로 고른다
  var NOT_CANCELLABLE = { RELEASED: 'alreadyFreed', EXPIRED: 'alreadyFreed', CONFIRMED: 'alreadyBooked' };

  function notCancellable(e) {
    var status = e.detail.status;
    if (has(NOT_CANCELLABLE, status)) showResult(NOT_CANCELLABLE[status]);
    else if (status === 'PAYING') reload('결제를 하고 있는 중이라 놓을 수 없어요');
    else reload(e.message);
  }

  ui.release.addEventListener('click', function () {
    if (state.stage !== 'pay' || state.timeUp) return;
    showCoNote('');
    ui.actions.hidden = true;
    ask.open();
  });

  function releaseHold() {
    if (state.stage !== 'pay') return;
    ask.busy();
    bk.del('/api/holds/' + holdId).then(function () {
      stopTimers();
      state.stage = 'result';
      forgetQueueToken();
      bk.session.remove(idemKey);
      location.href = showHref;
    }, function (e) {
      closeAsk();
      if (e.code === 'HOLD_NOT_CANCELLABLE') notCancellable(e);
      else if (e.code === 'HOLD_NOT_FOUND') showResult('notFound');
      else showCoNote(e.message);
    });
  }

  // ---- 결제 확인 중 ----

  function startPending(paymentId, note) {
    clearTimeout(timers.tick);
    clearTimeout(timers.recheck);
    state.stage = 'pending';
    state.pendingPaymentId = paymentId;
    state.pendingSince = mono();
    ui.pendNote.textContent = note || '';
    ui.pendNote.hidden = !note;
    ui.pendLead.textContent = '이 화면을 닫아도 결과는 내 예매에 나와요.';
    bk.fillFacts(ui.pendFacts, seatFacts(true));
    showOnly(ui.pend);
    setBack(null);
    window.scrollTo(0, 0);
    pendingPoller.now();
  }

  function checkPending() {
    if (mono() - state.pendingSince >= SLOW_AFTER_MS) {
      ui.pendLead.textContent = '조금 더 걸리고 있어요. 결과는 내 예매에서 볼 수 있어요';
    }
    // 다른 화면이 시작한 결제는 번호를 모르므로 자리 상태에서 찾는다
    if (!state.pendingPaymentId) {
      return bk.get('/api/holds/' + holdId).then(function (h) { return { hold: h }; });
    }
    return bk.get('/api/payments/' + state.pendingPaymentId).then(function (p) { return { payment: p }; });
  }

  // 결론이 난 것만 화면을 바꾼다. 나머지는 poller가 계속 묻는다
  function pendingChecked(r) {
    if (r.payment) {
      settle(r.payment);
      return;
    }
    state.hold = r.hold;
    if (r.hold.status !== 'PAYING' && r.hold.status !== 'HELD') {
      route(r.hold);
      return;
    }
    state.pendingPaymentId = r.hold.paymentId || null;
    if (state.pendingPaymentId) pendingPoller.now();
  }

  function pendingFailed(e) {
    if (!state.pendingPaymentId && e.status === 404) showResult('notFound');
  }

  function settle(p) {
    if (p.status === 'APPROVED') {
      if (p.holdStatus === 'CONFIRMED') showDone(p.bookingNumber);
      else if (p.holdStatus === 'REFUNDING' || p.holdStatus === 'RELEASED') showResult('seatLost');
    } else if (p.status === 'DECLINED') {
      showResult('declined');
    } else if (p.status === 'CANCELLED') {
      showResult('cancelled');
    }
  }

  // ---- 자리 상태로 화면 고르기 ----

  var RELEASE_REASONS = { DECLINED: 'declined', CANCELLED: 'cancelled', APPROVED: 'seatLost' };
  var HOLD_RESULTS = { EXPIRED: 'expired', REFUNDING: 'seatLost' };

  // 풀린 자리는 결제 기록으로 까닭을 가린다
  function explainReleased(h) {
    if (!h.paymentId) { showResult('released'); return; }
    bk.get('/api/payments/' + h.paymentId).then(function (p) {
      showResult(has(RELEASE_REASONS, p.status) ? RELEASE_REASONS[p.status] : 'released');
    }, function () { showResult('released'); });
  }

  function showConfirmed(h) {
    if (!h.paymentId) { showDone(null); return; }
    bk.get('/api/payments/' + h.paymentId).then(function (p) { showDone(p.bookingNumber); },
      function () { showDone(null); });
  }

  function route(h, note) {
    state.hold = h;
    if (!state.headerDone) {
      // 대기 표는 자리를 잡을 때 이미 썼다. 남겨 두면 다음 예매가 쓴 표로 줄을 선다
      forgetQueueToken();
      state.headerDone = true;
      bk.loadPerformanceHeader(showId, h.performanceId);
    }
    if (h.status === 'HELD') {
      showPay(note);
    } else if (h.status === 'PAYING') {
      startPending(h.paymentId || null, note);
    } else if (h.status === 'CONFIRMED') {
      stopTimers();
      showConfirmed(h);
    } else if (h.status === 'RELEASED') {
      stopTimers();
      explainReleased(h);
    } else {
      showResult(has(HOLD_RESULTS, h.status) ? HOLD_RESULTS[h.status] : 'released');
    }
  }

  // 결제 요청이 나가 있는 동안 늦게 온 자리 상태는 버린다
  function reload(note) {
    clearTimeout(timers.recheck);
    bk.get('/api/holds/' + holdId).then(function (h) {
      if (state.stage !== 'paying') route(h, note);
    }, function (e) {
      if (state.stage === 'paying') return;
      if (e.status === 404) { showResult('notFound'); return; }
      if (state.stage === 'loading') { showLoadFailed(e); return; }
      if (state.stage !== 'pay') return;
      // 시간이 다 된 뒤 다시 보는 중이면 조금 뒤 또 본다
      if (state.timeUp) { timers.recheck = setTimeout(reload, RECHECK_MS); return; }
      showCoNote(note || e.message);
      setPayButton('결제하기', true);
      unlockPay();
    });
  }

  // 숨어 있는 동안 다른 화면에서 결제했을 수 있어 자리 상태를 다시 본다. 확인 중 폴링은 poller가 다시 잇는다
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && state.stage === 'pay') reload();
  });

  bk.syncBar(ui.payBar);
  setBack(showHref);
  reload();
})();
