package com.example.ticketing.waitingroom.service;

import com.example.ticketing.waitingroom.dto.QueueStatusResponse;
import com.example.ticketing.waitingroom.repository.QueueRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.example.ticketing.waitingroom.WaitingRoomConst.*;

@Service
@RequiredArgsConstructor
public class QueueQueryService {

    private static final int BATCH_INTERVAL_SECONDS = 5 * 60;

    private final QueueRedisRepository queueRepository;

    public QueueStatusResponse getQueueStatus(Long concertId, String token) {
        if (queueRepository.isInProcessingQueue(concertId, token)) {
            return QueueStatusResponse.processing();
        }

        Integer position = queueRepository.getWaitingPosition(concertId, token);
        if (position != null) {
            int estimatedWaitSeconds = calculateEstimatedWait(position);
            return QueueStatusResponse.waiting(position, estimatedWaitSeconds);
        }

        return QueueStatusResponse.notInQueue();
    }

    private int calculateEstimatedWait(int position) {
        int batches = position / PROCESSING_QUEUE_SIZE;
        return batches * BATCH_INTERVAL_SECONDS;
    }
}
