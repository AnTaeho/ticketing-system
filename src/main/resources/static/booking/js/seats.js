(function () {
  'use strict';

  var POLL_MS = 1000;
  var RETRY_MS = 3000;
  var FLASH_MS = 1000;
  var NOTE_MS = 5000;
  var MAP_FAILS_BEFORE_NOTE = 3;
  var TAKEN_MESSAGE = '방금 다른 분이 고른 자리예요. 다른 자리를 골라 주세요';
  var BUSY_MESSAGE = bk.WORDS.busy + '. 잠시 뒤 다시 눌러 주세요';

  var body = document.body;
  var pid = body.dataset.performanceId;
  var maxSeats = Number(body.dataset.maxSeats) || 4;
  var showId = bk.positiveId(bk.query('show'));
  var tokenKey = bk.keys.queueToken(pid);
  var el = bk.el;

  var ui = {
    hint: document.getElementById('seats-hint'),
    hintClose: document.getElementById('seats-hint-close'),
    hintAgain: document.getElementById('seats-hint-again'),
    legend: document.getElementById('seats-legend'),
    tabs: document.getElementById('seats-tabs'),
    where: document.getElementById('seats-where'),
    mapState: document.getElementById('seats-map-state'),
    grid: document.getElementById('seats-grid'),
    bar: document.getElementById('seats-bar'),
    note: document.getElementById('seats-note'),
    pick: document.getElementById('seats-pick'),
    pickSeats: document.getElementById('seats-pick-seats'),
    pickSub: document.getElementById('seats-pick-sub'),
    submit: document.getElementById('seats-pick-submit')
  };
  var stop = bk.stateBox('seats-stop');

  var state = {
    map: null,          // 마지막으로 그린 좌석맵
    seatsById: {},      // id → 좌석(bk.seat 모양 + free)
    current: null,      // 보고 있는 구역
    picked: {},         // id → 고른 좌석
    flashes: {},        // id → 놓친 자리 표시가 끝나는 시각
    failures: 0,        // 좌석맵을 이어서 못 받은 횟수
    afterMap: null,     // 다음 좌석맵을 그린 뒤 한 번 할 일
    submitting: false,
    leaving: false,
    stopped: false
  };
  // 다시 그리지 않으려고 기억해 두는 모양
  var drawn = { buttons: {}, tabButtons: [], tabNames: null, gridShape: null, legendKey: null };

  var mapPoller = bk.poller({
    interval: function () { return state.failures >= MAP_FAILS_BEFORE_NOTE ? RETRY_MS : POLL_MS; },
    run: function () { return bk.get('/api/performances/' + pid + '/seats'); },
    onData: mapLoaded,
    onError: mapFailed
  });

  // ---- 알림 ----

  // kind가 'map'이면 좌석맵을 다시 받을 때까지 남겨 둔다
  var note = {
    timer: null,
    kind: null,
    show: function (message, kind) {
      clearTimeout(note.timer);
      ui.note.textContent = message;
      note.kind = kind || 'once';
      if (note.kind !== 'map') note.timer = setTimeout(function () { note.clear(); }, NOTE_MS);
    },
    clear: function (kind) {
      if (kind && note.kind !== kind) return;
      clearTimeout(note.timer);
      ui.note.textContent = '';
      note.kind = null;
    }
  };

  function showStop(title, text, button) {
    state.stopped = true;
    note.clear();
    stop.show(title, text, button);
    ui.pick.hidden = true;
    body.classList.add('is-stopped');
    ui.grid.inert = true;
    if (button) stop.action.focus();
  }

  // ---- 안내 ----

  function setHint(visible) {
    ui.hint.hidden = !visible;
    ui.hintAgain.parentNode.hidden = visible;
  }

  ui.hintClose.addEventListener('click', function () {
    bk.local.set(bk.keys.seatsHint, 'done');
    setHint(false);
    ui.hintAgain.focus();
  });

  ui.hintAgain.addEventListener('click', function () {
    setHint(true);
    window.scrollTo(0, 0);
    ui.hintClose.focus();
  });

  // ---- 좌석맵 정리 ----

  // 좌석을 bk.seat 모양으로 바꿔 줄 안에 그대로 두고 id로도 찾게 한다
  function index(snapshot) {
    state.seatsById = {};
    snapshot.sections.forEach(function (sec) {
      sec.rows.forEach(function (row) {
        row.seats = row.seats.map(function (raw) {
          var seat = bk.seat(raw, sec.section, row.rowNo);
          seat.free = raw.s === 'A';
          state.seatsById[String(seat.id)] = seat;
          return seat;
        });
      });
    });
  }

  function sectionOf(name) {
    return state.map.sections.filter(function (sec) { return sec.section === name; })[0];
  }

  function freeCount(sec) {
    var n = 0;
    sec.rows.forEach(function (row) {
      row.seats.forEach(function (seat) { if (seat.free) n++; });
    });
    return n;
  }

  // 무대에서 본 구역 위치
  function side(name) {
    var names = state.map.sections.map(function (sec) { return sec.section; });
    var i = names.indexOf(name);
    var n = names.length;
    if (n === 1) return '무대 정면';
    if (i === 0) return '무대 왼쪽 끝';
    if (i === n - 1) return '무대 오른쪽 끝';
    if (n % 2 === 1 && i === (n - 1) / 2) return '무대 정면';
    return i < n / 2 ? '무대 가운데 왼쪽' : '무대 가운데 오른쪽';
  }

  // ---- 좌석맵 그리기 ----

  function drawLegend() {
    var grades = {};
    Object.keys(state.seatsById).forEach(function (id) {
      var seat = state.seatsById[id];
      grades[seat.grade] = seat.price;
    });
    var list = Object.keys(grades).sort(function (a, b) { return grades[b] - grades[a]; });
    var key = list.map(function (g) { return g + grades[g]; }).join(',');
    if (key === drawn.legendKey) return;
    drawn.legendKey = key;
    ui.legend.textContent = '';
    list.forEach(function (g) {
      ui.legend.appendChild(el('li', { class: 'g-' + g },
        el('span', { class: 'chip', 'aria-hidden': 'true' }),
        el('b', { text: g + '석' }),
        el('span', { class: 'price num', text: bk.won(grades[g]) })));
    });
  }

  function buildTabs() {
    ui.tabs.textContent = '';
    drawn.tabButtons = state.map.sections.map(function (sec) {
      var tab = el('button', {
        class: 'tab', type: 'button', role: 'tab', id: 'seats-tab-' + sec.section,
        'aria-controls': 'seats-grid', 'data-section': sec.section
      }, el('strong', { text: sec.section + '구역' }), el('span', { class: 'num' }));
      ui.tabs.appendChild(tab);
      return tab;
    });
    var n = state.map.sections.length;
    ui.tabs.style.gridTemplateColumns = n === 4 ? '' : 'repeat(' + n + ',minmax(64px,1fr))';
    ui.tabs.classList.toggle('many', n > 5);
  }

  function drawTabs() {
    var names = state.map.sections.map(function (sec) { return sec.section; }).join(',');
    if (names !== drawn.tabNames) {
      drawn.tabNames = names;
      buildTabs();
    }
    state.map.sections.forEach(function (sec, i) {
      var tab = drawn.tabButtons[i];
      tab.setAttribute('aria-selected', String(sec.section === state.current));
      tab.lastChild.textContent = bk.count(freeCount(sec)) + '석 남음';
    });
  }

  // 8석 이상이면 가운데에 통로를 둔다. 통로 앞 번호를 돌려주고, 없으면 0
  function aisleAfter(cols) {
    return cols >= 8 ? Math.ceil(cols / 2) : 0;
  }

  function gridColumns(cols, half) {
    return half
      ? '14px repeat(' + half + ',minmax(var(--seat-min),1fr)) 4px repeat(' + (cols - half) + ',minmax(var(--seat-min),1fr)) 14px'
      : '14px repeat(' + cols + ',minmax(var(--seat-min),1fr)) 14px';
  }

  function gap() {
    return el('span', { 'aria-hidden': 'true' });
  }

  // 맨 위 좌석 번호 줄
  function appendNumberRow(frag, cols, half) {
    frag.appendChild(gap());
    for (var no = 1; no <= cols; no++) {
      frag.appendChild(el('span', { class: 'cn num', 'aria-hidden': 'true', text: String(no) }));
      if (no === half) frag.appendChild(gap());
    }
    frag.appendChild(gap());
  }

  // 열 번호, 좌석 단추(없는 번호는 빈칸), 열 번호
  function appendSeatRow(frag, row, cols, half) {
    var byNo = {};
    row.seats.forEach(function (seat) { byNo[seat.seatNo] = seat; });
    frag.appendChild(el('span', { class: 'rn num', 'aria-hidden': 'true', text: String(row.rowNo) }));
    for (var n = 1; n <= cols; n++) {
      var seat = byNo[n];
      if (seat) {
        var button = el('button', { class: 'seat', type: 'button', 'data-id': String(seat.id) });
        drawn.buttons[String(seat.id)] = button;
        frag.appendChild(button);
      } else {
        frag.appendChild(gap());
      }
      if (n === half) frag.appendChild(gap());
    }
    frag.appendChild(el('span', { class: 'rn num', 'aria-hidden': 'true', text: String(row.rowNo) }));
  }

  function buildGrid(sec) {
    var cols = 0;
    sec.rows.forEach(function (row) {
      row.seats.forEach(function (seat) { cols = Math.max(cols, seat.seatNo); });
    });
    var half = aisleAfter(cols);
    ui.grid.style.gridTemplateColumns = gridColumns(cols, half);

    var frag = document.createDocumentFragment();
    appendNumberRow(frag, cols, half);
    drawn.buttons = {};
    var prevGrade = null;
    sec.rows.forEach(function (row) {
      var grade = row.seats.length ? row.seats[0].grade : prevGrade;
      // 등급이 바뀌는 줄 사이에 점선을 긋는다
      if (prevGrade && grade !== prevGrade) frag.appendChild(el('i', { class: 'band', 'aria-hidden': 'true' }));
      prevGrade = grade;
      appendSeatRow(frag, row, cols, half);
    });
    ui.grid.textContent = '';
    ui.grid.appendChild(frag);
  }

  function seatLabel(seat) {
    return bk.seatName(seat) + ' ' + bk.seatPrice(seat);
  }

  function patchGrid() {
    var now = Date.now();
    Object.keys(drawn.buttons).forEach(function (id) {
      var seat = state.seatsById[id];
      var button = drawn.buttons[id];
      if (!seat) return;
      var off = !seat.free;
      var mine = !!state.picked[id];
      var lost = state.flashes[id] > now;
      button.className = 'seat g-' + seat.grade + (off ? ' off' : '') + (mine ? ' mine' : '') + (lost ? ' lost' : '');
      button.disabled = off;
      button.setAttribute('aria-pressed', String(mine));
      button.setAttribute('aria-label', seatLabel(seat));
    });
  }

  function drawSection() {
    var sec = sectionOf(state.current);
    var shape = sec.section + '|' + sec.rows.map(function (row) { return row.rowNo + ':' + row.seats.length; }).join(',');
    if (shape !== drawn.gridShape) {
      drawn.gridShape = shape;
      buildGrid(sec);
    }
    ui.grid.setAttribute('aria-labelledby', 'seats-tab-' + sec.section);
    ui.where.textContent = side(sec.section);
    patchGrid();
  }

  function draw() {
    drawLegend();
    drawTabs();
    drawSection();
    ui.mapState.hidden = true;
    ui.grid.hidden = false;
  }

  function showSection(name) {
    state.current = name;
    drawTabs();
    drawSection();
  }

  function showMapMessage(text) {
    ui.mapState.textContent = text;
    ui.mapState.hidden = false;
  }

  // ---- 고른 자리 ----

  function pickedList() {
    return Object.keys(state.picked).map(function (id) {
      return state.seatsById[id] || state.picked[id];
    }).sort(bk.bySeat);
  }

  function flash(id) {
    state.flashes[id] = Date.now() + FLASH_MS;
    setTimeout(function () {
      if (state.flashes[id] <= Date.now()) delete state.flashes[id];
      if (state.map) patchGrid();
    }, FLASH_MS + 20);
  }

  // 놓친 자리를 고른 목록에서 빼고 격자에 잠깐 표시한다
  function drop(ids) {
    var dropped = 0;
    ids.forEach(function (raw) {
      var id = String(raw);
      if (state.picked[id]) {
        delete state.picked[id];
        dropped++;
      }
      flash(id);
    });
    return dropped;
  }

  function dropTaken() {
    var lost = Object.keys(state.picked).filter(function (id) {
      return !state.seatsById[id] || !state.seatsById[id].free;
    });
    if (lost.length === 0) return 0;
    drop(lost);
    note.show(TAKEN_MESSAGE);
    return lost.length;
  }

  // 한 자리를 놓쳤다: 그 구역을 보이고 목록에서 빼고 알린다
  function loseSeat(id, message) {
    var seat = state.seatsById[String(id)];
    if (seat && seat.section !== state.current) showSection(seat.section);
    drop([id]);
    note.show(message);
    patchGrid();
  }

  function renderPickSummary(list) {
    ui.pickSub.textContent = '';
    if (list.length === 0) {
      ui.pickSeats.textContent = '아직 고른 자리가 없어요';
      ui.pickSub.textContent = '한 사람이 ' + maxSeats + '석까지 고를 수 있어요';
      return;
    }
    var total = list.reduce(function (sum, seat) { return sum + seat.price; }, 0);
    ui.pickSeats.textContent = bk.seatText(list);
    ui.pickSub.appendChild(document.createTextNode(bk.gradeText(list) + ' · '));
    ui.pickSub.appendChild(el('b', { class: 'num', text: bk.won(total) }));
    ui.pickSub.appendChild(document.createTextNode(' '));
    ui.pickSub.appendChild(el('span', { class: 'num', text: '(' + list.length + '/' + maxSeats + '석)' }));
  }

  function renderPick() {
    var list = pickedList();
    renderPickSummary(list);
    ui.submit.disabled = list.length === 0 || state.submitting || state.stopped;
    ui.submit.classList.toggle('is-empty', list.length === 0);
    ui.submit.textContent = state.submitting ? '자리를 잡고 있어요'
      : list.length === 0 ? '자리를 골라 주세요' : '이 자리로 할게요';
  }

  ui.grid.addEventListener('click', function (e) {
    var button = e.target.closest('.seat');
    if (!button || button.disabled || state.submitting || state.stopped) return;
    var id = button.dataset.id;
    if (state.picked[id]) {
      delete state.picked[id];
    } else if (Object.keys(state.picked).length >= maxSeats) {
      note.show('한 번에 ' + maxSeats + '석까지 고를 수 있어요');
      return;
    } else {
      state.picked[id] = state.seatsById[id];
    }
    patchGrid();
    renderPick();
  });

  ui.tabs.addEventListener('click', function (e) {
    var tab = e.target.closest('.tab');
    if (!tab || !state.map) return;
    showSection(tab.dataset.section);
  });

  // ---- 좌석맵 받기 ----

  function render(snapshot) {
    if (state.map && snapshot.generatedAt === state.map.generatedAt) return;
    state.map = snapshot;
    index(snapshot);
    if (state.map.sections.length === 0) {
      showMapMessage('고를 수 있는 자리가 없어요');
      ui.grid.hidden = true;
      return;
    }
    if (!state.current || !sectionOf(state.current)) state.current = state.map.sections[0].section;
    // 내가 보낸 선점이 막 커밋되면 내 자리도 잡힌 자리로 보이므로 그동안은 비교하지 않는다
    if (!state.submitting) dropTaken();
    draw();
    renderPick();
  }

  function runAfterMap() {
    var then = state.afterMap;
    state.afterMap = null;
    if (then) then();
  }

  function mapLoaded(snapshot) {
    state.failures = 0;
    note.clear('map');
    render(snapshot);
    runAfterMap();
  }

  function mapFailed(e) {
    runAfterMap();
    state.failures++;
    if (e.status === 404) {
      showMapMessage('공연 날짜를 찾지 못했어요');
      ui.grid.hidden = true;
      showStop('공연 날짜를 찾지 못했어요', '공연 정보에서 날짜를 다시 골라 주세요.',
        { label: '공연 정보로 가기', run: function () { location.href = bk.links.show(showId); } });
      mapPoller.idle();
      return;
    }
    var network = bk.isNetworkError(e);
    if (!state.map) {
      showMapMessage(network
        ? bk.WORDS.network + '. 연결되면 저절로 다시 불러와요'
        : '자리 정보를 불러오지 못했어요. 잠시 뒤 저절로 다시 불러와요');
    } else if (state.failures >= MAP_FAILS_BEFORE_NOTE) {
      note.show(network
        ? bk.WORDS.network + '. 연결되면 자리 정보를 다시 불러와요'
        : '자리 정보를 새로 받지 못하고 있어요. 잠시 뒤 저절로 다시 불러와요', 'map');
    }
  }

  // 좌석맵을 곧바로 다시 받고, then은 그것을 그린 뒤 한 번 부른다
  function reloadMap(then) {
    if (state.leaving) return;
    state.afterMap = then || null;
    mapPoller.now();
  }

  // ---- 자리 잡기 ----

  function showHeldElsewhere() {
    bk.get('/api/performances/' + pid + '/holds/mine').then(function (h) {
      showStop('이미 잡아 둔 자리가 있어요', '결제 화면에서 결제하거나 자리를 놓을 수 있어요.',
        { label: '결제 화면으로 가기', run: function () { location.href = bk.links.checkout(h.holdId, showId); } });
    }, function (e) {
      note.show(e.code === 'HOLD_NOT_FOUND' ? '잡아 둔 자리가 방금 풀렸어요. 다시 눌러 주세요' : e.message);
    });
  }

  function showNotAdmitted() {
    showStop('입장 순서가 지났어요', '다시 줄을 서 주세요. 맨 뒤부터 다시 기다려요.',
      { label: '다시 줄 서기', run: function () {
        bk.session.remove(tokenKey);
        location.href = bk.links.queue(pid, showId);
      } });
  }

  // DB는 어느 자리인지 알려 주지 않으므로 새 좌석맵에서 잡힌 자리를 찾는다. 못 찾으면 보낸 자리를 모두 뺀다
  function retakeChanged(sent) {
    reloadMap(function () {
      var gone = sent.filter(function (id) { return !state.picked[id]; }).length;
      if (gone === 0) gone = dropTaken();
      if (gone === 0) {
        drop(sent);
        note.show(TAKEN_MESSAGE);
      }
      if (state.map) patchGrid();
      renderPick();
    });
  }

  // 코드마다 화면이 하는 일. 없는 코드는 서버 문구를 띠에 띄운다
  var HOLD_FAILURES = {
    SEAT_TAKEN: function (e) { loseSeat(e.detail.seatId, TAKEN_MESSAGE); reloadMap(); },
    SEAT_STATE_CHANGED: function (e, ids) { retakeChanged((e.detail.seatIds || ids).map(String)); },
    COOLDOWN: function (e) { loseSeat(e.detail.seatId, e.message); },
    USER_HAS_HOLD: showHeldElsewhere,
    NOT_ADMITTED: showNotAdmitted,
    INVALID_SEAT_SELECTION: function (e) { note.show(e.message); reloadMap(); },
    BUSY: function () { note.show(BUSY_MESSAGE); },
    QUEUE_UNAVAILABLE: function () { note.show(BUSY_MESSAGE); }
  };

  function holdFailed(e, ids) {
    if (Object.prototype.hasOwnProperty.call(HOLD_FAILURES, e.code)) HOLD_FAILURES[e.code](e, ids);
    else note.show(e.message);
  }

  function holdPicked() {
    var list = pickedList();
    if (list.length === 0 || state.submitting || state.stopped) return;
    state.submitting = true;
    note.clear();
    renderPick();
    var seatIds = list.map(function (seat) { return seat.id; });
    bk.post('/api/performances/' + pid + '/holds', {
      seatIds: seatIds,
      queueToken: bk.session.get(tokenKey)
    }).then(function (r) {
      state.leaving = true;
      mapPoller.stop();
      bk.local.set(bk.keys.seatsHint, 'done');
      // 뒤로 가기로 이미 잡은 자리를 다시 고르는 화면에 오지 않게 기록을 바꾼다
      location.replace(bk.links.checkout(r.holdId, showId));
    }, function (e) {
      state.submitting = false;
      holdFailed(e, seatIds);
      renderPick();
    });
  }

  ui.submit.addEventListener('click', holdPicked);

  setHint(bk.local.get(bk.keys.seatsHint) !== 'done');
  renderPick();
  bk.syncBar(ui.bar);
  bk.loadPerformanceHeader(showId, pid);
  mapPoller.now();
})();
