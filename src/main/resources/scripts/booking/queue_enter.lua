-- KEYS: [1] processing, [2] waiting, [3] 사용자 키
-- ARGV: [1] 새 member, [2] now(ms), [3] processing 만료 시각(ms), [4] waiting 점수(진입 시각 ms), [5] 정원, [6] 사용자 키 TTL(ms, waiting 토큰 수명과 같다)
-- 반환: {'PROCESSING' | 'WAITING', member}. 살아 있는 옛 토큰이 있으면 그 토큰과 상태
local now = tonumber(ARGV[2])
local old = redis.call('GET', KEYS[3])
if old then
  local p = redis.call('ZSCORE', KEYS[1], old)
  if p and tonumber(p) > now then return {'PROCESSING', old} end
  local w = redis.call('ZSCORE', KEYS[2], old)
  if w and tonumber(w) + tonumber(ARGV[6]) > now then return {'WAITING', old} end
  -- 만료된 옛 토큰은 정리 전이라도 자리를 비우고 새로 선다
  redis.call('ZREM', KEYS[1], old)
  redis.call('ZREM', KEYS[2], old)
end
local member = ARGV[1]
local status
if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[5]) and redis.call('ZCARD', KEYS[2]) == 0 then
  redis.call('ZADD', KEYS[1], ARGV[3], member)
  status = 'PROCESSING'
else
  -- 같은 ms에 들어와도 도착 순서대로 서도록 마지막 점수보다 크게 둔다
  local score = tonumber(ARGV[4])
  local last = redis.call('ZRANGE', KEYS[2], -1, -1, 'WITHSCORES')
  if last[2] and tonumber(last[2]) >= score then score = tonumber(last[2]) + 1 end
  redis.call('ZADD', KEYS[2], string.format('%.0f', score), member)
  status = 'WAITING'
end
redis.call('SET', KEYS[3], member, 'PX', ARGV[6])
return {status, member}
