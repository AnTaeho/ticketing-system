-- KEYS[1] = processing, KEYS[2] = 사용자 키, ARGV[1] = member
-- 사용자 키는 값이 이 토큰일 때만 지운다. 그 사이 새로 선 토큰을 지우지 않으려고
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
return removed
