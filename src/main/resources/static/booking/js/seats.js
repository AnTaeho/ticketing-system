(function () {
  'use strict';

  var POLL_MS = 1000;
  var RETRY_MS = 3000;
  var FLASH_MS = 1000;
  var NOTE_MS = 5000;
  var MAP_FAILS_BEFORE_NOTE = 3;
  var HINT_KEY = 'bk_hint_seats';
  var TAKEN_MESSAGE = '방금 다른 분이 고른 자리예요. 다른 자리를 골라 주세요';
  var BUSY_MESSAGE = '사람이 많아 잠시 막혔어요. 잠시 뒤 다시 눌러 주세요';

  var body = document.body;
  var pid = body.dataset.performanceId;
  var maxSeats = Number(body.dataset.maxSeats) || 4;
  var showId = bk.positiveId(bk.query('show'));
  var tokenKey = 'bk_queue_' + pid;
  var showHref = showId ? '/booking/shows/' + showId : '/booking/shows';
  var queueHref = '/booking/performances/' + pid + '/queue' + (showId ? '?show=' + showId : '');
  var el = bk.el;

  var hint = document.getElementById('hint');
  var hintClose = document.getElementById('hint-close');
  var hintAgain = document.getElementById('hint-again');
  var legend = document.getElementById('legend');
  var tabs = document.getElementById('tabs');
  var where = document.getElementById('where');
  var mapState = document.getElementById('map-state');
  var grid = document.getElementById('seat-grid');
  var bar = document.getElementById('bar');
  var note = document.getElementById('seat-note');
  var pick = document.getElementById('pick');
  var pickSeats = document.getElementById('pick-seats');
  var pickSub = document.getElementById('pick-sub');
  var submit = document.getElementById('pick-submit');
  var stop = document.getElementById('stop');
  var stopTitle = document.getElementById('stop-title');
  var stopSub = document.getElementById('stop-sub');
  var stopAction = document.getElementById('stop-action');

  var map = null;
  var seatsById = {};
  var buttons = {};
  var tabButtons = [];
  var tabNames = null;
  var gridShape = null;
  var legendKey = null;
  var current = null;
  var picked = {};
  var flashes = {};
  var timer = null;
  var loadSeq = 0;
  var failures = 0;
  var noteTimer = null;
  var noteKind = null;
  var submitting = false;
  var leaving = false;
  var stopped = false;
  var onStop = null;

  // ---- 알림·상자 ----

  // kind가 'map'이면 좌석맵을 다시 받을 때까지 남겨 둔다
  function showNote(message, kind) {
    clearTimeout(noteTimer);
    note.textContent = message;
    noteKind = kind || 'once';
    if (noteKind !== 'map') noteTimer = setTimeout(function () { clearNote(); }, NOTE_MS);
  }

  function clearNote(kind) {
    if (kind && noteKind !== kind) return;
    clearTimeout(noteTimer);
    note.textContent = '';
    noteKind = null;
  }

  function showStop(title, text, button) {
    stopped = true;
    clearNote();
    stopTitle.textContent = title;
    stopSub.textContent = text || '';
    stopSub.hidden = !text;
    onStop = button ? button.run : null;
    stopAction.hidden = !button;
    if (button) stopAction.textContent = button.label;
    pick.hidden = true;
    stop.hidden = false;
    body.classList.add('is-stopped');
    grid.inert = true;
    if (button) stopAction.focus();
  }

  stopAction.addEventListener('click', function () { if (onStop) onStop(); });

  // 띠 높이만큼 본문 아래를 비워 마지막 줄이 띠에 가리지 않게 한다
  function syncBar() {
    body.style.setProperty('--bar-h', bar.offsetHeight + 'px');
  }

  // ---- 안내 ----

  function setHint(visible) {
    hint.hidden = !visible;
    hintAgain.parentNode.hidden = visible;
  }

  hintClose.addEventListener('click', function () {
    bk.local.set(HINT_KEY, 'done');
    setHint(false);
    hintAgain.focus();
  });

  hintAgain.addEventListener('click', function () {
    setHint(true);
    window.scrollTo(0, 0);
    hintClose.focus();
  });

  // ---- 좌석맵 그리기 ----

  function label(seat) {
    return seat.section + '구역 ' + seat.rowNo + '열 ' + seat.no + '번 ' + seat.grade + '석 ' + bk.won(seat.price);
  }

  function index(snapshot) {
    seatsById = {};
    snapshot.sections.forEach(function (sec) {
      sec.rows.forEach(function (row) {
        row.seats.forEach(function (seat) {
          seatsById[String(seat.id)] = {
            id: seat.id, section: sec.section, rowNo: row.rowNo, no: seat.no,
            grade: seat.grade, price: seat.price, s: seat.s
          };
        });
      });
    });
  }

  function sectionOf(name) {
    return map.sections.filter(function (sec) { return sec.section === name; })[0];
  }

  function left(sec) {
    var n = 0;
    sec.rows.forEach(function (row) {
      row.seats.forEach(function (seat) { if (seat.s === 'A') n++; });
    });
    return n;
  }

  // 무대에서 본 구역 위치
  function side(name) {
    var names = map.sections.map(function (sec) { return sec.section; });
    var i = names.indexOf(name);
    var n = names.length;
    if (n === 1) return '무대 정면';
    if (i === 0) return '무대 왼쪽 끝';
    if (i === n - 1) return '무대 오른쪽 끝';
    if (n % 2 === 1 && i === (n - 1) / 2) return '무대 정면';
    return i < n / 2 ? '무대 가운데 왼쪽' : '무대 가운데 오른쪽';
  }

  function drawLegend() {
    var grades = {};
    Object.keys(seatsById).forEach(function (id) {
      var seat = seatsById[id];
      grades[seat.grade] = seat.price;
    });
    var list = Object.keys(grades).sort(function (a, b) { return grades[b] - grades[a]; });
    var key = list.map(function (g) { return g + grades[g]; }).join(',');
    if (key === legendKey) return;
    legendKey = key;
    legend.textContent = '';
    list.forEach(function (g) {
      legend.appendChild(el('li', { class: 'g-' + g },
        el('span', { class: 'chip', 'aria-hidden': 'true' }),
        el('b', { text: g + '석' }),
        el('span', { class: 'price num', text: bk.won(grades[g]) })));
    });
  }

  function drawTabs() {
    var names = map.sections.map(function (sec) { return sec.section; }).join(',');
    if (names !== tabNames) {
      tabNames = names;
      tabs.textContent = '';
      tabButtons = map.sections.map(function (sec) {
        var tab = el('button', {
          class: 'tab', type: 'button', role: 'tab', id: 'tab-' + sec.section,
          'aria-controls': 'seat-grid', 'data-section': sec.section
        }, el('strong', { text: sec.section + '구역' }), el('span', { class: 'num' }));
        tabs.appendChild(tab);
        return tab;
      });
      var n = map.sections.length;
      tabs.style.gridTemplateColumns = n === 4 ? '' : 'repeat(' + n + ',minmax(64px,1fr))';
      tabs.classList.toggle('many', n > 5);
    }
    map.sections.forEach(function (sec, i) {
      var tab = tabButtons[i];
      tab.setAttribute('aria-selected', String(sec.section === current));
      tab.lastChild.textContent = bk.count(left(sec)) + '석 남음';
    });
  }

  function buildGrid(sec) {
    var cols = 0;
    sec.rows.forEach(function (row) {
      row.seats.forEach(function (seat) { cols = Math.max(cols, seat.no); });
    });
    var half = cols >= 8 ? Math.ceil(cols / 2) : 0;
    grid.style.gridTemplateColumns = half
      ? '14px repeat(' + half + ',minmax(var(--seat-min),1fr)) 4px repeat(' + (cols - half) + ',minmax(var(--seat-min),1fr)) 14px'
      : '14px repeat(' + cols + ',minmax(var(--seat-min),1fr)) 14px';

    var frag = document.createDocumentFragment();
    function gap() { frag.appendChild(el('span', { 'aria-hidden': 'true' })); }
    gap();
    for (var no = 1; no <= cols; no++) {
      frag.appendChild(el('span', { class: 'cn num', 'aria-hidden': 'true', text: String(no) }));
      if (no === half) gap();
    }
    gap();

    buttons = {};
    var prevGrade = null;
    sec.rows.forEach(function (row) {
      var grade = row.seats.length ? row.seats[0].grade : prevGrade;
      // 등급이 바뀌는 줄 사이에 점선을 긋는다
      if (prevGrade && grade !== prevGrade) frag.appendChild(el('i', { class: 'band', 'aria-hidden': 'true' }));
      prevGrade = grade;
      var byNo = {};
      row.seats.forEach(function (seat) { byNo[seat.no] = seat; });
      frag.appendChild(el('span', { class: 'rn num', 'aria-hidden': 'true', text: String(row.rowNo) }));
      for (var n = 1; n <= cols; n++) {
        var seat = byNo[n];
        if (seat) {
          var button = el('button', { class: 'seat', type: 'button', 'data-id': String(seat.id) });
          buttons[String(seat.id)] = button;
          frag.appendChild(button);
        } else {
          gap();
        }
        if (n === half) gap();
      }
      frag.appendChild(el('span', { class: 'rn num', 'aria-hidden': 'true', text: String(row.rowNo) }));
    });
    grid.textContent = '';
    grid.appendChild(frag);
  }

  function patchGrid() {
    var now = Date.now();
    Object.keys(buttons).forEach(function (id) {
      var seat = seatsById[id];
      var button = buttons[id];
      if (!seat) return;
      var off = seat.s !== 'A';
      var mine = !!picked[id];
      var lost = flashes[id] > now;
      button.className = 'seat g-' + seat.grade + (off ? ' off' : '') + (mine ? ' mine' : '') + (lost ? ' lost' : '');
      button.disabled = off;
      button.setAttribute('aria-pressed', String(mine));
      button.setAttribute('aria-label', label(seat));
    });
  }

  function drawSection() {
    var sec = sectionOf(current);
    var shape = sec.section + '|' + sec.rows.map(function (row) { return row.rowNo + ':' + row.seats.length; }).join(',');
    if (shape !== gridShape) {
      gridShape = shape;
      buildGrid(sec);
    }
    grid.setAttribute('aria-labelledby', 'tab-' + sec.section);
    where.textContent = side(sec.section);
    patchGrid();
  }

  function draw() {
    drawLegend();
    drawTabs();
    drawSection();
    mapState.hidden = true;
    grid.hidden = false;
  }

  // ---- 고른 자리 ----

  function pickedList() {
    return Object.keys(picked).map(function (id) { return seatsById[id] || picked[id]; }).sort(function (a, b) {
      if (a.section !== b.section) return a.section < b.section ? -1 : 1;
      return a.rowNo - b.rowNo || a.no - b.no;
    });
  }

  function flash(id) {
    flashes[id] = Date.now() + FLASH_MS;
    setTimeout(function () {
      if (flashes[id] <= Date.now()) delete flashes[id];
      if (map) patchGrid();
    }, FLASH_MS + 20);
  }

  // 놓친 자리를 고른 목록에서 빼고 격자에 잠깐 표시한다
  function drop(ids) {
    var dropped = 0;
    ids.forEach(function (raw) {
      var id = String(raw);
      if (picked[id]) {
        delete picked[id];
        dropped++;
      }
      flash(id);
    });
    return dropped;
  }

  function dropTaken() {
    var lost = Object.keys(picked).filter(function (id) {
      return !seatsById[id] || seatsById[id].s !== 'A';
    });
    if (lost.length === 0) return 0;
    drop(lost);
    showNote(TAKEN_MESSAGE);
    return lost.length;
  }

  // A구역 3열 9번, 10번 / B구역 1열 2번
  function seatsText(list) {
    var groups = [];
    list.forEach(function (seat) {
      var last = groups[groups.length - 1];
      if (last && last.section === seat.section && last.rowNo === seat.rowNo) last.nos.push(seat.no);
      else groups.push({ section: seat.section, rowNo: seat.rowNo, nos: [seat.no] });
    });
    return groups.map(function (g) {
      return g.section + '구역 ' + g.rowNo + '열 ' + g.nos.map(function (n) { return n + '번'; }).join(', ');
    }).join(' / ');
  }

  // R석 1매 · S석 1매
  function gradesText(list) {
    var counts = {};
    var prices = {};
    list.forEach(function (seat) {
      counts[seat.grade] = (counts[seat.grade] || 0) + 1;
      prices[seat.grade] = seat.price;
    });
    return Object.keys(counts).sort(function (a, b) { return prices[b] - prices[a]; }).map(function (g) {
      return g + '석 ' + counts[g] + '매';
    }).join(' · ');
  }

  function renderPick() {
    var list = pickedList();
    pickSub.textContent = '';
    if (list.length === 0) {
      pickSeats.textContent = '아직 고른 자리가 없어요';
      pickSub.textContent = '한 사람이 ' + maxSeats + '석까지 고를 수 있어요';
    } else {
      var total = list.reduce(function (sum, seat) { return sum + seat.price; }, 0);
      pickSeats.textContent = seatsText(list);
      pickSub.appendChild(document.createTextNode(gradesText(list) + ' · '));
      pickSub.appendChild(el('b', { class: 'num', text: bk.won(total) }));
      pickSub.appendChild(document.createTextNode(' '));
      pickSub.appendChild(el('span', { class: 'num', text: '(' + list.length + '/' + maxSeats + '석)' }));
    }
    submit.disabled = list.length === 0 || submitting || stopped;
    submit.classList.toggle('is-empty', list.length === 0);
    submit.textContent = submitting ? '자리를 잡고 있어요' : list.length === 0 ? '자리를 골라 주세요' : '이 자리로 할게요';
  }

  grid.addEventListener('click', function (e) {
    var button = e.target.closest('.seat');
    if (!button || button.disabled || submitting || stopped) return;
    var id = button.dataset.id;
    if (picked[id]) {
      delete picked[id];
    } else if (Object.keys(picked).length >= maxSeats) {
      showNote('한 번에 ' + maxSeats + '석까지 고를 수 있어요');
      return;
    } else {
      picked[id] = seatsById[id];
    }
    patchGrid();
    renderPick();
  });

  tabs.addEventListener('click', function (e) {
    var tab = e.target.closest('.tab');
    if (!tab || !map) return;
    current = tab.dataset.section;
    drawTabs();
    drawSection();
  });

  // ---- 좌석맵 받기 ----

  function render(snapshot) {
    if (map && snapshot.generatedAt === map.generatedAt) return;
    map = snapshot;
    index(snapshot);
    if (map.sections.length === 0) {
      mapState.textContent = '고를 수 있는 자리가 없어요';
      mapState.hidden = false;
      grid.hidden = true;
      return;
    }
    if (!current || !sectionOf(current)) current = map.sections[0].section;
    // 내가 보낸 선점이 막 커밋되면 내 자리도 잡힌 자리로 보이므로 그동안은 비교하지 않는다
    if (!submitting) dropTaken();
    draw();
    renderPick();
  }

  function schedule(ms) {
    clearTimeout(timer);
    if (!leaving && !document.hidden) timer = setTimeout(load, ms);
  }

  function mapFailed(e) {
    failures++;
    if (e.status === 404) {
      mapState.textContent = '공연 날짜를 찾지 못했어요';
      mapState.hidden = false;
      grid.hidden = true;
      showStop('공연 날짜를 찾지 못했어요', '공연 정보에서 날짜를 다시 골라 주세요.',
        { label: '공연 정보로 가기', run: function () { location.href = showHref; } });
      return;
    }
    var network = e.code === 'NETWORK';
    if (!map) {
      mapState.textContent = network
        ? '인터넷 연결이 불안정해요. 연결되면 저절로 다시 불러와요'
        : '자리 정보를 불러오지 못했어요. 잠시 뒤 저절로 다시 불러와요';
      mapState.hidden = false;
    } else if (failures >= MAP_FAILS_BEFORE_NOTE) {
      showNote(network
        ? '인터넷 연결이 불안정해요. 연결되면 자리 정보를 다시 불러와요'
        : '자리 정보를 새로 받지 못하고 있어요. 잠시 뒤 저절로 다시 불러와요', 'map');
    }
    schedule(failures >= MAP_FAILS_BEFORE_NOTE ? RETRY_MS : POLL_MS);
  }

  // then은 이번 요청의 좌석맵을 그린 뒤 한 번 부른다
  function load(then) {
    clearTimeout(timer);
    if (leaving) return;
    var seq = ++loadSeq;
    bk.get('/api/performances/' + pid + '/seats').then(function (snapshot) {
      if (seq !== loadSeq) return;
      failures = 0;
      clearNote('map');
      render(snapshot);
      if (typeof then === 'function') then(true);
      schedule(POLL_MS);
    }, function (e) {
      if (seq !== loadSeq) return;
      if (typeof then === 'function') then(false);
      mapFailed(e);
    });
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      clearTimeout(timer);
      loadSeq++;
    } else {
      load();
    }
  });

  // ---- 선점 ----

  function checkoutHref(holdId) {
    return '/booking/holds/' + encodeURIComponent(holdId) + '/checkout' + (showId ? '?show=' + showId : '');
  }

  function moveTo(id) {
    var seat = seatsById[String(id)];
    if (seat && seat.section !== current) {
      current = seat.section;
      drawTabs();
      drawSection();
    }
  }

  function showHeldElsewhere() {
    bk.get('/api/performances/' + pid + '/holds/mine').then(function (h) {
      showStop('이미 잡아 둔 자리가 있어요', '결제 화면에서 결제하거나 자리를 놓을 수 있어요.',
        { label: '결제 화면으로 가기', run: function () { location.href = checkoutHref(h.holdId); } });
    }, function (e) {
      showNote(e.code === 'HOLD_NOT_FOUND' ? '잡아 둔 자리가 방금 풀렸어요. 다시 눌러 주세요' : e.message);
    });
  }

  // 새 좌석맵이 이미 뺀 자리가 있으면 나머지는 그대로 둔다
  function retakeChanged(sent) {
    load(function () {
      var gone = sent.filter(function (id) { return !picked[id]; }).length;
      if (gone === 0) gone = dropTaken();
      if (gone === 0) {
        drop(sent);
        showNote(TAKEN_MESSAGE);
      }
      if (map) patchGrid();
      renderPick();
    });
  }

  function holdFailed(e, ids) {
    var detail = e.detail || {};
    switch (e.code) {
      case 'SEAT_TAKEN':
        moveTo(detail.seatId);
        drop([detail.seatId]);
        showNote(TAKEN_MESSAGE);
        patchGrid();
        load();
        return;
      case 'SEAT_STATE_CHANGED':
        // DB는 어느 자리인지 알려 주지 않으므로 새 좌석맵에서 잡힌 자리를 찾는다. 못 찾으면 보낸 자리를 모두 뺀다
        retakeChanged((detail.seatIds || ids).map(String));
        return;
      case 'COOLDOWN':
        moveTo(detail.seatId);
        drop([detail.seatId]);
        showNote(e.message);
        patchGrid();
        return;
      case 'USER_HAS_HOLD':
        showHeldElsewhere();
        return;
      case 'NOT_ADMITTED':
        showStop('입장 순서가 지났어요', '다시 줄을 서 주세요. 맨 뒤부터 다시 기다려요.',
          { label: '다시 줄 서기', run: function () {
            bk.session.remove(tokenKey);
            location.href = queueHref;
          } });
        return;
      case 'INVALID_SEAT_SELECTION':
        showNote(e.message);
        load();
        return;
      case 'BUSY':
      case 'QUEUE_UNAVAILABLE':
        showNote(BUSY_MESSAGE);
        return;
      default:
        showNote(e.message);
    }
  }

  submit.addEventListener('click', function () {
    var list = pickedList();
    if (list.length === 0 || submitting || stopped) return;
    submitting = true;
    clearNote();
    renderPick();
    var seatIds = list.map(function (seat) { return seat.id; });
    bk.post('/api/performances/' + pid + '/holds', {
      seatIds: seatIds,
      queueToken: bk.session.get(tokenKey)
    }).then(function (r) {
      leaving = true;
      clearTimeout(timer);
      bk.local.set(HINT_KEY, 'done');
      // 뒤로 가기로 이미 잡은 자리를 다시 고르는 화면에 오지 않게 기록을 바꾼다
      location.replace(checkoutHref(r.holdId));
    }, function (e) {
      submitting = false;
      holdFailed(e, seatIds);
      renderPick();
    });
  });

  // ---- 머리글 ----

  function loadHeader() {
    bk.setHeader(null, null, showHref);
    if (!showId) return;
    bk.get('/api/shows/' + showId).then(function (show) {
      var performance = (show.performances || []).filter(function (p) { return String(p.id) === pid; })[0];
      var sub = performance ? bk.dateTime(performance.startsAt) + ' · ' + show.venueName : show.venueName;
      bk.setHeader(show.title, sub, showHref);
    }, function () { /* 머리글은 없어도 자리는 고른다 */ });
  }

  setHint(bk.local.get(HINT_KEY) !== 'done');
  renderPick();
  syncBar();
  if ('ResizeObserver' in window) new ResizeObserver(syncBar).observe(bar);
  else window.addEventListener('resize', syncBar);
  loadHeader();
  load();
})();
