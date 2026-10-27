(function () {
  'use strict';

  var REFUND_POLL_MS = 5000;

  var list = document.getElementById('bookings');
  var loading = document.getElementById('bk-loading');
  var error = document.getElementById('bk-error');
  var none = document.getElementById('bk-none');
  var el = bk.el;

  // bookingId → 마지막으로 그린 예매
  var shown = {};
  var pollTimer = null;
  var polling = false;

  function seatName(s) {
    return s.section + '구역 ' + s.rowNo + '열 ' + s.seatNo + '번';
  }

  function stateTag(b) {
    if (b.status === 'CONFIRMED') return el('span', { class: 'tag open', text: '예매 확정' });
    if (b.refund === 'DONE') return el('span', { class: 'tag', text: '취소됨 · 환불 완료' });
    return el('span', { class: 'tag warn', text: '취소됨 · 환불 진행 중' });
  }

  function fact(k, v) {
    return el('div', null, el('dt', { text: k }), el('dd', { class: 'num', text: v }));
  }

  function message(b) {
    if (b.status === 'CONFIRMED') {
      return el('p', { class: 'bk-msg' + (b.notification ? '' : ' is-wait'),
        text: b.notification || '예매 확정 알림을 보내고 있어요' });
    }
    if (b.refund === 'DONE') {
      return el('p', { class: 'bk-msg is-done', text: '결제 금액 ' + bk.won(b.totalAmount) + '을 결제한 수단으로 돌려드렸어요' });
    }
    return el('p', { class: 'bk-msg is-refund', text: '결제 금액 ' + bk.won(b.totalAmount) + '을 돌려드리고 있어요. 끝나면 여기에 바로 나와요' });
  }

  function footer(b, card) {
    if (b.status !== 'CONFIRMED') return null;
    if (!b.cancellable) return el('p', { class: 'note bk-foot', text: '공연이 시작돼 취소할 수 없어요' });

    var open = el('button', { class: 'btn btn-ghost', type: 'button', text: '예매 취소' });
    var no = el('button', { class: 'btn btn-ghost', type: 'button', text: '아니요' });
    var yes = el('button', { class: 'btn btn-primary', type: 'button', text: '예, 취소할게요' });
    var ask = el('div', { class: 'ask', hidden: true },
      el('p', { class: 'ask-title', text: '예매를 취소할까요?' }),
      el('p', { class: 'ask-text', text: '결제 금액은 자동으로 돌려드려요.' }),
      el('div', { class: 'ask-actions' }, no, yes));

    open.addEventListener('click', function () {
      hideAlert(card);
      open.hidden = true;
      ask.hidden = false;
      card.classList.add('is-asking');
      yes.focus();
    });
    no.addEventListener('click', function () {
      ask.hidden = true;
      open.hidden = false;
      card.classList.remove('is-asking');
      open.focus();
    });
    yes.addEventListener('click', function () { cancel(b, card, ask, yes, no); });

    return el('div', { class: 'bk-foot' }, open, ask);
  }

  function card(b) {
    var node = el('article', { class: 'panel bk' + (b.status === 'CANCELLED' ? ' is-cancelled' : ''),
      'data-id': String(b.bookingId) });
    var seats = el('ul', { class: 'bk-seats' });
    (b.seats || []).forEach(function (s) {
      seats.appendChild(el('li', null,
        el('strong', { text: seatName(s) }),
        el('span', { class: 'num', text: s.grade + '석 ' + bk.won(s.price) })));
    });
    var facts = el('dl', { class: 'facts' },
      fact('예매번호', b.bookingNumber),
      fact('결제 금액', bk.won(b.totalAmount)),
      fact('예매한 때', bk.dateTime(b.createdAt)),
      b.cancelledAt ? fact('취소한 때', bk.dateTime(b.cancelledAt)) : null);

    node.appendChild(el('div', { class: 'bk-state' }, stateTag(b)));
    node.appendChild(el('h2', { class: 'bk-title', text: b.showTitle }));
    node.appendChild(el('p', { class: 'bk-when' },
      el('span', { class: 'num', text: bk.dateTime(b.startsAt) }), ' · ' + b.venueName));
    node.appendChild(seats);
    node.appendChild(facts);
    node.appendChild(message(b));
    node.appendChild(el('p', { class: 'alert bk-alert', role: 'alert', hidden: true }));
    var foot = footer(b, node);
    if (foot) node.appendChild(foot);
    return node;
  }

  function showAlert(cardNode, text) {
    var alert = cardNode.querySelector('.bk-alert');
    alert.textContent = text;
    alert.hidden = false;
  }

  function hideAlert(cardNode) {
    var alert = cardNode.querySelector('.bk-alert');
    if (alert) alert.hidden = true;
  }

  function replaceCard(b, alertText) {
    var old = list.querySelector('.bk[data-id="' + b.bookingId + '"]');
    var next = card(b);
    shown[b.bookingId] = JSON.stringify(b);
    if (old) list.replaceChild(next, old);
    else list.appendChild(next);
    if (alertText) showAlert(next, alertText);
    return next;
  }

  function cancel(b, cardNode, ask, yes, no) {
    yes.disabled = true;
    no.disabled = true;
    yes.textContent = '취소하고 있어요';
    bk.post('/api/bookings/' + b.bookingId + '/cancel').then(function () {
      return refreshOne(b.bookingId);
    }, function (e) {
      if (e.code === 'CANCEL_NOT_ALLOWED') {
        // 그사이 공연이 시작됐다. 카드를 새로 그려 취소 단추를 없앤다
        return refreshOne(b.bookingId, e.message);
      }
      if (e.status === 404) {
        ask.hidden = true;
        cardNode.classList.remove('is-asking');
        showAlert(cardNode, e.message);
        return null;
      }
      yes.disabled = false;
      no.disabled = false;
      yes.textContent = '예, 취소할게요';
      showAlert(cardNode, e.message);
      return null;
    });
  }

  function refreshOne(bookingId, alertText) {
    return bk.get('/api/bookings/' + bookingId).then(function (fresh) {
      var node = replaceCard(fresh, alertText);
      var focusTarget = node.querySelector('.bk-state');
      if (focusTarget) { focusTarget.setAttribute('tabindex', '-1'); focusTarget.focus({ preventScroll: true }); }
      schedule();
    }, function (e) {
      // 취소는 됐는데 다시 읽지 못했다. 목록을 다시 받아 맞춘다
      load(e.message);
    });
  }

  function render(bookings) {
    loading.hidden = true;
    error.hidden = true;
    if (!bookings || bookings.length === 0) {
      list.textContent = '';
      shown = {};
      none.hidden = false;
      return;
    }
    none.hidden = true;
    var ids = {};
    bookings.forEach(function (b, i) {
      ids[b.bookingId] = true;
      var text = JSON.stringify(b);
      var old = list.querySelector('.bk[data-id="' + b.bookingId + '"]');
      // 취소를 묻는 중인 카드는 건드리지 않는다
      if (old && (shown[b.bookingId] === text || old.classList.contains('is-asking'))) {
        if (list.children[i] !== old) list.insertBefore(old, list.children[i] || null);
        return;
      }
      var next = card(b);
      shown[b.bookingId] = text;
      if (old) list.replaceChild(next, old);
      else list.insertBefore(next, list.children[i] || null);
    });
    Array.prototype.slice.call(list.children).forEach(function (node) {
      if (!ids[node.getAttribute('data-id')]) { delete shown[node.getAttribute('data-id')]; list.removeChild(node); }
    });
  }

  // 환불이 끝나지 않았거나 확정 알림이 아직 없는 예매
  function needsRefresh() {
    return Object.keys(shown).some(function (id) {
      var b = JSON.parse(shown[id]);
      return b.refund === 'REQUESTED' || (b.status === 'CONFIRMED' && !b.notification);
    });
  }

  // 바뀔 예매가 있을 때만 다시 읽는다. 탭이 숨겨지면 멈춘다
  function schedule() {
    clearTimeout(pollTimer);
    pollTimer = null;
    if (document.hidden || !needsRefresh()) return;
    pollTimer = setTimeout(poll, REFUND_POLL_MS);
  }

  function poll() {
    pollTimer = null;
    if (polling) return;
    polling = true;
    bk.get('/api/bookings').then(function (bookings) {
      polling = false;
      render(bookings);
      schedule();
    }, function () {
      // 다음 차례에 다시 읽는다. 이미 그린 카드는 그대로 둔다
      polling = false;
      schedule();
    });
  }

  function load(alertText) {
    bk.get('/api/bookings').then(function (bookings) {
      render(bookings);
      if (alertText) { error.textContent = alertText; error.hidden = false; }
      schedule();
    }, function (e) {
      loading.hidden = true;
      error.textContent = e.message;
      error.hidden = false;
    });
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      clearTimeout(pollTimer);
      pollTimer = null;
    } else if (needsRefresh() && !pollTimer) {
      poll();
    }
  });

  load();
})();
