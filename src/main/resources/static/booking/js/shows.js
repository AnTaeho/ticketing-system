(function () {
  'use strict';

  var list = document.getElementById('shows');
  var loading = document.getElementById('shows-loading');
  var error = document.getElementById('shows-error');
  var el = bk.el;

  function nextLine(performance) {
    if (!performance) {
      return el('p', { class: 'next' }, el('span', { class: 'note', text: '예정된 날짜가 없어요' }));
    }
    var tag;
    if (performance.state === 'OPEN') tag = el('span', { class: 'tag open', text: '예매 중' });
    else if (performance.state === 'BEFORE_OPEN') tag = el('span', { class: 'tag', text: bk.shortDateTime(performance.openAt) + ' 오픈' });
    else tag = el('span', { class: 'tag', text: '예매 끝' });
    return el('p', { class: 'next' }, el('span', { class: 'num', text: bk.dateTime(performance.startsAt) }), tag);
  }

  function render(shows) {
    loading.hidden = true;
    list.textContent = '';
    if (!shows || shows.length === 0) {
      list.appendChild(el('p', { class: 'panel empty', text: '지금 예매할 수 있는 공연이 없어요' }));
      return;
    }
    shows.forEach(function (show) {
      list.appendChild(el('a', { class: 'panel card', href: '/booking/shows/' + show.id },
        el('h2', { text: show.title }),
        el('p', { class: 'venue', text: show.venueName }),
        nextLine(show.nextPerformance)));
    });
  }

  bk.get('/api/shows').then(render, function (e) {
    loading.hidden = true;
    error.textContent = e.message;
    error.hidden = false;
  });
})();
