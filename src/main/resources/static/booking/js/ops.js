(function () {
  'use strict';

  var POLL_MS = 2000;
  // 0이 아니면 주황으로 보이는 상태
  var BAD = { UNKNOWN: true, REFUNDING: true, FAILED: true };
  // 스케줄러가 1초마다 올리는 수라 강조하면 다른 변화가 묻힌다
  var STEADY = { 'ctr.booking.seatmap.rebuild': true };
  var TYPE_NAMES = { TICKET_ISSUED: '발권 알림', REFUND_REQUESTED: '환불' };

  var body = document.body;
  var performanceId = body.dataset.performanceId;
  var grid = document.getElementById('ops-grid');
  var attn = document.getElementById('ops-attn');
  var at = document.getElementById('ops-at');
  var errorBox = document.getElementById('ops-error');
  var redisDown = document.getElementById('ops-redis-down');
  var loading = document.getElementById('ops-loading');
  var el = bk.el;

  var reduceMotion = false;
  try { reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { /* 없으면 움직인다 */ }

  // 지난번 숫자. 키는 'seats.HELD'처럼 칸마다 하나
  var previous = null;
  var current = {};
  var timer = null;
  var busy = false;
  var stopped = false;
  var headerDone = false;

  function num(key, value, extraClass) {
    current[key] = value;
    var cls = 'n num';
    if (value == null) cls += ' na';
    else if (value === 0) cls += ' zero';
    if (extraClass) cls += ' ' + extraClass;
    var node = el('span', { class: cls, 'data-k': key, text: value == null ? '읽지 못함' : bk.count(value) });
    if (previous && value != null && previous[key] !== undefined && previous[key] !== value && !reduceMotion
        && !STEADY[key]) {
      node.classList.add('bump');
    }
    return node;
  }

  function card(title, code, explain, content, opts) {
    var o = opts || {};
    return el('section', { class: 'panel ops-card' + (o.wide ? ' ops-wide' : '') },
      el('h2', null, title, code ? el('code', { text: code }) : null,
        o.sum != null ? el('span', { class: 'sum num', text: o.sum }) : null),
      el('p', { class: 'explain', text: explain }),
      content);
  }

  function statusTable(group, counts, notes) {
    var table = el('table', { class: 'kv' });
    var tbody = el('tbody');
    Object.keys(counts).forEach(function (state) {
      var value = counts[state];
      tbody.appendChild(el('tr', null,
        el('th', { scope: 'row' }, state, notes && notes[state] ? el('span', { class: 'desc', text: notes[state] }) : null),
        el('td', null, num(group + '.' + state, value, BAD[state] && value > 0 ? 'bad' : null))));
    });
    table.appendChild(tbody);
    return table;
  }

  function total(counts) {
    return Object.keys(counts).reduce(function (sum, k) { return sum + (counts[k] || 0); }, 0);
  }

  function seatBar(seats) {
    var sum = total(seats);
    var bar = el('div', { class: 'stack', role: 'img',
      'aria-label': '빈자리 ' + seats.AVAILABLE + ', 결제 대기 ' + seats.HELD + ', 확정 ' + seats.SOLD });
    if (sum > 0) {
      ['SOLD', 'HELD', 'AVAILABLE'].forEach(function (s) {
        if (seats[s] > 0) bar.appendChild(el('i', { class: 's-' + s, style: 'width:' + (seats[s] * 100 / sum) + '%' }));
      });
    }
    return bar;
  }

  // 상태를 행으로 둔다. 작업 이름이 길어 좁은 화면에서도 열 머리에 들어가게
  function outboxTable(outbox) {
    var types = Object.keys(outbox);
    var states = types.length ? Object.keys(outbox[types[0]]) : [];
    var head = el('tr', null, el('th', { scope: 'col', text: '상태' }));
    types.forEach(function (type) {
      head.appendChild(el('th', { scope: 'col' }, TYPE_NAMES[type] || type, el('span', { class: 'desc', text: type })));
    });
    var tbody = el('tbody');
    states.forEach(function (s) {
      var row = el('tr', null, el('th', { scope: 'row', text: s }));
      types.forEach(function (type) {
        var value = outbox[type][s] || 0;
        row.appendChild(el('td', null, num('outbox.' + type + '.' + s, value, BAD[s] && value > 0 ? 'bad' : null)));
      });
      tbody.appendChild(row);
    });
    return el('table', { class: 'matrix' }, el('thead', null, head), tbody);
  }

  function redisTable(data) {
    var q = data.queue || {};
    var g = data.gateKeys || {};
    var tbody = el('tbody',
      null,
      el('tr', null, el('th', { scope: 'row' }, 'waiting', el('span', { class: 'desc', text: '줄 서서 기다리는 사람' })),
        el('td', null, num('redis.waiting', q.waiting))),
      el('tr', null, el('th', { scope: 'row' }, 'processing', el('span', { class: 'desc', text: '좌석 화면에 들어온 사람' })),
        el('td', null, num('redis.processing', q.processing))),
      el('tr', null, el('th', { scope: 'row' }, 'seat:*', el('span', { class: 'desc', text: '잡힌 좌석마다 하나' })),
        el('td', null, num('redis.seat', g.seat))),
      el('tr', null, el('th', { scope: 'row' }, 'userhold:*', el('span', { class: 'desc', text: '자리를 잡은 사용자마다 하나' })),
        el('td', null, num('redis.userhold', g.userHold))));
    return el('table', { class: 'kv' }, tbody);
  }

  function counterList(counters) {
    var list = el('ul', { class: 'ctr' });
    Object.keys(counters).forEach(function (name) {
      var c = counters[name];
      var parts = el('div', { class: 'parts' });
      var keys = Object.keys(c.values || {});
      if (keys.length === 0) parts.appendChild(el('span', { class: 'zero-note', text: '아직 센 것이 없어요' }));
      if (c.tags && c.tags.length) {
        keys.forEach(function (k) {
          parts.appendChild(el('span', { class: 'part' }, k, el('b', { class: 'num', text: bk.count(c.values[k]) })));
        });
      }
      var tagText = c.tags && c.tags.length ? '{' + c.tags.join(',') + '}' : '';
      list.appendChild(el('li', null,
        el('span', { class: 'name', text: name + tagText }),
        el('span', { class: 'tot' }, num('ctr.' + name, c.total)),
        c.tags && c.tags.length ? parts : (keys.length ? null : parts)));
    });
    return list;
  }

  function elapsed(fromText, nowText) {
    var ms = bk.epochOf(nowText) - bk.epochOf(fromText);
    return isNaN(ms) ? '' : bk.clockText(ms);
  }

  function attnGroup(title, code, explain, items) {
    var list = el('ul', { class: 'attn-list' });
    items.forEach(function (item) { list.appendChild(item); });
    return el('div', { class: 'attn-group' },
      el('h3', null, title, el('code', { text: code })),
      el('p', { class: 'explain', text: explain }),
      list);
  }

  function renderAttention(data) {
    var a = data.attention || {};
    var failed = a.failedTasks || [];
    var refunding = a.refundingHolds || [];
    var stale = a.stalePayments || [];
    var count = failed.length + refunding.length + stale.length;
    attn.textContent = '';
    attn.hidden = count === 0;
    if (count === 0) return;
    attn.appendChild(el('h2', { text: '사람이 살펴볼 일이 ' + count + '건 있어요' }));
    attn.appendChild(el('p', { text: '저절로 풀리지 않을 수 있는 상태예요. 목록은 종류마다 최근 20건까지 보여요.' }));
    if (failed.length) {
      attn.appendChild(attnGroup('멈춘 Outbox 작업', 'outbox_task FAILED',
        '재시도를 다 써서 워커가 더는 집지 않는 작업. 원인을 고친 뒤 손으로 다시 돌려야 한다.',
        failed.map(function (t) {
          return el('li', null,
            el('span', { class: 'head', text: '#' + t.id + ' ' + t.type + ' · 대상 ' + t.aggregateId + ' · ' + t.attempt + '번 시도' }),
            el('span', { class: 'err', text: t.lastError || '오류 기록 없음' }));
        })));
    }
    if (refunding.length) {
      attn.appendChild(attnGroup('환불을 기다리는 선점', 'seat_hold REFUNDING',
        '결제는 됐지만 자리를 지키지 못해 결제 금액을 돌려주는 중. 환불 작업이 끝나면 사라진다.',
        refunding.map(function (h) {
          return el('li', null,
            el('span', { class: 'head', text: 'hold #' + h.holdId + ' · 사용자 ' + h.userId + ' · ' + h.seatCount + '석 · ' + bk.won(h.totalAmount) }),
            el('span', { class: 'err', text: '바뀐 시각 ' + bk.dateTime(h.updatedAt) }));
        })));
    }
    if (stale.length) {
      attn.appendChild(attnGroup('30초 넘게 결과를 모르는 결제', 'booking_payment UNKNOWN·REQUESTED',
        'PG 응답이 늦어 결과를 아직 모른다. 대사 스케줄러가 PG에 다시 물어 승인·거절로 확정한다.',
        stale.map(function (p) {
          return el('li', null,
            el('span', { class: 'head', text: '결제 #' + p.paymentId + ' ' + p.status + ' · hold #' + p.holdId + ' · ' + elapsed(p.createdAt, data.generatedAt) + ' 지남' }),
            el('span', { class: 'err', text: p.lastError || '오류 기록 없음' }));
        })));
    }
  }

  function render(data) {
    current = {};
    loading.hidden = true;
    errorBox.hidden = true;
    body.classList.remove('is-stale');
    redisDown.hidden = data.redisAvailable !== false;

    if (!headerDone && data.performance) {
      var p = data.performance;
      bk.setHeader('운영 현황 · 회차 ' + p.performanceId,
        '「' + p.showTitle + '」 ' + bk.dateTime(p.startsAt) + ' · ' + p.venueName);
      document.title = '운영 현황 · ' + p.showTitle;
      headerDone = true;
    }
    at.textContent = '마지막으로 읽은 때 ' + (data.generatedAt || '').replace('T', ' ').slice(0, 19);

    renderAttention(data);

    var seats = data.seats || {};
    var cards = [
      card('좌석', 'seat',
        'AVAILABLE은 빈자리, HELD는 자리를 잡아 두고 결제를 기다리는 중, SOLD는 예매가 확정된 자리.',
        el('div', null, seatBar(seats), statusTable('seats', seats)), { sum: '전체 ' + bk.count(total(seats)) + '석' }),
      card('자리 잡기 기록', 'seat_hold',
        'HELD는 결제 전, PAYING은 결제 중, CONFIRMED는 확정. EXPIRED는 시간이 지나, RELEASED는 사용자가 놓아서 풀린 기록.',
        statusTable('holds', data.holds || {}, { REFUNDING: '결제 뒤 자리를 놓쳐 돌려주는 중' })),
      card('결제', 'booking_payment',
        'UNKNOWN은 PG 응답이 늦어 결과를 모르는 결제. 대사 스케줄러가 PG에 다시 물어 APPROVED나 DECLINED로 확정한다.',
        statusTable('payments', data.payments || {}, { REQUESTED: 'PG에 보낸 직후', CANCELLED: '환불까지 끝남' })),
      card('예매', 'booking',
        'CONFIRMED는 확정된 예매, CANCELLED는 사용자가 취소한 예매. 취소하면 좌석은 바로 빈자리로 돌아간다.',
        statusTable('bookings', data.bookings || {})),
      card('Outbox 작업', 'outbox_task',
        'Outbox는 확정 뒤 발권 알림·환불을 맡기는 작업 목록. 워커가 PENDING을 집어 처리하고, 정해진 횟수를 넘겨 실패하면 FAILED로 남긴다.',
        outboxTable(data.outbox || {}), { wide: true }),
      card('대기열과 게이트', 'Redis',
        '대기열은 줄 선 사람과 들어온 사람 수. 게이트 키는 이미 잡힌 자리를 DB까지 가기 전에 거절하는 Redis 사본.',
        redisTable(data)),
      card('카운터', 'Micrometer',
        '이 서버가 켜진 뒤 센 수. 모든 회차를 합친 값이라 위 표와 숫자가 다를 수 있다.',
        counterList(data.counters || {}), { wide: true })
    ];
    grid.textContent = '';
    cards.forEach(function (c) { grid.appendChild(c); });
    previous = current;
  }

  function showError(text, stale) {
    loading.hidden = true;
    errorBox.textContent = text;
    errorBox.hidden = false;
    if (stale) body.classList.add('is-stale');
  }

  function schedule() {
    clearTimeout(timer);
    timer = null;
    if (stopped || document.hidden) return;
    timer = setTimeout(poll, POLL_MS);
  }

  function poll() {
    timer = null;
    if (busy || stopped) return;
    busy = true;
    bk.get('/api/ops/performances/' + performanceId + '/summary').then(function (data) {
      busy = false;
      render(data);
      schedule();
    }, function (e) {
      busy = false;
      if (e.status === 404) {
        stopped = true;
        showError('이 회차를 찾지 못했어요. 주소의 회차 번호를 확인해 주세요', false);
        return;
      }
      showError('집계를 읽지 못했어요. 2초 뒤 다시 읽어요 (' + e.message + ')', true);
      schedule();
    });
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      clearTimeout(timer);
      timer = null;
    } else if (!timer && !stopped) {
      poll();
    }
  });

  poll();
})();
