-- KEYS: [1] processing, [2] waiting, [3] admitted, [4] leaving
-- ARGV: [1] now(ms), [2] waiting 토큰 수명(ms), [3] 최대 세션 길이(ms)
-- 반환: processing과 waiting에서 지운 수의 합
local now = tonumber(ARGV[1])
local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
for _, m in ipairs(expired) do
  redis.call('ZREM', KEYS[3], m)
end
local processing = redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
-- 최대 세션이 지난 입장 시각은 살아 있는 토큰의 것일 수 없으므로 처리열에서 짝을 잃은 항목까지 함께 치운다
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', string.format('%.0f', now - tonumber(ARGV[3])))
local waiting = redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', string.format('%.0f', now - tonumber(ARGV[2])))
-- 떠난 뒤 유예 안에 돌아오지 않은 대기자를 뺀다. 남은 사용자 키는 다음 진입이 두 열에 없는 옛 토큰으로 보고 새로 세운다
local left = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', ARGV[1])
for _, m in ipairs(left) do
  waiting = waiting + redis.call('ZREM', KEYS[2], m)
end
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', ARGV[1])
return processing + waiting
