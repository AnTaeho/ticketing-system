(function () {
  'use strict';

  var showId = document.body.dataset.showId;
  var loading = document.getElementById('show-loading');
  var error = document.getElementById('show-error');
  var body = document.getElementById('show-body');
  var about = document.getElementById('show-about');
  var list = document.getElementById('show-perfs');
  var el = bk.el;
  var countdowns = [];
  var lastReloadAt = 0;
  var DAY_MS = 24 * 60 * 60 * 1000;

  function action(performance) {
    if (performance.state === 'OPEN') {
      return el('a', {
        class: 'btn btn-primary',
        href: bk.links.queue(performance.id, showId),
        text: '예매하기'
      });
    }
    if (performance.state === 'BEFORE_OPEN') {
      var box = el('p', { class: 'btn is-off num' });
      countdowns.push({ node: box, openAt: bk.epochOf(performance.openAt), openText: performance.openAt });
      return box;
    }
    return el('p', { class: 'btn is-off', text: '예매가 끝났어요' });
  }

  function stateText(performance) {
    if (performance.state === 'OPEN') return '지금 예매할 수 있어요';
    if (performance.state === 'BEFORE_OPEN') return bk.dateTime(performance.openAt) + '에 예매가 열려요';
    return '이 날짜는 예매가 끝났어요';
  }

  function render(show) {
    loading.hidden = true;
    error.hidden = true;
    bk.setHeader(show.title, show.venueName);
    bk.setTitle(show.title);
    about.textContent = show.description || '';
    about.hidden = !show.description;
    countdowns = [];
    list.textContent = '';
    if (show.performances.length === 0) {
      list.appendChild(el('p', { class: 'panel empty', text: '예정된 날짜가 없어요' }));
    }
    show.performances.forEach(function (performance) {
      var closed = performance.state === 'CLOSED';
      list.appendChild(el('section', { class: 'panel perf' + (closed ? ' closed' : '') },
        el('div', null,
          el('p', { class: 'perf-when num', text: bk.dateTime(performance.startsAt) }),
          el('p', { class: 'perf-state', text: stateText(performance) })),
        action(performance)));
    });
    body.hidden = false;
    tick();
  }

  function tick() {
    var now = bk.serverNow();
    var due = false;
    countdowns.forEach(function (c) {
      var left = c.openAt - now;
      if (left <= 0) {
        due = true;
        c.node.textContent = '곧 열려요';
      } else if (left >= DAY_MS) {
        c.node.textContent = bk.shortDateTime(c.openText) + '에 열려요';
      } else {
        c.node.textContent = '예매 오픈까지 ' + bk.clockText(left);
      }
    });
    // 열릴 시각이 지나면 상태를 다시 받는다. 시계가 어긋나도 3초에 한 번만 묻는다
    if (due && now - lastReloadAt > 3000) {
      lastReloadAt = now;
      load();
    }
  }

  function load() {
    bk.get('/api/shows/' + showId).then(render, function (e) {
      loading.hidden = true;
      if (e.status === 404) {
        document.getElementById('show-missing').hidden = false;
        return;
      }
      error.textContent = e.message;
      error.hidden = false;
    });
  }

  setInterval(function () { if (countdowns.length) tick(); }, 1000);
  load();
})();
