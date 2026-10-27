package com.example.ticketing.booking.queue;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.BookingProperties.Queue;

// 정원만 바꾼 설정. 속성을 바꾸면 테스트 컨텍스트가 늘어나므로 서비스·스케줄러를 new로 만들 때 쓴다
final class QueueTestProps {

    private QueueTestProps() {
    }

    static BookingProperties withCapacity(BookingProperties p, int capacity) {
        Queue q = p.queue();
        return new BookingProperties(p.seed(), p.redis(), p.hold(), p.payment(), p.gate(),
                new Queue(capacity, q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteIntervalMs(), q.required()),
                p.seatmap(), p.outbox(), p.ops(), p.pg(), p.fakePg());
    }
}
