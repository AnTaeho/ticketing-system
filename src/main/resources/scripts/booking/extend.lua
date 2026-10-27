-- KEYS = userhold 키 + 좌석 키들, ARGV[1] = holdToken, ARGV[2] = ttlMs
-- 값이 토큰과 같은 키만 연장하고 연장한 수를 돌려준다
local n = 0
for _, k in ipairs(KEYS) do
  if redis.call('GET', k) == ARGV[1] then redis.call('PEXPIRE', k, ARGV[2]); n = n + 1 end
end
return n
