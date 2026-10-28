-- KEYS: [1] processing, [2] admitted
-- ARGV: [1] queueToken, [2] now(ms), [3] 무응답 한도(ms), [4] 최대 세션 길이(ms)
-- 반환: 갱신한 뒤 남은 시간(ms). 만료됐거나 처리열에 없으면 0
local now = tonumber(ARGV[2])
local p = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not p or tonumber(p) <= now then return 0 end
local a = redis.call('ZSCORE', KEYS[2], ARGV[1])
-- 입장 시각이 없는 토큰(입장 시각을 적기 전에 들어온 토큰)은 늘리지 않고 남은 시간만 돌려준다
if not a then return tonumber(p) - now end
local deadline = tonumber(a) + tonumber(ARGV[4])
if deadline <= now then return 0 end
local expire_at = math.min(now + tonumber(ARGV[3]), deadline)
redis.call('ZADD', KEYS[1], string.format('%.0f', expire_at), ARGV[1])
return expire_at - now
