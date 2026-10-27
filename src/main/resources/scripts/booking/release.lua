-- KEYS = userhold 키(있으면) + 좌석 키들, ARGV[1] = holdToken
-- 남의 선점 키를 지우지 않도록 값이 토큰과 같은 키만 지운다
local n = 0
for _, k in ipairs(KEYS) do
  if redis.call('GET', k) == ARGV[1] then redis.call('DEL', k); n = n + 1 end
end
return n
