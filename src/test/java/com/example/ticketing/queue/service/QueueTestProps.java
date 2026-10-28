package com.example.ticketing.queue.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Queue;

// 정원만 바꾼 설정. 속성을 바꾸면 테스트 컨텍스트가 늘어나므로 서비스·스케줄러를 new로 만들 때 쓴다
final class QueueTestProps {

    private QueueTestProps() {
    }

    static TicketingProperties withCapacity(TicketingProperties p, int capacity) {
        Queue q = p.queue();
        return new TicketingProperties(p.seed(), p.redis(), p.hold(), p.payment(), p.gate(),
                new Queue(capacity, q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteEnabled(), q.promoteIntervalMs(),
                        q.required()),
                p.seatmap(), p.outbox(), p.ops(), p.pg(), p.fakePg());
    }
}
