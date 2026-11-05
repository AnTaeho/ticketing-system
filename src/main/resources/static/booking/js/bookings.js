(function () {
  'use strict';

  var list = document.getElementById('bk-list');
  var loading = document.getElementById('bk-loading');
  var error = document.getElementById('bk-error');
  var none = document.getElementById('bk-none');
  var el = bk.el;

  // bookingId → 마지막으로 그린 예매
  var shown = {};

  function stateTag(b) {
    if (b.status === 'CONFIRMED') return el('span', { class: 'tag open', text: '예매 확정' });
    return el('span', { class: 'tag', text: '취소됨 · 환불 완료' });
  }

  function message(b) {
    if (b.status === 'CONFIRMED') return null;
    return el('p', { class: 'bk-msg is-done', text: '결제 금액 ' + bk.won(b.totalAmount) + '을 결제한 수단으로 돌려드렸어요' });
  }

  function footer(b, card) {
    if (b.status !== 'CONFIRMED') return null;
    if (!b.cancellable) return el('p', { class: 'note bk-foot', text: '공연이 시작돼 취소할 수 없어요' });

    var open = el('button', { class: 'btn btn-ghost', type: 'button', text: '예매 취소' });
    var ask = bk.confirmBox({
      title: '예매를 취소할까요?', text: '결제 금액은 자동으로 돌려드려요.',
      no: '아니요', yes: '예, 취소할게요', busy: '취소하고 있어요', focus: 'yes',
      onNo: function () {
        ask.close();
        open.hidden = false;
        card.classList.remove('is-asking');
        open.focus();
      },
      onYes: function () { cancel(b, card, ask); }
    });

    open.addEventListener('click', function () {
      hideAlert(card);
      open.hidden = true;
      card.classList.add('is-asking');
      ask.open();
    });

    return el('div', { class: 'bk-foot' }, open, ask.node);
  }

  function card(b) {
    var node = el('article', { class: 'panel bk' + (b.status === 'CANCELLED' ? ' is-cancelled' : ''),
      'data-id': String(b.bookingId) });
    var seats = el('ul', { class: 'bk-seats' });
    (b.seats || []).forEach(function (raw) {
      var s = bk.seat(raw);
      seats.appendChild(el('li', null,
        el('strong', { text: bk.seatName(s) }),
        el('span', { class: 'num', text: bk.seatPrice(s) })));
    });
    var facts = bk.fillFacts(el('dl', { class: 'facts' }), [
      { k: '예매번호', v: b.bookingNumber, num: true },
      { k: '결제 금액', v: bk.won(b.totalAmount), num: true },
      { k: '예매한 때', v: bk.dateTime(b.createdAt), num: true },
      b.cancelledAt ? { k: '취소한 때', v: bk.dateTime(b.cancelledAt), num: true } : null
    ]);

    node.appendChild(el('div', { class: 'bk-state' }, stateTag(b)));
    node.appendChild(el('h2', { class: 'bk-title', text: b.showTitle }));
    node.appendChild(el('p', { class: 'bk-when' },
      el('span', { class: 'num', text: bk.dateTime(b.startsAt) }), ' · ' + b.venueName));
    node.appendChild(seats);
    node.appendChild(facts);
    var msg = message(b);
    if (msg) node.appendChild(msg);
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

  function cardOf(bookingId) {
    return list.querySelector('.bk[data-id="' + bookingId + '"]');
  }

  function replaceCard(b, alertText) {
    var old = cardOf(b.bookingId);
    var next = card(b);
    shown[b.bookingId] = JSON.stringify(b);
    if (old) list.replaceChild(next, old);
    else list.appendChild(next);
    if (alertText) showAlert(next, alertText);
    return next;
  }

  function cancel(b, cardNode, ask) {
    ask.busy();
    bk.post('/api/bookings/' + b.bookingId + '/cancel').then(function () {
      return refreshOne(b.bookingId);
    }, function (e) {
      if (e.code === 'CANCEL_NOT_ALLOWED') {
        // 그사이 공연이 시작됐다. 카드를 새로 그려 취소 단추를 없앤다
        return refreshOne(b.bookingId, e.message);
      }
      if (e.status === 404) {
        ask.close();
        cardNode.classList.remove('is-asking');
      } else {
        ask.reset();
      }
      showAlert(cardNode, e.message);
      return null;
    });
  }

  function refreshOne(bookingId, alertText) {
    return bk.get('/api/bookings/' + bookingId).then(function (fresh) {
      var node = replaceCard(fresh, alertText);
      var focusTarget = node.querySelector('.bk-state');
      if (focusTarget) { focusTarget.setAttribute('tabindex', '-1'); focusTarget.focus({ preventScroll: true }); }
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
      var old = cardOf(b.bookingId);
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

  function load(alertText) {
    bk.get('/api/bookings').then(function (bookings) {
      render(bookings);
      if (alertText) { error.textContent = alertText; error.hidden = false; }
    }, function (e) {
      loading.hidden = true;
      error.textContent = e.message;
      error.hidden = false;
    });
  }

  load();
})();
