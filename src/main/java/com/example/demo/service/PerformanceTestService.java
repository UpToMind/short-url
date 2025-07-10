package com.example.demo.service;

import com.example.demo.entity.Url;
import com.example.demo.repository.UrlRepository;
import com.example.demo.util.SnowflakeIdGenerator;
import com.example.demo.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {
    
    private final UrlRepository urlRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Base62Encoder base62Encoder;
    private static final int BATCH_SIZE = 1000;
    
    // 테스트용 도메인들
    private static final String[] SAMPLE_DOMAINS = {
        "https://www.google.com",
        "https://www.naver.com", 
        "https://www.daum.net",
        "https://www.youtube.com",
        "https://www.facebook.com",
        "https://www.instagram.com",
        "https://www.twitter.com",
        "https://www.linkedin.com",
        "https://www.github.com",
        "https://www.stackoverflow.com"
    };
    
    /**
     * 1000만 건 대량 데이터 삽입 (병렬 처리) - Snowflake ID 사용
     */
    @Transactional
    public void insertBulkTestData(int totalCount) {
        log.info("🚀 Snowflake ID 기반 병렬 대량 테스트 데이터 삽입 시작 - 총 {}개", totalCount);
        long startTime = System.currentTimeMillis();
        
        // CPU 코어 수의 2배만큼 스레드 풀 생성 (I/O 바운드 작업에 적합)
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, 20);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // 중복 방지를 위한 Thread-Safe Set (Snowflake ID 사용으로 중복 가능성 극히 낮음)
        Set<String> usedShortCodes = ConcurrentHashMap.newKeySet();
        
        // 배치 단위로 데이터 삽입
        int batchCount = (totalCount + BATCH_SIZE - 1) / BATCH_SIZE;
        List<Future<Void>> futures = new ArrayList<>();
        
        // 진행률 추적을 위한 Atomic 변수들
        AtomicInteger completedBatches = new AtomicInteger(0);
        AtomicLong totalInserted = new AtomicLong(0);
        
        log.info("📊 Snowflake ID 병렬 처리 설정: {}개 스레드, {}개 배치로 분할", threadCount, batchCount);
        
        // 각 배치를 병렬로 처리
        for (int i = 0; i < batchCount; i++) {
            final int batchIndex = i;
            final int currentBatchSize = Math.min(BATCH_SIZE, totalCount - (i * BATCH_SIZE));
            
            Future<Void> future = executor.submit(() -> {
                try {
                    SecureRandom random = new SecureRandom();
                    List<Url> urls = new ArrayList<>();
                    
                    for (int j = 0; j < currentBatchSize; j++) {
                        // Snowflake ID 생성
                        long snowflakeId = snowflakeIdGenerator.nextId();
                        
                        // Base62 인코딩으로 shortCode 생성
                        String shortCode = base62Encoder.generateShortCode(snowflakeId);
                        
                        // 중복 체크 (Snowflake ID 사용으로 매우 낮은 확률)
                        while (usedShortCodes.contains(shortCode)) {
                            log.warn("⚠️ shortCode 중복 발생 (매우 드문 경우): {}", shortCode);
                            snowflakeId = snowflakeIdGenerator.nextId();
                            shortCode = base62Encoder.generateShortCode(snowflakeId);
                        }
                        usedShortCodes.add(shortCode);
                        
                        // 테스트용 랜덤 URL 생성
                        String originalUrl = generateTestUrl(random, (batchIndex * BATCH_SIZE) + j);
                        
                        Url url = new Url();
                        url.setId(snowflakeId);  // Snowflake ID를 직접 Primary Key로 설정
                        url.setOriginalUrl(originalUrl);
                        url.setShortCode(shortCode);
                        url.setCreatedAt(LocalDateTime.now());
                        url.setClickCount(0L);
                        
                        urls.add(url);
                    }
                    
                    // 배치 저장
                    urlRepository.saveAll(urls);
                    
                    // 진행률 업데이트
                    int completed = completedBatches.incrementAndGet();
                    long inserted = totalInserted.addAndGet(currentBatchSize);
                    
                    // 100배치마다 진행률 표시
                    if (completed % 100 == 0) {
                        long currentTime = System.currentTimeMillis();
                        double elapsedSeconds = (currentTime - startTime) / 1000.0;
                        double rate = inserted / elapsedSeconds;
                        
                        log.info("📊 Snowflake ID 병렬 진행률: %d/%d 배치 완료 (%.1f%%), 삽입 속도: %.0f records/sec", 
                            completed, batchCount, ((double)completed / batchCount) * 100, rate);
                    }
                    
                    // 메모리 관리를 위해 주기적으로 Set 클리어
                    if (usedShortCodes.size() > 200000) {
                        synchronized (usedShortCodes) {
                            if (usedShortCodes.size() > 200000) {
                                usedShortCodes.clear();
                                log.debug("🧹 메모리 관리: shortCode Set 클리어");
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("배치 {}번 처리 중 오류 발생: {}", batchIndex, e.getMessage());
                    throw new RuntimeException(e);
                }
                
                return null;
            });
            
            futures.add(future);
        }
        
        // 모든 배치 완료 대기
        log.info("⏳ 모든 Snowflake ID 배치 작업 완료 대기 중...");
        for (Future<Void> future : futures) {
            try {
                future.get(); // 각 배치 완료까지 대기
            } catch (Exception e) {
                log.error("배치 처리 중 오류 발생: {}", e.getMessage());
            }
        }
        
        // 스레드 풀 종료
        executor.shutdown();
        
        long endTime = System.currentTimeMillis();
        double totalTimeSeconds = (endTime - startTime) / 1000.0;
        double averageRate = totalCount / totalTimeSeconds;
        
        log.info("🎉 Snowflake ID 기반 병렬 대량 데이터 삽입 완료!");
        log.info(String.format("📈 총 소요시간: %.2f초", totalTimeSeconds));
        log.info(String.format("📈 평균 삽입 속도: %.0f records/sec", averageRate));
        log.info("📈 사용된 스레드 수: {}", threadCount);
        log.info("📈 총 삽입 레코드: {}", totalCount);
    }
    
    /**
     * 테스트용 랜덤 Original URL 생성
     */
    private String generateTestUrl(SecureRandom random, int index) {
        String domain = SAMPLE_DOMAINS[random.nextInt(SAMPLE_DOMAINS.length)];
        String path = "/test-page-" + index;
        String query = "?id=" + random.nextInt(100000) + "&type=test";
        return domain + path + query;
    }
    
    /**
     * 단일 조회 성능 테스트 (ID 기반)
     */
    @Transactional(readOnly = true)
    public void performSingleQueryTest(int testCount) {
        log.info("🔍 단일 조회 성능 테스트 시작 - {}회 실행", testCount);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return;
        }
        
        log.info("📊 메모리 효율적인 랜덤 조회 테스트 실행");
        log.info("📊 총 데이터 수: {}개", totalRecords);
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int successCount = 0;
        
        for (int i = 0; i < testCount; i++) {
            try {
                // 랜덤 오프셋으로 단일 레코드 조회
                int randomOffset = random.nextInt((int) totalRecords);
                
                long startTime = System.nanoTime();
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                long endTime = System.nanoTime();
                
                if (!randomUrls.isEmpty()) {
                    totalTime += (endTime - startTime);
                    successCount++;
                } else {
                    log.warn("랜덤 조회 실패: offset={}", randomOffset);
                }
                
            } catch (Exception e) {
                log.warn("단일 조회 실패: {}", e.getMessage());
            }
        }
        
        if (successCount > 0) {
            double averageTimeMs = (totalTime / successCount) / 1_000_000.0;
            log.info("📊 단일 조회 성능 결과:");
            log.info("  - 총 데이터 수: {}", totalRecords);
            log.info("  - 테스트 횟수: {}", testCount);
            log.info("  - 성공 횟수: {}", successCount);
            log.info(String.format("  - 평균 조회 시간: %.3fms", averageTimeMs));
            log.info(String.format("  - 초당 처리 가능: %.0f queries/sec", 1000.0 / averageTimeMs));
        } else {
            log.warn("⚠️ 성공한 조회가 없습니다.");
        }
    }
    
    /**
     * shortCode 조회 성능 테스트
     */
    @Transactional(readOnly = true)
    public void performShortCodeQueryTest(int testCount) {
        log.info("🔍 shortCode 조회 성능 테스트 시작 - {}회 실행", testCount);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return;
        }
        
        log.info("📊 메모리 효율적인 랜덤 shortCode 조회 테스트 실행");
        log.info("📊 총 데이터 수: {}개", totalRecords);
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int successCount = 0;
        
        for (int i = 0; i < testCount; i++) {
            try {
                // 랜덤 오프셋으로 단일 레코드 조회하여 shortCode 추출
                int randomOffset = random.nextInt((int) totalRecords);
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                
                if (!randomUrls.isEmpty()) {
                    String shortCode = randomUrls.get(0).getShortCode();
                    
                    // 실제 shortCode 조회 성능 측정
                    long startTime = System.nanoTime();
                    Optional<Url> result = urlRepository.findByShortCode(shortCode);
                    long endTime = System.nanoTime();
                    
                    if (result.isPresent()) {
                        totalTime += (endTime - startTime);
                        successCount++;
                    } else {
                        log.warn("shortCode 조회 실패: {}", shortCode);
                    }
                } else {
                    log.warn("랜덤 레코드 조회 실패: offset={}", randomOffset);
                }
                
            } catch (Exception e) {
                log.warn("shortCode 조회 실패: {}", e.getMessage());
            }
        }
        
        if (successCount > 0) {
            double averageTimeMs = (totalTime / successCount) / 1_000_000.0;
            log.info("📊 shortCode 조회 성능 결과:");
            log.info("  - 총 데이터 수: {}", totalRecords);
            log.info("  - 테스트 횟수: {}", testCount);
            log.info("  - 성공 횟수: {}", successCount);
            log.info(String.format("  - 평균 조회 시간: %.3fms", averageTimeMs));
            log.info(String.format("  - 초당 처리 가능: %.0f queries/sec", 1000.0 / averageTimeMs));
        } else {
            log.warn("⚠️ 성공한 조회가 없습니다.");
        }
    }
    
    /**
     * 배치 조회 성능 테스트
     */
    @Transactional(readOnly = true)
    public void performBatchQueryTest(int batchSize, int batchCount) {
        log.info("🔍 배치 조회 성능 테스트 시작 - {} 배치 x {} 크기", batchCount, batchSize);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return;
        }
        
        log.info("📊 메모리 효율적인 랜덤 배치 조회 테스트 실행");
        log.info("📊 총 데이터 수: {}개", totalRecords);
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int totalQueries = 0;
        
        for (int batch = 0; batch < batchCount; batch++) {
            List<Long> batchIds = new ArrayList<>();
            
            // 랜덤 오프셋으로 배치 크기만큼 ID 수집
            for (int i = 0; i < batchSize; i++) {
                int randomOffset = random.nextInt((int) totalRecords);
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                
                if (!randomUrls.isEmpty()) {
                    batchIds.add(randomUrls.get(0).getId());
                }
            }
            
            if (!batchIds.isEmpty()) {
                long startTime = System.nanoTime();
                List<Url> results = urlRepository.findAllById(batchIds);
                long endTime = System.nanoTime();
                
                totalTime += (endTime - startTime);
                totalQueries += results.size(); // 실제 조회된 레코드 수
            }
        }
        
        double totalTimeMs = totalTime / 1_000_000.0;
        double averageTimeMs = totalTimeMs / batchCount;
        double queriesPerSecond = (totalQueries * 1000.0) / totalTimeMs;
        
        log.info("📊 배치 조회 성능 결과:");
        log.info("  - 총 데이터 수: {}", totalRecords);
        log.info("  - 배치 수: {}", batchCount);
        log.info("  - 배치 크기: {}", batchSize);
        log.info("  - 실제 조회된 레코드 수: {}", totalQueries);
        log.info(String.format("  - 총 소요시간: %.2fms", totalTimeMs));
        log.info(String.format("  - 배치당 평균 시간: %.3fms", averageTimeMs));
        log.info(String.format("  - 초당 처리 가능: %.0f queries/sec", queriesPerSecond));
    }
    
    /**
     * 데이터베이스 상태 조회
     */
    @Transactional(readOnly = true)
    public void showDatabaseStatus() {
        log.info("📊 === 데이터베이스 상태 조회 ===");
        
        long totalCount = urlRepository.count();
        log.info("📊 총 URL 레코드 수: {}", totalCount);
        
        if (totalCount > 0) {
            // 최근 생성된 URL 조회
            log.info("📊 최근 생성된 URL 샘플:");
            List<Url> recentUrls = urlRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(3)
                .toList();
            
            recentUrls.forEach(url -> {
                log.info("  - shortCode: {}, Snowflake ID: {}, 클릭 수: {}", 
                        url.getShortCode(), url.getId(), url.getClickCount());
            });
        }
    }
    
    /**
     * 전체 데이터 삭제 (테스트용)
     */
    @Transactional
    public void clearAllData() {
        log.info("🗑️ 전체 데이터 삭제 시작...");
        long startTime = System.currentTimeMillis();
        
        long totalCount = urlRepository.count();
        urlRepository.deleteAll();
        
        long endTime = System.currentTimeMillis();
        log.info("🗑️ 전체 데이터 삭제 완료 - {}개 레코드 삭제, 소요시간: {}ms", 
                totalCount, endTime - startTime);
    }
    
    /**
     * 중복된 original URL 찾기 및 통계
     */
    @Transactional(readOnly = true)
    public void findDuplicateUrls() {
        log.info("🔍 중복 original URL 검사 시작...");
        long startTime = System.currentTimeMillis();
        
        // 중복된 original URL 조회
        List<Object[]> duplicates = urlRepository.findDuplicateOriginalUrls();
        
        long endTime = System.currentTimeMillis();
        
        log.info("📊 중복 original URL 검사 결과:");
        log.info("  - 중복된 URL 그룹 수: {}", duplicates.size());
        log.info(String.format("  - 검사 소요시간: %dms", endTime - startTime));
        
        if (!duplicates.isEmpty()) {
            log.warn("⚠️ 중복된 original URL이 발견되었습니다!");
            duplicates.forEach(row -> {
                String originalUrl = (String) row[0];
                Long count = (Long) row[1];
                log.warn("    * URL '{}': {}개 중복", originalUrl, count);
            });
        } else {
            log.info("✅ original URL 중복 없음");
        }
    }
    
    /**
     * 중복된 shortCode 찾기 및 통계 (데이터 무결성 검사)
     */
    @Transactional(readOnly = true)
    public void findDuplicateShortCodes() {
        log.info("🔍 중복 shortCode 검사 시작...");
        long startTime = System.currentTimeMillis();
        
        // 중복된 shortCode 조회
        List<Object[]> duplicates = urlRepository.findDuplicateShortCodes();
        
        long endTime = System.currentTimeMillis();
        
        log.info("📊 중복 shortCode 검사 결과:");
        log.info("  - 중복된 shortCode 그룹 수: {}", duplicates.size());
        log.info(String.format("  - 검사 소요시간: %dms", endTime - startTime));
        
        if (!duplicates.isEmpty()) {
            log.warn("⚠️ 중복된 shortCode가 발견되었습니다! (데이터 무결성 문제)");
            duplicates.forEach(row -> {
                String shortCode = (String) row[0];
                Long count = (Long) row[1];
                log.warn("    * shortCode '{}': {}개 중복", shortCode, count);
            });
        } else {
            log.info("✅ shortCode 중복 없음 - 데이터 무결성 양호");
        }
    }
    
    /**
     * 중복된 Snowflake ID 찾기 및 통계 (데이터 무결성 검사)
     */
    @Transactional(readOnly = true)
    public void findDuplicateSnowflakeIds() {
        log.info("🔍 중복 Snowflake ID 검사 시작...");
        long startTime = System.currentTimeMillis();
        
        // 중복된 ID 조회 (Snowflake ID가 Primary Key)
        List<Object[]> duplicates = urlRepository.findDuplicateIds();
        
        long endTime = System.currentTimeMillis();
        
        log.info("📊 중복 Snowflake ID 검사 결과:");
        log.info("  - 중복된 Snowflake ID 그룹 수: {}", duplicates.size());
        log.info(String.format("  - 검사 소요시간: %dms", endTime - startTime));
        
        if (!duplicates.isEmpty()) {
            log.warn("⚠️ 중복된 Snowflake ID가 발견되었습니다! (심각한 데이터 무결성 문제)");
            duplicates.forEach(row -> {
                Long snowflakeId = (Long) row[0];
                Long count = (Long) row[1];
                log.warn("    * Snowflake ID '{}': {}개 중복", snowflakeId, count);
            });
        } else {
            log.info("✅ Snowflake ID 중복 없음 - 데이터 무결성 양호");
        }
    }
    
    /**
     * 전체 중복 검사 실행
     */
    public void runDuplicateAnalysis() {
        log.info("🚀 === 전체 중복 검사 시작 ===");
        
        // 1. 데이터베이스 현황 확인
        showDatabaseStatus();
        
        // 2. 중복 original URL 검사
        findDuplicateUrls();
        
        // 3. 중복 shortCode 검사 (데이터 무결성)
        findDuplicateShortCodes();
        
        // 4. 중복 Snowflake ID 검사 (데이터 무결성)
        findDuplicateSnowflakeIds();
        
        log.info("🚀 === 전체 중복 검사 완료 ===");
    }
    
    /**
     * 전체 성능 테스트 시나리오 실행
     */
    public void runFullPerformanceTest() {
        log.info("🚀 === 전체 성능 테스트 시나리오 시작 ===");
        
        try {
            // 1. 데이터베이스 현황 확인
            log.info("1️⃣ 데이터베이스 현황 확인");
            showDatabaseStatus();
            
            // 2. 단일 조회 성능 테스트
            log.info("2️⃣ 단일 조회 성능 테스트 (1000회)");
            performSingleQueryTest(1000);
            
            // 3. shortCode 조회 성능 테스트
            log.info("3️⃣ shortCode 조회 성능 테스트 (1000회)");
            performShortCodeQueryTest(1000);
            
            // 4. 배치 조회 성능 테스트 (소규모)
            log.info("4️⃣ 배치 조회 성능 테스트 (100개 x 10배치)");
            performBatchQueryTest(100, 10);
            
            // 5. 배치 조회 성능 테스트 (대규모)
            log.info("5️⃣ 배치 조회 성능 테스트 (1000개 x 5배치)");
            performBatchQueryTest(1000, 5);
            
            log.info("🎉 === 전체 성능 테스트 시나리오 완료 ===");
            
        } catch (Exception e) {
            log.error("전체 성능 테스트 중 오류 발생: {}", e.getMessage());
            throw new RuntimeException("전체 성능 테스트 실패", e);
        }
    }
} 