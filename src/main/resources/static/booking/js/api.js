// 예매 화면들이 함께 쓰는 데이터 쪽 도구. 화면 부품은 bk-ui.js에 있다
(function () {
  'use strict';

  // 화면이 직접 정해 쓰는 말
  var WORDS = {
    network: '인터넷 연결이 불안정해요'
  };
  var NETWORK_MESSAGE = WORDS.network + '. 연결을 확인하고 다시 시도해 주세요';
  var FALLBACK_MESSAGE = '잠시 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요';
  var WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
  // 공연 시각은 서울 기준 시각 문자열로 온다
  var SEOUL_OFFSET_MS = 9 * 60 * 60 * 1000;

  function ApiError(status, code, message, detail) {
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.message = message;
    this.detail = detail || {};
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  // 응답을 받지 못한 실패. 시간 초과도 화면에는 연결 문제로 보인다
  function isNetworkError(e) {
    return e.code === 'NETWORK' || e.code === 'TIMEOUT';
  }

  function readJson(res) {
    return res.text().then(function (text) {
      if (!text) return null;
      try { return JSON.parse(text); } catch (e) { return null; }
    }, function () { return null; });
  }

  function loginUrl(next) {
    return '/booking/login?next=' + encodeURIComponent(next || (location.pathname + location.search));
  }

  // options.headers는 더할 머리글, options.timeoutMs가 지나면 TIMEOUT으로 끝낸다
  function request(method, path, body, options) {
    var opts = options || {};
    var init = { method: method, credentials: 'same-origin', headers: { 'Accept': 'application/json' } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    Object.assign(init.headers, opts.headers);
    if (opts.timeoutMs) init.signal = AbortSignal.timeout(opts.timeoutMs);
    return fetch(path, init).then(function (res) {
      return readJson(res).then(function (data) {
        if (res.ok) return data;
        var code = (data && data.code) || ('HTTP_' + res.status);
        if (res.status === 401 && code === 'LOGIN_REQUIRED') {
          location.href = loginUrl();
          // 화면을 옮기는 중이므로 호출한 쪽이 에러를 그리지 않게 끝나지 않는 약속을 돌려준다
          return new Promise(function () {});
        }
        throw new ApiError(res.status, code, (data && data.message) || FALLBACK_MESSAGE, data && data.detail);
      });
    }, function (e) {
      throw new ApiError(0, e.name === 'TimeoutError' ? 'TIMEOUT' : 'NETWORK', NETWORK_MESSAGE, {});
    });
  }

  // 같은 요청을 주기마다 보낸다. 늦게 온 응답은 버리고, 탭이 숨으면 멈췄다가 다시 보이면 곧바로 한 번 보낸다.
  // interval은 ms나 ms를 돌려주는 함수(null이면 다음 차례를 잡지 않는다). 처리기가 다음 차례를 정하지 않으면 interval 뒤에 다시 한다
  function poller(opts) {
    var timer = null;
    var seq = 0;
    var active = false;
    var decided = false;

    function awake() {
      return !document.hidden;
    }

    function nextDelay() {
      return typeof opts.interval === 'function' ? opts.interval() : opts.interval;
    }

    function plan(ms) {
      clearTimeout(timer);
      timer = null;
      decided = true;
      if (active && awake() && ms != null) timer = setTimeout(run, ms);
    }

    function finish(mine, handler, value) {
      if (mine !== seq || !active) return;
      decided = false;
      if (handler) handler(value);
      if (mine === seq && active && !decided) plan(nextDelay());
    }

    function run() {
      clearTimeout(timer);
      timer = null;
      if (!active || !awake()) return;
      var mine = ++seq;
      opts.run().then(function (value) { finish(mine, opts.onData, value); },
        function (e) { finish(mine, opts.onError, e); });
    }

    document.addEventListener('visibilitychange', function () {
      if (document.hidden) {
        clearTimeout(timer);
        timer = null;
        seq++;
      } else if (active && nextDelay() != null) {
        run();
      }
    });

    return {
      // 지금 한 번 하고 이어 간다. 날아가 있는 응답은 버린다
      now: function () { active = true; run(); },
      // 이번에는 다음 차례를 잡지 않는다. 탭이 다시 보이면 이어 간다
      idle: function () { plan(null); },
      stop: function () { active = false; seq++; clearTimeout(timer); timer = null; }
    };
  }

  function parseLocal(text) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?/.exec(text || '');
    if (!m) return null;
    return {
      y: +m[1], mo: +m[2], d: +m[3], h: +m[4], mi: +m[5], s: +(m[6] || 0),
      ms: m[7] ? +(m[7] + '00').slice(0, 3) : 0
    };
  }

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function weekday(t) {
    return WEEKDAYS[new Date(Date.UTC(t.y, t.mo - 1, t.d)).getUTCDay()];
  }

  // 2026.10.30(금) 19:30
  function dateTime(text) {
    var t = parseLocal(text);
    if (!t) return '';
    return t.y + '.' + pad(t.mo) + '.' + pad(t.d) + '(' + weekday(t) + ') ' + pad(t.h) + ':' + pad(t.mi);
  }

  // 10.30(금) 19:30
  function shortDateTime(text) {
    var t = parseLocal(text);
    if (!t) return '';
    return pad(t.mo) + '.' + pad(t.d) + '(' + weekday(t) + ') ' + pad(t.h) + ':' + pad(t.mi);
  }

  function epochOf(text) {
    var t = parseLocal(text);
    if (!t) return NaN;
    return Date.UTC(t.y, t.mo - 1, t.d, t.h, t.mi, t.s, t.ms) - SEOUL_OFFSET_MS;
  }

  // 9:58, 1:02:03
  function clockText(ms) {
    var total = Math.max(0, Math.ceil(ms / 1000));
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
  }

  // 180,000원
  function won(amount) {
    return Number(amount).toLocaleString('ko-KR') + '원';
  }

  function count(n) {
    return Number(n).toLocaleString('ko-KR');
  }

  // ---- 좌석 ----

  // 좌석맵(no, 구역·열은 바깥)과 잡기·예매 응답(seatNo, seatId)을 같은 모양으로 맞춘다
  function seat(raw, section, rowNo) {
    return {
      id: raw.id != null ? raw.id : raw.seatId,
      section: section != null ? section : raw.section,
      rowNo: rowNo != null ? rowNo : raw.rowNo,
      seatNo: raw.seatNo != null ? raw.seatNo : raw.no,
      grade: raw.grade,
      price: raw.price
    };
  }

  // 구역, 열, 번호 순
  function bySeat(a, b) {
    if (a.section !== b.section) return a.section < b.section ? -1 : 1;
    return a.rowNo - b.rowNo || a.seatNo - b.seatNo;
  }

  // A구역 3열 9번
  function seatName(s) {
    return s.section + '구역 ' + s.rowNo + '열 ' + s.seatNo + '번';
  }

  // R석 150,000원
  function seatPrice(s) {
    return s.grade + '석 ' + won(s.price);
  }

  // A구역 3열 9번, 10번 / B구역 1열 2번
  function seatText(seats) {
    var groups = [];
    seats.slice().sort(bySeat).forEach(function (s) {
      var last = groups[groups.length - 1];
      if (last && last.section === s.section && last.rowNo === s.rowNo) last.nos.push(s.seatNo);
      else groups.push({ section: s.section, rowNo: s.rowNo, nos: [s.seatNo] });
    });
    return groups.map(function (g) {
      return g.section + '구역 ' + g.rowNo + '열 ' + g.nos.map(function (n) { return n + '번'; }).join(', ');
    }).join(' / ');
  }

  // [{grade, count, price, sum}] 비싼 등급부터
  function gradeGroups(seats) {
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
  function gradeText(seats) {
    return gradeGroups(seats).map(function (g) { return g.grade + '석 ' + g.count + '매'; }).join(' · ');
  }

  // ---- 저장소·주소 ----

  function storage(kind) {
    function area() { return kind === 'local' ? window.localStorage : window.sessionStorage; }
    return {
      get: function (key) { try { return area().getItem(key); } catch (e) { return null; } },
      set: function (key, value) { try { area().setItem(key, value); } catch (e) { /* 저장 못 해도 화면은 돈다 */ } },
      remove: function (key) { try { area().removeItem(key); } catch (e) { /* 저장 못 해도 화면은 돈다 */ } }
    };
  }

  var keys = {
    // 같은 자리 결제 재시도에 쓰는 번호(sessionStorage)
    idempotency: function (holdId) { return 'bk_idem_' + holdId; },
    // 결제 확인 중 화면에 보일 결제 수단(sessionStorage)
    payMethod: function (holdId) { return 'bk_method_' + holdId; },
    // 좌석 화면 안내를 닫았는지(localStorage)
    seatsHint: 'bk_hint_seats'
  };

  // 공연을 고르고 들어온 화면은 ?show=로 공연 번호를 이어 받는다
  function withShow(path, showId) {
    return path + (showId ? '?show=' + showId : '');
  }

  var links = {
    shows: '/booking/shows',
    bookings: '/booking/bookings',
    show: function (showId) { return showId ? '/booking/shows/' + showId : '/booking/shows'; },
    seats: function (performanceId, showId) { return withShow('/booking/performances/' + performanceId + '/seats', showId); },
    checkout: function (holdId, showId) { return withShow('/booking/holds/' + encodeURIComponent(holdId) + '/checkout', showId); }
  };

  function query(name) {
    return new URLSearchParams(location.search).get(name);
  }

  // 같은 사이트 안의 경로만 돌려준다. 아니면 null. //나 /\로 시작하는 주소는 URL이 다른 사이트로 읽는다
  function safePath(raw) {
    if (!raw || raw.charAt(0) !== '/') return null;
    try {
      var url = new URL(raw, location.origin);
      return url.origin === location.origin ? url.pathname + url.search + url.hash : null;
    } catch (e) {
      return null;
    }
  }

  function positiveId(raw) {
    return /^[1-9]\d{0,17}$/.test(raw || '') ? raw : null;
  }

  window.bk = {
    WORDS: WORDS,
    NETWORK_MESSAGE: NETWORK_MESSAGE,
    isNetworkError: isNetworkError,
    get: function (path) { return request('GET', path); },
    post: function (path, body, options) { return request('POST', path, body, options); },
    del: function (path) { return request('DELETE', path); },
    poller: poller,
    dateTime: dateTime,
    shortDateTime: shortDateTime,
    epochOf: epochOf,
    clockText: clockText,
    won: won,
    count: count,
    seat: seat,
    bySeat: bySeat,
    seatName: seatName,
    seatPrice: seatPrice,
    seatText: seatText,
    gradeGroups: gradeGroups,
    gradeText: gradeText,
    session: storage('session'),
    local: storage('local'),
    keys: keys,
    links: links,
    query: query,
    safePath: safePath,
    positiveId: positiveId
  };
})();
