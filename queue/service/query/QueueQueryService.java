package com.example.book_your_seat.queue.service.query;

import com.example.book_your_seat.queue.controller.dto.QueueResponse;
import com.example.book_your_seat.queue.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.example.book_your_seat.queue.QueueConst.*;

@Service
@RequiredArgsConstructor
public class QueueQueryService {
    public final QueueRedisRepository queueRedisRepository;

    /*
    유저의 현재 큐 상태 확인
     */
    public QueueResponse findQueueStatus(Long concertId, String token) {
        if (queueRedisRepository.isInProcessingQueue(concertId, token)) {
            return QueueResponse.processing();
        }

        Integer position = queueRedisRepository.getWaitingQueuePosition(concertId, token);
        if (position != null) {
            return QueueResponse.waiting(position, calculateEstimatedWaitSeconds(position));
        } else {
            return QueueResponse.notInQueue();
        }
    }

    private Integer calculateEstimatedWaitSeconds(int position) {
        int batchSize = PROCESSING_QUEUE_SIZE;
        int batchInterval = FIVE * MINUTE;
        int batches = position / batchSize;
        return batches * batchInterval;
    }
}
