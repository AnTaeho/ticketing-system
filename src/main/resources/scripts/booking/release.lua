-- KEYS: 지울 키들
-- ARGV: [1] 기대 값(값이 이것과 같을 때만 지운다. 범용 compare-and-delete)
-- 반환: 지운 키 수
local n = 0
for _, k in ipairs(KEYS) do
  if redis.call('GET', k) == ARGV[1] then redis.call('DEL', k); n = n + 1 end
end
return n
