-- KEYS: 좌석 키(정렬)
-- ARGV: [1] holdToken, [2] ttlMs
-- 반환: {'OK'} | {'SEAT_TAKEN', 좌석 키}
for i = 1, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then return {'SEAT_TAKEN', KEYS[i]} end
end
for i = 1, #KEYS do redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2]) end
return {'OK'}
