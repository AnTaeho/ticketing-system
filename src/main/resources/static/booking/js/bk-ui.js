// 예매 화면 공통 부품: 요소 만들기, 머리·문서 제목, 하단 띠 높이, 상태 상자, 확인 단계, 사실 목록
(function () {
  'use strict';

  var SITE_NAME = '티켓 예매';

  // 글은 textContent로만 넣는다
  function el(tag, attrs) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value == null || value === false) return;
        if (key === 'text') node.textContent = value;
        else if (key === 'class') node.className = value;
        else node.setAttribute(key, value === true ? '' : value);
      });
    }
    for (var i = 2; i < arguments.length; i++) {
      var child = arguments[i];
      if (child == null) continue;
      node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return node;
  }

  function setHeader(title, sub, backHref) {
    var h = document.getElementById('page-title');
    var p = document.getElementById('page-sub');
    var back = document.getElementById('page-back');
    if (h && title) h.textContent = title;
    if (p) { p.textContent = sub || ''; p.hidden = !sub; }
    if (back && backHref) back.setAttribute('href', backHref);
  }

  // 「제목 · 사이트 이름」. 사이트 이름 대신 덧붙일 말을 줄 수 있다
  function setTitle(title, tail) {
    document.title = title + ' · ' + (tail || SITE_NAME);
  }

  // 회차 화면 머리: 뒤로 가기를 공연 정보로 걸고 공연 이름과 회차 시각·장소를 채운다.
  // 못 읽으면 null로 끝나고 화면은 그대로 쓴다
  function loadPerformanceHeader(showId, performanceId) {
    var back = bk.links.show(showId);
    setHeader(null, null, back);
    if (!showId) return Promise.resolve(null);
    return bk.get('/api/shows/' + showId).then(function (show) {
      var performance = (show.performances || []).filter(function (p) {
        return String(p.id) === String(performanceId);
      })[0] || null;
      setHeader(show.title, performance ? bk.dateTime(performance.startsAt) + ' · ' + show.venueName : show.venueName, back);
      return { show: show, performance: performance };
    }, function () { return null; });
  }

  // 띠 높이만큼 본문 아래를 비워 마지막 줄이 띠에 가리지 않게 한다
  function syncBar(bar) {
    function sync() { document.body.style.setProperty('--bar-h', bar.offsetHeight + 'px'); }
    sync();
    if ('ResizeObserver' in window) new ResizeObserver(sync).observe(bar);
    else window.addEventListener('resize', sync);
  }

  // 더 할 수 없을 때 보이는 상자. 마크업 id는 <id>, <id>-title, <id>-text, <id>-action
  function stateBox(id) {
    var box = document.getElementById(id);
    var title = document.getElementById(id + '-title');
    var text = document.getElementById(id + '-text');
    var action = document.getElementById(id + '-action');
    var onAction = null;
    action.addEventListener('click', function () { if (onAction) onAction(); });
    return {
      box: box,
      action: action,
      // button: {label, run}. 없으면 단추를 숨긴다
      show: function (titleText, bodyText, button) {
        box.hidden = false;
        title.textContent = titleText;
        text.textContent = bodyText || '';
        text.hidden = !bodyText;
        onAction = button ? button.run : null;
        action.hidden = !button;
        if (button) action.textContent = button.label;
      }
    };
  }

  // 되돌릴 수 없는 일을 한 번 더 묻는 상자.
  // opts: {id, title, text, no, yes, busy(누른 뒤 글), focus('no'|'yes'), onNo, onYes}
  function confirmBox(opts) {
    var no = el('button', { class: 'btn btn-ghost', id: opts.id && opts.id + '-no', type: 'button', text: opts.no });
    var yes = el('button', { class: 'btn btn-primary', id: opts.id && opts.id + '-yes', type: 'button', text: opts.yes });
    var node = el('div', { class: 'ask', id: opts.id, hidden: true },
      el('p', { class: 'ask-title', text: opts.title }),
      el('p', { class: 'ask-text', text: opts.text }),
      el('div', { class: 'ask-actions' }, no, yes));
    function reset() {
      yes.disabled = false;
      no.disabled = false;
      yes.textContent = opts.yes;
    }
    no.addEventListener('click', function () { if (opts.onNo) opts.onNo(); });
    yes.addEventListener('click', function () { if (opts.onYes) opts.onYes(); });
    return {
      node: node,
      open: function () {
        reset();
        node.hidden = false;
        (opts.focus === 'no' ? no : yes).focus();
      },
      close: function () {
        node.hidden = true;
        reset();
      },
      busy: function () {
        yes.disabled = true;
        no.disabled = true;
        yes.textContent = opts.busy;
      },
      reset: reset
    };
  }

  // <dl class="facts">를 채운다. rows: [{k, v, small, num}], 빈 칸(null)은 건너뛴다
  function fillFacts(dl, rows) {
    dl.textContent = '';
    rows.forEach(function (row) {
      if (!row) return;
      dl.appendChild(el('div', null,
        el('dt', { text: row.k }),
        el('dd', { class: row.num ? 'num' : null }, row.v, row.small ? el('small', { text: row.small }) : null)));
    });
    return dl;
  }

  bk.el = el;
  bk.setHeader = setHeader;
  bk.setTitle = setTitle;
  bk.loadPerformanceHeader = loadPerformanceHeader;
  bk.syncBar = syncBar;
  bk.stateBox = stateBox;
  bk.confirmBox = confirmBox;
  bk.fillFacts = fillFacts;
})();
