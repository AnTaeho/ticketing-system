(function () {
  'use strict';

  var form = document.getElementById('login-form');
  var input = document.getElementById('nickname');
  var error = document.getElementById('login-error');
  var submit = document.getElementById('login-submit');

  function nextPath() {
    var next = bk.safePath(bk.query('next'));
    if (!next || next.indexOf('/booking/login') === 0) return '/booking/shows';
    return next;
  }

  function showError(message) {
    error.textContent = message;
    error.hidden = !message;
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
  }

  input.addEventListener('input', function () { if (!error.hidden) showError(''); });

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var nickname = input.value.trim();
    var length = Array.from(nickname).length;
    if (length < 2 || length > 20) {
      showError('닉네임은 2~20자로 써 주세요');
      input.focus();
      return;
    }
    showError('');
    submit.disabled = true;
    submit.textContent = '들어가는 중…';
    bk.post('/api/demo/login', { nickname: nickname }).then(function () {
      location.replace(nextPath());
    }, function (e) {
      submit.disabled = false;
      submit.textContent = '시작하기';
      showError(e.message);
      input.focus();
    });
  });

  input.focus();
})();
