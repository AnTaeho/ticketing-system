package com.example.book_your_seat.queue.service.command;

import com.example.book_your_seat.queue.controller.dto.TokenResponse;
import com.example.book_your_seat.queue.repository.QueueRedisRepository;
import com.example.book_your_seat.queue.util.QueueJwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

import static com.example.book_your_seat.queue.QueueConst.PROCESSING_QUEUE_SIZE;
import static com.example.book_your_seat.queue.QueueConst.REMOVE_BAD_REQUEST;

@Service
@RequiredArgsConstructor
public class QueueCommandService {

    private final QueueRedisRepository queueRedisRepository;
    public final QueueJwtUtil queueJwtUtil;

    /*
    토큰을 발급하고, Waiting Queue에 등록
     */
    public TokenResponse issueTokenAndEnqueue(Long userId, Long concertId) {
        String token = queueJwtUtil.createJwt(userId);

        //waiting queue가 비어있고, processing queue가 다 차지 않았으면 바로 processing queue에 넣어주기
        if (queueRedisRepository.isProcessableNow(concertId)) {
            queueRedisRepository.enqueueProcessingQueue(concertId, token);
        } else {
            queueRedisRepository.enqueueWaitingQueue(concertId, token);
        }

        return new TokenResponse(token);
    }

    /*
    Waiting Queue -> Processing Queue 로 이동시키기
     */
    public void updateWaitingToProcessing() {
        Set<String> keys = queueRedisRepository.findConcertHavingWaiting();
        keys.forEach(key -> {
            String concertId = key.split(":")[1];
            int count = calculateAvailableProcessingCount(concertId);
            if (count == 0) return;

            List<String> tokens = queueRedisRepository.getFrontTokensFromWaitingQueue(concertId, count);
            tokens.forEach(token -> {
                queueRedisRepository.updateWaitingToProcessing(concertId, token);
            });
        });
    }

    /*
    스케줄러 실행 시 만료토큰 제거
     */
    public void removeExpiredToken() {
        queueRedisRepository.removeExpiredToken(System.currentTimeMillis());
    }

    /*
    대기열에서 토큰 삭제
     */
    public void removeTokenInWaitingQueue(Long userId, Long concertId, String token) {
        Long tokenUserId = queueJwtUtil.getUserIdByToken(token);
        if (userId.equals(tokenUserId))
            queueRedisRepository.removeTokenInWaitingQueue(concertId, token);
        else throw new IllegalArgumentException(REMOVE_BAD_REQUEST);
    }

    /*
    처리열에서 토큰 삭제 (API 완료 시, 예외 발생 시)
   */
    public void removeTokenInProcessingQueue(Long userId, Long concertId, String token) {
        Long tokenUserId = queueJwtUtil.getUserIdByToken(token);
        if (userId.equals(tokenUserId))
            queueRedisRepository.removeTokenInProcessingQueue(concertId, token);
        else throw new IllegalArgumentException(REMOVE_BAD_REQUEST);
    }

    /*
    update 가능한 queue Size 계산
     */
    private int calculateAvailableProcessingCount(String concertId) {
        int size = queueRedisRepository.getProcessingQueueCount(concertId);
        return PROCESSING_QUEUE_SIZE - size;
    }
}
