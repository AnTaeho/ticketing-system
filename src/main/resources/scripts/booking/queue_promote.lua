-- KEYS: [1] processing, [2] waiting, [3] admitted
-- ARGV: [1] 승격 토큰의 만료 시각(ms), [2] 정원, [3] now(ms, 입장 시각)
-- 반환: waiting에서 processing으로 옮긴 수
local slots = tonumber(ARGV[2]) - redis.call('ZCARD', KEYS[1])
if slots <= 0 then return 0 end
local members = redis.call('ZRANGE', KEYS[2], 0, slots - 1)
for _, m in ipairs(members) do
  redis.call('ZREM', KEYS[2], m)
  redis.call('ZADD', KEYS[1], ARGV[1], m)
  redis.call('ZADD', KEYS[3], ARGV[3], m)
end
return #members
