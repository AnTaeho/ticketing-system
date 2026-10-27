-- KEYS[1] = userhold, KEYS[2..] = 좌석 키들, ARGV[1] = holdToken, ARGV[2] = gate_close_at(epoch ms)
-- 과거 시각이면 PEXPIREAT가 키를 바로 지운다. 반환: 처리한 좌석 키 수
if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
local n = 0
for i = 2, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then redis.call('PEXPIREAT', KEYS[i], ARGV[2]); n = n + 1 end
end
return n
