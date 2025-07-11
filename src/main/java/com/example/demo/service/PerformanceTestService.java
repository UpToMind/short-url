package com.example.demo.service;

import com.example.demo.entity.Url;
import com.example.demo.repository.UrlRepository;
import com.example.demo.util.SnowflakeIdGenerator;
import com.example.demo.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import java.util.concurrent.ThreadLocalRandom;
import com.example.demo.service.RedisUrlCacheService;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {
    
    private final UrlRepository urlRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Base62Encoder base62Encoder;
    private final RedisUrlCacheService redisUrlCacheService;
    private static final int BATCH_SIZE = 1000;
    
    // 숫자 포맷터 (천 단위 구분자 사용)
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.KOREA);
    
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
                    ThreadLocalRandom random = ThreadLocalRandom.current();
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
                        String originalUrl = generateTestUrl(ThreadLocalRandom.current(), (batchIndex * BATCH_SIZE) + j);
                        
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
    private String generateTestUrl(ThreadLocalRandom random, int index) {
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
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
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
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
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
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
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
    
    /**
     * 실제 리디렉션 응답 속도 측정 (End-to-End 성능)
     * Redis 비교를 위한 실제 서비스 시나리오 테스트
     */
    @Transactional(readOnly = true)
    public Map<String, Object> measureRedirectResponseTime(int testCount) {
        log.info("🔍 실제 리디렉션 응답 속도 측정 시작 - {}회 실행 (End-to-End)", testCount);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return Map.of("error", "no_data");
        }
        
        log.info("📊 실제 서비스 시나리오 응답 속도 측정 (DB 기반)");
        log.info("📊 총 데이터 수: {}", NUMBER_FORMAT.format(totalRecords));
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Long> responseTimes = new ArrayList<>();
        List<Long> dbQueryTimes = new ArrayList<>(); // 순수 DB 조회 시간
        List<Long> totalProcessTimes = new ArrayList<>(); // 전체 처리 시간
        
        int successCount = 0;
        int notFoundCount = 0;
        
        for (int i = 0; i < testCount; i++) {
            try {
                // 랜덤 오프셋으로 실제 shortCode 추출
                int randomOffset = random.nextInt((int) totalRecords);
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                
                if (!randomUrls.isEmpty()) {
                    String shortCode = randomUrls.get(0).getShortCode();
                    
                    // === 실제 서비스 로직 시뮬레이션 ===
                    long startTime = System.nanoTime();
                    
                    // 1. shortCode 유효성 검사 (실제 서비스에서 하는 작업)
                    if (shortCode == null || shortCode.length() != 7) {
                        continue; // 유효하지 않은 shortCode
                    }
                    
                    // 2. DB 조회 시간 측정
                    long dbStartTime = System.nanoTime();
                    Optional<Url> result = urlRepository.findByShortCode(shortCode);
                    long dbEndTime = System.nanoTime();
                    
                    // 3. 결과 처리 (실제 서비스에서 하는 작업)
                    if (result.isPresent()) {
                        Url url = result.get();
                        
                        // 클릭 수 증가 (실제 서비스 로직)
                        // url.setClickCount(url.getClickCount() + 1);
                        // urlRepository.save(url); // 실제로는 업데이트하지만 테스트에서는 제외
                        
                        // 리디렉션 URL 준비
                        String originalUrl = url.getOriginalUrl();
                        
                        successCount++;
                    } else {
                        notFoundCount++;
                    }
                    
                    long endTime = System.nanoTime();
                    
                    // 시간 기록
                    long totalTime = endTime - startTime;
                    long dbTime = dbEndTime - dbStartTime;
                    
                    responseTimes.add(totalTime);
                    dbQueryTimes.add(dbTime);
                    totalProcessTimes.add(totalTime);
                    
                } else {
                    log.warn("랜덤 레코드 조회 실패: offset={}", randomOffset);
                }
                
            } catch (Exception e) {
                log.warn("리디렉션 테스트 실패: {}", e.getMessage());
            }
        }
        
        if (successCount > 0) {
            // 통계 계산
            responseTimes.sort(Long::compareTo);
            dbQueryTimes.sort(Long::compareTo);
            
            double avgResponseNanos = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
            double avgDbNanos = dbQueryTimes.stream().mapToLong(Long::longValue).average().orElse(0);
            
            double avgResponseMs = avgResponseNanos / 1_000_000.0;
            double avgDbMs = avgDbNanos / 1_000_000.0;
            
            // 퍼센타일 계산
            long p50Response = responseTimes.get((int) (responseTimes.size() * 0.5));
            long p90Response = responseTimes.get((int) (responseTimes.size() * 0.9));
            long p95Response = responseTimes.get((int) (responseTimes.size() * 0.95));
            long p99Response = responseTimes.get((int) (responseTimes.size() * 0.99));
            
            long minResponse = responseTimes.get(0);
            long maxResponse = responseTimes.get(responseTimes.size() - 1);
            
            double qps = 1000.0 / avgResponseMs;
            
            // 결과 맵 생성
            Map<String, Object> results = new HashMap<>();
            results.put("test_type", "REDIRECT_END_TO_END");
            results.put("storage_type", "DATABASE_H2");
            results.put("total_records", totalRecords);
            results.put("test_count", testCount);
            results.put("success_count", successCount);
            results.put("not_found_count", notFoundCount);
            results.put("success_rate", (double) successCount / testCount * 100);
            
            // 전체 응답 시간 (End-to-End)
            results.put("avg_response_ms", avgResponseMs);
            results.put("p50_response_ms", p50Response / 1_000_000.0);
            results.put("p90_response_ms", p90Response / 1_000_000.0);
            results.put("p95_response_ms", p95Response / 1_000_000.0);
            results.put("p99_response_ms", p99Response / 1_000_000.0);
            results.put("min_response_ms", minResponse / 1_000_000.0);
            results.put("max_response_ms", maxResponse / 1_000_000.0);
            
            // 순수 DB 조회 시간
            results.put("avg_db_query_ms", avgDbMs);
            results.put("db_query_ratio", (avgDbMs / avgResponseMs) * 100); // DB 조회가 전체 시간에서 차지하는 비율
            
            results.put("qps", qps);
            
            log.info("📊 === 실제 리디렉션 응답 속도 결과 (End-to-End) ===");
            log.info("  🗄️ 저장소: H2 Database");
            log.info(String.format("  📈 총 데이터: %s", NUMBER_FORMAT.format(totalRecords)));
            log.info(String.format("  🎯 테스트 횟수: %s", NUMBER_FORMAT.format(testCount)));
            log.info(String.format("  ✅ 성공 횟수: %s", NUMBER_FORMAT.format(successCount)));
            log.info(String.format("  ❌ 실패 횟수: %s", NUMBER_FORMAT.format(notFoundCount)));
            log.info(String.format("  📊 성공률: %.1f%%", (double) successCount / testCount * 100));
            log.info(String.format("  ⚡ 평균 응답 (End-to-End): %.3fms", avgResponseMs));
            log.info(String.format("  🗄️ 평균 DB 조회: %.3fms", avgDbMs));
            log.info(String.format("  📊 DB 조회 비율: %.1f%%", (avgDbMs / avgResponseMs) * 100));
            log.info(String.format("  📊 P50: %.3fms", p50Response / 1_000_000.0));
            log.info(String.format("  📊 P90: %.3fms", p90Response / 1_000_000.0));
            log.info(String.format("  📊 P95: %.3fms", p95Response / 1_000_000.0));
            log.info(String.format("  📊 P99: %.3fms", p99Response / 1_000_000.0));
            log.info(String.format("  🔽 최소: %.3fms", minResponse / 1_000_000.0));
            log.info(String.format("  🔼 최대: %.3fms", maxResponse / 1_000_000.0));
            log.info(String.format("  🚀 처리량: %.0f QPS", qps));
            log.info("📊 === Redis 캐시 적용 후 비교 예정 ===");
            
            return results;
        } else {
            log.warn("⚠️ 성공한 조회가 없습니다.");
            return Map.of("error", "no_success");
        }
    }
    
    /**
     * Redis 캐시 성능 테스트 (DB와 비교)
     * 캐시 워밍업 후 성능 측정
     */
    @Transactional(readOnly = true)
    public Map<String, Object> measureRedisCachePerformance(int testCount) {
        log.info("🚀 Redis 캐시 성능 테스트 시작 - {}회 실행", testCount);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return Map.of("error", "no_data");
        }
        
        log.info("📊 Redis 캐시 성능 측정 (Cache vs DB 비교)");
        log.info("📊 총 데이터 수: {}", NUMBER_FORMAT.format(totalRecords));
        
        // 1. 캐시 워밍업 (테스트 데이터의 일부를 미리 캐시에 로드)
        log.info("🔥 캐시 워밍업 시작...");
        int warmupCount = Math.min(testCount / 2, 500); // 테스트 횟수의 절반 또는 최대 500개
        List<String> warmedUpShortCodes = warmupCacheAndReturnShortCodes(warmupCount);
        
        // 캐시 워밍업 후 상태 확인
        log.info("🔍 캐시 워밍업 후 상태 확인:");
        log.info("  - 워밍업된 shortCode 수: {}", warmedUpShortCodes.size());
        log.info("  - Redis 캐시 크기: {}", redisUrlCacheService.getCacheSize());
        
        // 워밍업된 shortCode 중 일부를 테스트해보기
        if (!warmedUpShortCodes.isEmpty()) {
            String testShortCode = warmedUpShortCodes.get(0);
            Optional<Url> testResult = redisUrlCacheService.getUrlByShortCode(testShortCode);
            log.info("  - 테스트 shortCode '{}' 캐시 조회 결과: {}", testShortCode, testResult.isPresent() ? "HIT" : "MISS");
        }
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // 캐시 성능 측정 변수들
        List<Long> cacheHitTimes = new ArrayList<>();
        List<Long> cacheMissTimes = new ArrayList<>();
        List<Long> dbOnlyTimes = new ArrayList<>();
        
        int cacheHitCount = 0;
        int cacheMissCount = 0;
        int dbOnlyCount = 0;
        
        // 디버깅을 위한 카운터
        int warmedUpShortCodeUsed = 0;
        int randomShortCodeUsed = 0;
        
        log.info("📊 성능 측정 시작 (Cache Hit/Miss 분석)");
        
        for (int i = 0; i < testCount; i++) {
            try {
                String shortCode;
                boolean isWarmedUp = false;
                
                // 50% 확률로 캐시된 shortCode 사용 (캐시 HIT 유도)
                if (i < testCount / 2 && !warmedUpShortCodes.isEmpty()) {
                    shortCode = warmedUpShortCodes.get(random.nextInt(warmedUpShortCodes.size()));
                    isWarmedUp = true;
                    warmedUpShortCodeUsed++;
                    
                    // 처음 5개 워밍업 shortCode 사용 로깅
                    if (warmedUpShortCodeUsed <= 5) {
                        log.info("🎯 워밍업 shortCode 사용 #{}: shortCode={}", warmedUpShortCodeUsed, shortCode);
                    }
                } else {
                    // 나머지는 랜덤 shortCode 사용 (캐시 MISS 유도)
                    int randomOffset = random.nextInt((int) totalRecords);
                    List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                    if (randomUrls.isEmpty()) continue;
                    shortCode = randomUrls.get(0).getShortCode();
                    randomShortCodeUsed++;
                    
                    // 처음 5개 랜덤 shortCode 사용 로깅
                    if (randomShortCodeUsed <= 5) {
                        log.info("🎲 랜덤 shortCode 사용 #{}: shortCode={}", randomShortCodeUsed, shortCode);
                    }
                }
                
                // === Redis 캐시 조회 테스트 ===
                long cacheStartTime = System.nanoTime();
                Optional<Url> cachedResult = redisUrlCacheService.getUrlByShortCode(shortCode);
                long cacheEndTime = System.nanoTime();
                
                if (cachedResult.isPresent()) {
                    // 캐시 HIT
                    cacheHitTimes.add(cacheEndTime - cacheStartTime);
                    cacheHitCount++;
                    
                    // 디버깅: 첫 10개 캐시 HIT 로그
                    if (cacheHitCount <= 10) {
                        log.info("🎯 캐시 HIT #{}: shortCode={}, isWarmedUp={}", cacheHitCount, shortCode, isWarmedUp);
                    }
                } else {
                    // 캐시 MISS - DB에서 조회하고 캐시에 저장
                    long dbStartTime = System.nanoTime();
                    Optional<Url> dbResult = urlRepository.findByShortCode(shortCode);
                    long dbEndTime = System.nanoTime();
                    
                    if (dbResult.isPresent()) {
                        // DB에서 조회 성공 - 캐시에 저장
                        redisUrlCacheService.cacheUrl(dbResult.get());
                        cacheMissTimes.add(dbEndTime - dbStartTime);
                        cacheMissCount++;
                        
                        // 디버깅: 첫 10개 캐시 MISS 로그
                        if (cacheMissCount <= 10) {
                            log.info("❌ 캐시 MISS #{}: shortCode={}, isWarmedUp={}", cacheMissCount, shortCode, isWarmedUp);
                        }
                    }
                }
                
                // === DB 직접 조회 테스트 (비교용) ===
                long dbOnlyStartTime = System.nanoTime();
                Optional<Url> dbOnlyResult = urlRepository.findByShortCode(shortCode);
                long dbOnlyEndTime = System.nanoTime();
                
                if (dbOnlyResult.isPresent()) {
                    dbOnlyTimes.add(dbOnlyEndTime - dbOnlyStartTime);
                    dbOnlyCount++;
                }
                
                // 중간 진행 상황 로그 (매 100회마다)
                if ((i + 1) % 100 == 0) {
                    log.debug("📊 진행 상황 {}/{}: HIT={}, MISS={}", i + 1, testCount, cacheHitCount, cacheMissCount);
                }
                
            } catch (Exception e) {
                log.warn("Redis 캐시 성능 테스트 실패: {}", e.getMessage());
            }
        }
        
        // 디버깅 정보 로그
        log.info("🔍 테스트 완료 후 통계:");
        log.info("  - 워밍업된 shortCode 사용 횟수: {}", warmedUpShortCodeUsed);
        log.info("  - 랜덤 shortCode 사용 횟수: {}", randomShortCodeUsed);
        log.info("  - 최종 캐시 크기: {}", redisUrlCacheService.getCacheSize());
        
        // 결과 계산
        Map<String, Object> results = calculateCachePerformanceResults(
            cacheHitTimes, cacheMissTimes, dbOnlyTimes,
            cacheHitCount, cacheMissCount, dbOnlyCount,
            totalRecords, testCount
        );
        
        // 결과 로깅
        logCachePerformanceResults(results);
        
        return results;
    }
    
    /**
     * 캐시 워밍업 - 테스트 데이터의 일부를 미리 캐시에 로드하고 shortCode 목록 반환
     */
    private List<String> warmupCacheAndReturnShortCodes(int warmupCount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long totalRecords = urlRepository.count();
        List<String> warmedUpShortCodes = new ArrayList<>();
        
        log.info("🔥 캐시 워밍업 시작 - {}개 데이터 준비", warmupCount);
        
        for (int i = 0; i < warmupCount; i++) {
            try {
                int randomOffset = random.nextInt((int) totalRecords);
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                
                if (!randomUrls.isEmpty()) {
                    Url url = randomUrls.get(0);
                    redisUrlCacheService.cacheUrl(url);
                    warmedUpShortCodes.add(url.getShortCode());
                    
                    // 처음 10개 워밍업 shortCode 로깅
                    if (i < 10) {
                        log.info("🔥 워밍업 #{}: shortCode={}", i + 1, url.getShortCode());
                    }
                }
            } catch (Exception e) {
                log.warn("캐시 워밍업 실패: {}", e.getMessage());
            }
        }
        
        log.info("🔥 캐시 워밍업 완료 - {}개 데이터 캐시됨", warmedUpShortCodes.size());
        log.info("📊 현재 캐시 크기: {}", redisUrlCacheService.getCacheSize());
        
        // 워밍업된 shortCode 목록 샘플 로깅
        if (!warmedUpShortCodes.isEmpty()) {
            log.info("📝 워밍업된 shortCode 샘플: {}", warmedUpShortCodes.subList(0, Math.min(5, warmedUpShortCodes.size())));
        }
        
        return warmedUpShortCodes;
    }

    /**
     * 캐시 워밍업 - 테스트 데이터의 일부를 미리 캐시에 로드
     */
    private void warmupCache(int warmupCount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long totalRecords = urlRepository.count();
        
        for (int i = 0; i < warmupCount; i++) {
            try {
                int randomOffset = random.nextInt((int) totalRecords);
                List<Url> randomUrls = urlRepository.findAll(PageRequest.of(randomOffset, 1)).getContent();
                
                if (!randomUrls.isEmpty()) {
                    Url url = randomUrls.get(0);
                    redisUrlCacheService.cacheUrl(url);
                }
            } catch (Exception e) {
                log.warn("캐시 워밍업 실패: {}", e.getMessage());
            }
        }
        
        log.info("🔥 캐시 워밍업 완료 - {}개 데이터 캐시됨", warmupCount);
        log.info("📊 현재 캐시 크기: {}", redisUrlCacheService.getCacheSize());
    }
    
    /**
     * 캐시 성능 결과 계산
     */
    private Map<String, Object> calculateCachePerformanceResults(
            List<Long> cacheHitTimes, List<Long> cacheMissTimes, List<Long> dbOnlyTimes,
            int cacheHitCount, int cacheMissCount, int dbOnlyCount,
            long totalRecords, int testCount) {
        
        Map<String, Object> results = new HashMap<>();
        
        // 기본 정보
        results.put("test_type", "REDIS_CACHE_PERFORMANCE");
        results.put("total_records", totalRecords);
        results.put("test_count", testCount);
        
        // 캐시 통계
        results.put("cache_hit_count", cacheHitCount);
        results.put("cache_miss_count", cacheMissCount);
        results.put("db_only_count", dbOnlyCount);
        results.put("cache_hit_rate", cacheHitCount > 0 ? (double) cacheHitCount / (cacheHitCount + cacheMissCount) * 100 : 0);
        
        // 캐시 HIT 성능
        if (!cacheHitTimes.isEmpty()) {
            cacheHitTimes.sort(Long::compareTo);
            double avgCacheHitMs = cacheHitTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            results.put("avg_cache_hit_ms", avgCacheHitMs);
            results.put("cache_hit_qps", cacheHitCount > 0 ? 1000.0 / avgCacheHitMs : 0);
        }
        
        // 캐시 MISS 성능 (DB 조회)
        if (!cacheMissTimes.isEmpty()) {
            cacheMissTimes.sort(Long::compareTo);
            double avgCacheMissMs = cacheMissTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            results.put("avg_cache_miss_ms", avgCacheMissMs);
            results.put("cache_miss_qps", cacheMissCount > 0 ? 1000.0 / avgCacheMissMs : 0);
        }
        
        // DB 직접 조회 성능
        if (!dbOnlyTimes.isEmpty()) {
            dbOnlyTimes.sort(Long::compareTo);
            double avgDbOnlyMs = dbOnlyTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            results.put("avg_db_only_ms", avgDbOnlyMs);
            results.put("db_only_qps", dbOnlyCount > 0 ? 1000.0 / avgDbOnlyMs : 0);
            
            // 성능 개선 비율 계산
            if (!cacheHitTimes.isEmpty()) {
                double avgCacheHitMs = cacheHitTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
                double improvementRatio = avgDbOnlyMs / avgCacheHitMs;
                results.put("performance_improvement_ratio", improvementRatio);
            }
        }
        
        return results;
    }
    
    /**
     * 캐시 성능 결과 로깅
     */
    private void logCachePerformanceResults(Map<String, Object> results) {
        log.info("📊 === Redis 캐시 성능 테스트 결과 ===");
        log.info("  🗄️ 저장소: Redis + H2 Database");
        log.info(String.format("  📈 총 데이터: %s", NUMBER_FORMAT.format((Long) results.get("total_records"))));
        log.info(String.format("  🎯 테스트 횟수: %s", NUMBER_FORMAT.format((Integer) results.get("test_count"))));
        log.info(String.format("  🎯 캐시 HIT: %s회", NUMBER_FORMAT.format((Integer) results.get("cache_hit_count"))));
        log.info(String.format("  ❌ 캐시 MISS: %s회", NUMBER_FORMAT.format((Integer) results.get("cache_miss_count"))));
        log.info(String.format("  📊 캐시 HIT율: %.1f%%", (Double) results.get("cache_hit_rate")));
        
        if (results.containsKey("avg_cache_hit_ms")) {
            log.info(String.format("  ⚡ 평균 캐시 HIT: %.3fms", (Double) results.get("avg_cache_hit_ms")));
            log.info(String.format("  🚀 캐시 HIT QPS: %.0f", (Double) results.get("cache_hit_qps")));
        }
        
        if (results.containsKey("avg_cache_miss_ms")) {
            log.info(String.format("  🗄️ 평균 캐시 MISS (DB): %.3fms", (Double) results.get("avg_cache_miss_ms")));
        }
        
        if (results.containsKey("avg_db_only_ms")) {
            log.info(String.format("  🗄️ 평균 DB 직접 조회: %.3fms", (Double) results.get("avg_db_only_ms")));
            log.info(String.format("  🗄️ DB 직접 QPS: %.0f", (Double) results.get("db_only_qps")));
        }
        
        if (results.containsKey("performance_improvement_ratio")) {
            log.info(String.format("  📈 성능 개선 비율: %.1f배 빨라짐", (Double) results.get("performance_improvement_ratio")));
        }
        
        log.info("📊 === Redis 캐시 성능 분석 완료 ===");
    }
} 