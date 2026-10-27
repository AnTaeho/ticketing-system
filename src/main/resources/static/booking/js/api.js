// 예매 화면 공통: API 호출, 에러 문구, 날짜·금액 형식, 저장소 접근
(function () {
  'use strict';

  var NETWORK_MESSAGE = '인터넷 연결이 불안정해요. 연결을 확인하고 다시 시도해 주세요';
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

  var clock = { offsetMs: 0, bestRttMs: Infinity };

  // 응답 Date 헤더로 내 시계와 서버 시계의 차이를 잰다. 왕복이 가장 짧았던 측정을 쓴다
  function noteServerTime(res, sentAt) {
    var header = res.headers.get('Date');
    if (!header) return;
    var receivedAt = Date.now();
    var rtt = receivedAt - sentAt;
    var server = Date.parse(header);
    if (isNaN(server) || rtt > clock.bestRttMs) return;
    clock.bestRttMs = rtt;
    // Date 헤더는 초 단위로 잘리므로 반 초를 더해 가운데로 맞춘다
    clock.offsetMs = server + 500 - (sentAt + rtt / 2);
  }

  function serverNow() {
    return Date.now() + clock.offsetMs;
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
    Object.keys(opts.headers || {}).forEach(function (name) { init.headers[name] = opts.headers[name]; });
    var timer = null;
    var timedOut = false;
    if (opts.timeoutMs && typeof AbortController === 'function') {
      var controller = new AbortController();
      init.signal = controller.signal;
      timer = setTimeout(function () { timedOut = true; controller.abort(); }, opts.timeoutMs);
    }
    var sentAt = Date.now();
    return fetch(path, init).then(function (res) {
      noteServerTime(res, sentAt);
      return readJson(res).then(function (data) {
        clearTimeout(timer);
        if (res.ok) return data;
        var code = (data && data.code) || ('HTTP_' + res.status);
        if (res.status === 401 && code === 'LOGIN_REQUIRED') {
          location.href = loginUrl();
          // 화면을 옮기는 중이므로 호출한 쪽이 에러를 그리지 않게 끝나지 않는 약속을 돌려준다
          return new Promise(function () {});
        }
        throw new ApiError(res.status, code, (data && data.message) || FALLBACK_MESSAGE, data && data.detail);
      });
    }, function () {
      clearTimeout(timer);
      throw new ApiError(0, timedOut ? 'TIMEOUT' : 'NETWORK', NETWORK_MESSAGE, {});
    });
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

  function storage(kind) {
    function area() { return kind === 'local' ? window.localStorage : window.sessionStorage; }
    return {
      get: function (key) { try { return area().getItem(key); } catch (e) { return null; } },
      set: function (key, value) { try { area().setItem(key, value); } catch (e) { /* 저장 못 해도 화면은 돈다 */ } },
      remove: function (key) { try { area().removeItem(key); } catch (e) { /* 저장 못 해도 화면은 돈다 */ } }
    };
  }

  function query(name) {
    try { return new URLSearchParams(location.search).get(name); } catch (e) { return null; }
  }

  // 같은 사이트 안의 경로만 돌려준다. 아니면 null
  function safePath(raw) {
    if (!raw || raw.charAt(0) !== '/' || raw.charAt(1) === '/' || raw.charAt(1) === '\\') return null;
    try {
      var url = new URL(raw, location.origin);
      if (url.origin !== location.origin) return null;
      return url.pathname + url.search + url.hash;
    } catch (e) {
      return null;
    }
  }

  function positiveId(raw) {
    return /^[1-9]\d{0,17}$/.test(raw || '') ? raw : null;
  }

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

  window.bk = {
    ApiError: ApiError,
    NETWORK_MESSAGE: NETWORK_MESSAGE,
    get: function (path) { return request('GET', path); },
    post: function (path, body, options) { return request('POST', path, body, options); },
    del: function (path) { return request('DELETE', path); },
    serverNow: serverNow,
    dateTime: dateTime,
    shortDateTime: shortDateTime,
    epochOf: epochOf,
    clockText: clockText,
    won: won,
    count: count,
    session: storage('session'),
    local: storage('local'),
    query: query,
    safePath: safePath,
    positiveId: positiveId,
    loginUrl: loginUrl,
    el: el,
    setHeader: setHeader
  };
})();
