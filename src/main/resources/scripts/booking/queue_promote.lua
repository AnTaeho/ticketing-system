-- KEYS: [1] processing, [2] waiting, [3] admitted, [4] leaving
-- ARGV: [1] 승격 토큰의 만료 시각(ms), [2] 정원, [3] now(ms, 입장 시각)
-- 반환: waiting에서 processing으로 옮긴 수
local slots = tonumber(ARGV[2]) - redis.call('ZCARD', KEYS[1])
if slots <= 0 then return 0 end
local members = redis.call('ZRANGE', KEYS[2], 0, slots - 1)
for _, m in ipairs(members) do
  local expire_at = tonumber(ARGV[1])
  -- 떠남 유예 중에 차례가 오면 유예가 끝나는 시각까지만 자리를 준다. 돌아와 좌석 화면을 열면 생존 신호가 늘린다
  local l = redis.call('ZSCORE', KEYS[4], m)
  if l then
    redis.call('ZREM', KEYS[4], m)
    if tonumber(l) < expire_at then expire_at = tonumber(l) end
  end
  redis.call('ZREM', KEYS[2], m)
  redis.call('ZADD', KEYS[1], string.format('%.0f', expire_at), m)
  redis.call('ZADD', KEYS[3], ARGV[3], m)
end
return #members
