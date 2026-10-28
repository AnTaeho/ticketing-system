-- KEYS: userhold 키 + 좌석 키들
-- ARGV: [1] holdToken, [2] ttlMs
-- 반환: 값이 holdToken이라 연장한 키 수
local n = 0
for _, k in ipairs(KEYS) do
  if redis.call('GET', k) == ARGV[1] then redis.call('PEXPIRE', k, ARGV[2]); n = n + 1 end
end
return n
