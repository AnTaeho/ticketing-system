-- KEYS: [1] 좌석맵 리스 키
-- ARGV: [1] 소유자, [2] TTL(ms)
-- 반환: 1(비어 있어 잡았거나 같은 소유자라 TTL만 늘렸다. 스케줄러가 매 주기 리스를 이어 가게) | 0(남이 쥐고 있다)
local cur = redis.call('GET', KEYS[1])
if not cur then
  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
  return 1
end
if cur == ARGV[1] then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return 1
end
return 0
