-- 부하 회차 하나의 정합성 집계. 앞에 SET @pid = <회차 ID>; 를 붙여 실행하고, 줄마다 "이름<TAB>값"을 낸다.
-- 판정(0이어야 하는 것 등)은 load-test/scripts/invariants.py가 한다.

-- 같은 좌석이 확정 예매 두 건 이상에 들어간 좌석 수
SELECT 'overbooked_seats', COUNT(*) FROM (
  SELECT bs.seat_id FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id
   WHERE b.status = 'CONFIRMED' AND b.performance_id = @pid
   GROUP BY bs.seat_id HAVING COUNT(*) > 1) t;

SELECT 'total_seats', COUNT(*) FROM seat WHERE performance_id = @pid;
SELECT 'sold_seats', COUNT(*) FROM seat WHERE performance_id = @pid AND status = 'SOLD';
SELECT 'held_seats', COUNT(*) FROM seat WHERE performance_id = @pid AND status = 'HELD';
SELECT 'available_seats', COUNT(*) FROM seat WHERE performance_id = @pid AND status = 'AVAILABLE';
-- 만료 시각이 지났는데 HELD로 남은 좌석. 만료 스케줄러 주기(1초) 안의 것도 센다
SELECT 'held_seats_past_expiry', COUNT(*) FROM seat
 WHERE performance_id = @pid AND status = 'HELD' AND (held_until IS NULL OR held_until < NOW(6));

SELECT 'confirmed_bookings', COUNT(*) FROM booking WHERE performance_id = @pid AND status = 'CONFIRMED';
SELECT 'confirmed_booking_seats', COUNT(*) FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id
 WHERE b.status = 'CONFIRMED' AND b.performance_id = @pid;

-- 결제가 아직 열린 상태(REQUESTED·UNKNOWN)
SELECT 'open_payments', COUNT(*) FROM booking_payment bp JOIN seat_hold h ON h.id = bp.hold_id
 WHERE h.performance_id = @pid AND bp.status IN ('REQUESTED', 'UNKNOWN');
SELECT CONCAT('payments_', bp.status), COUNT(*) FROM booking_payment bp JOIN seat_hold h ON h.id = bp.hold_id
 WHERE h.performance_id = @pid GROUP BY bp.status;

SELECT 'paying_holds', COUNT(*) FROM seat_hold WHERE performance_id = @pid AND status = 'PAYING';
SELECT 'refunding_holds', COUNT(*) FROM seat_hold WHERE performance_id = @pid AND status = 'REFUNDING';
SELECT CONCAT('holds_', status), COUNT(*) FROM seat_hold WHERE performance_id = @pid GROUP BY status;

-- 이 회차의 Outbox 작업(발권 알림은 예매, 환불은 결제를 가리킨다)
SELECT CONCAT('outbox_', o.task_type, '_', o.status), COUNT(*) FROM outbox_task o
 WHERE (o.task_type = 'TICKET_ISSUED'
        AND o.aggregate_id IN (SELECT b.id FROM booking b WHERE b.performance_id = @pid))
    OR (o.task_type = 'REFUND_REQUESTED'
        AND o.aggregate_id IN (SELECT bp.id FROM booking_payment bp JOIN seat_hold h ON h.id = bp.hold_id
                                WHERE h.performance_id = @pid))
 GROUP BY o.task_type, o.status;

-- 마지막 확정 예매 시각(epoch ms). 매진까지 걸린 시간을 재는 데 쓴다
SELECT 'first_booking_at_ms', COALESCE(ROUND(UNIX_TIMESTAMP(MIN(created_at)) * 1000), 'null')
  FROM booking WHERE performance_id = @pid AND status = 'CONFIRMED';
SELECT 'last_booking_at_ms', COALESCE(ROUND(UNIX_TIMESTAMP(MAX(created_at)) * 1000), 'null')
  FROM booking WHERE performance_id = @pid AND status = 'CONFIRMED';
