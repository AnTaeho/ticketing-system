-- KEYS: [1] userhold, [2..n+1] 좌석 키(정렬), [n+2..2n+1] 쿨다운 키(같은 순서)
-- ARGV: [1] holdToken, [2] ttlMs (좌석 수 n은 KEYS 길이에서 구한다)
-- 반환: {'OK'} | {'USER_HAS_HOLD'} | {'SEAT_TAKEN', 좌석 키} | {'COOLDOWN', 쿨다운 키}
local n = (#KEYS - 1) / 2
if redis.call('EXISTS', KEYS[1]) == 1 then return {'USER_HAS_HOLD'} end
for i = 2, n + 1 do
  if redis.call('EXISTS', KEYS[i]) == 1 then return {'SEAT_TAKEN', KEYS[i]} end
end
for i = n + 2, 2 * n + 1 do
  if redis.call('EXISTS', KEYS[i]) == 1 then return {'COOLDOWN', KEYS[i]} end
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
for i = 2, n + 1 do redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2]) end
return {'OK'}
