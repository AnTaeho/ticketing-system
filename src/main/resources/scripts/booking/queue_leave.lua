-- KEYS: [1] processing, [2] waiting, [3] leaving
-- ARGV: [1] queueToken, [2] now(ms), [3] 처리열에서 떠난 뒤 남길 시간(ms), [4] 대기열에서 떠난 뒤 남길 시간(ms), [5] waiting 토큰 수명(ms)
-- 반환: 1(처리열 점수를 줄였다) | 2(대기열 토큰에 떠난 시각 + 유예를 적었다) | 0(아무것도 하지 않았다)
-- 둘 다 바로 지우지 않는다. 새로고침이면 다시 열린 화면의 생존 신호나 상태 조회가 되돌린다
local now = tonumber(ARGV[2])
local p = redis.call('ZSCORE', KEYS[1], ARGV[1])
if p then
  if tonumber(p) <= now then return 0 end
  local expire_at = now + tonumber(ARGV[3])
  if expire_at >= tonumber(p) then return 0 end
  redis.call('ZADD', KEYS[1], 'XX', string.format('%.0f', expire_at), ARGV[1])
  return 1
end
local w = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not w or tonumber(w) + tonumber(ARGV[5]) <= now then return 0 end
-- 떠남 신호가 여러 번 와도 처음 떠난 시각을 기준으로 뺀다
redis.call('ZADD', KEYS[3], 'NX', string.format('%.0f', now + tonumber(ARGV[4])), ARGV[1])
return 2
