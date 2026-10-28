-- KEYS: [1] processing, [2] 사용자 키, [3] admitted
-- ARGV: [1] queueToken(사용자 키는 값이 이 토큰일 때만 지워 그 사이 새로 선 토큰을 남긴다)
-- 반환: processing에서 지운 수(0 또는 1)
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
return removed
