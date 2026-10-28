-- KEYS: [1] processing
-- ARGV: [1] queueToken, [2] now(ms), [3] 떠난 뒤 남길 시간(ms)
-- 반환: 점수를 줄였으면 1, 처리열에 없거나 만료됐거나 이미 짧으면 0
-- 바로 지우지 않는다. 새로고침이면 다시 열린 화면의 생존 신호가 점수를 되돌린다
local now = tonumber(ARGV[2])
local p = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not p or tonumber(p) <= now then return 0 end
local expire_at = now + tonumber(ARGV[3])
if expire_at >= tonumber(p) then return 0 end
redis.call('ZADD', KEYS[1], 'XX', string.format('%.0f', expire_at), ARGV[1])
return 1
