package com.example.demo.util;

import org.springframework.stereotype.Component;

/**
 * Snowflake ID 생성기
 * 
 * 64비트 구조:
 * - 1비트: 부호 (항상 0)
 * - 41비트: 타임스탬프 (밀리초, 2010-01-01 00:00:00 UTC 기준)
 * - 10비트: 머신 ID (데이터센터 ID 5비트 + 워커 ID 5비트)
 * - 12비트: 시퀀스 번호 (같은 밀리초 내에서 생성되는 ID 구분)
 * 
 * 이론적으로 초당 409만 개의 고유 ID 생성 가능
 */
@Component
public class SnowflakeIdGenerator {
    
    // Twitter Snowflake 기준 시작 시간 (2010-01-01 00:00:00 UTC)
    private static final long EPOCH = 1288834974657L;
    
    // 각 필드의 비트 수
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    
    // 각 필드의 최대값
    private static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = -1L ^ (-1L << DATACENTER_ID_BITS);
    private static final long MAX_SEQUENCE = -1L ^ (-1L << SEQUENCE_BITS);
    
    // 각 필드의 시프트 값
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    
    // 인스턴스 설정
    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    
    /**
     * 기본 생성자 (워커 ID와 데이터센터 ID를 자동으로 설정)
     */
    public SnowflakeIdGenerator() {
        // 간단한 방법으로 워커 ID와 데이터센터 ID 설정
        // 실제 운영 환경에서는 설정 파일이나 환경 변수로 관리
        this.workerId = getWorkerId();
        this.datacenterId = getDatacenterId();
    }
    
    /**
     * 사용자 정의 생성자
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(String.format("Worker ID는 0과 %d 사이여야 합니다", MAX_WORKER_ID));
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(String.format("Datacenter ID는 0과 %d 사이여야 합니다", MAX_DATACENTER_ID));
        }
        
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }
    
    /**
     * 고유한 Snowflake ID 생성
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        
        // 시계가 뒤로 돌아간 경우 예외 처리
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(String.format("시계가 뒤로 돌아갔습니다. %d 밀리초 후 다시 시도하세요", lastTimestamp - timestamp));
        }
        
        // 같은 밀리초 내에서 생성되는 경우
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 시퀀스 번호가 최대값을 초과하면 다음 밀리초까지 대기
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 새로운 밀리초이면 시퀀스 번호 초기화
            sequence = 0L;
        }
        
        lastTimestamp = timestamp;
        
        // 각 필드를 조합하여 최종 ID 생성
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT) |
               (datacenterId << DATACENTER_ID_SHIFT) |
               (workerId << WORKER_ID_SHIFT) |
               sequence;
    }
    
    /**
     * 다음 밀리초까지 대기
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
    
    /**
     * 워커 ID 자동 생성 (MAC 주소 기반)
     */
    private long getWorkerId() {
        try {
            java.net.NetworkInterface network = java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getLocalHost());
            if (network == null) {
                return 1L;
            }
            byte[] mac = network.getHardwareAddress();
            if (mac == null) {
                return 1L;
            }
            
            long id = ((0x000000FF & (long) mac[mac.length - 1]) |
                      (0x0000FF00 & (((long) mac[mac.length - 2]) << 8))) >> 6;
            return id % (MAX_WORKER_ID + 1);
        } catch (Exception e) {
            return 1L;
        }
    }
    
    /**
     * 데이터센터 ID 자동 생성 (IP 주소 기반)
     */
    private long getDatacenterId() {
        try {
            java.net.InetAddress ip = java.net.InetAddress.getLocalHost();
            byte[] ipBytes = ip.getAddress();
            long id = (0x000000FF & (long) ipBytes[ipBytes.length - 1]) |
                     (0x0000FF00 & (((long) ipBytes[ipBytes.length - 2]) << 8));
            return (id >> 6) % (MAX_DATACENTER_ID + 1);
        } catch (Exception e) {
            return 1L;
        }
    }
    
    /**
     * ID를 파싱하여 각 구성 요소 정보 반환
     */
    public IdInfo parseId(long id) {
        long timestamp = ((id >> TIMESTAMP_SHIFT) & ((1L << 41) - 1)) + EPOCH;
        long datacenterId = (id >> DATACENTER_ID_SHIFT) & ((1L << DATACENTER_ID_BITS) - 1);
        long workerId = (id >> WORKER_ID_SHIFT) & ((1L << WORKER_ID_BITS) - 1);
        long sequence = id & ((1L << SEQUENCE_BITS) - 1);
        
        return new IdInfo(timestamp, datacenterId, workerId, sequence);
    }
    
    /**
     * ID 정보를 담는 클래스
     */
    public static class IdInfo {
        private final long timestamp;
        private final long datacenterId;
        private final long workerId;
        private final long sequence;
        
        public IdInfo(long timestamp, long datacenterId, long workerId, long sequence) {
            this.timestamp = timestamp;
            this.datacenterId = datacenterId;
            this.workerId = workerId;
            this.sequence = sequence;
        }
        
        public long getTimestamp() { return timestamp; }
        public long getDatacenterId() { return datacenterId; }
        public long getWorkerId() { return workerId; }
        public long getSequence() { return sequence; }
        
        @Override
        public String toString() {
            return String.format("IdInfo{timestamp=%d, datacenterId=%d, workerId=%d, sequence=%d}", 
                               timestamp, datacenterId, workerId, sequence);
        }
    }
} 