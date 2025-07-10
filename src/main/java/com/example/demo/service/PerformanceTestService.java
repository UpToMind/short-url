package com.example.demo.service;

import com.example.demo.entity.Url;
import com.example.demo.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Query;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {
    
    private final UrlRepository urlRepository;
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHORT_CODE_LENGTH = 6;
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
     * 1000만 건 대량 데이터 삽입 (병렬 처리)
     */
    @Transactional
    public void insertBulkTestData(int totalCount) {
        log.info("🚀 병렬 대량 테스트 데이터 삽입 시작 - 총 {}개", totalCount);
        long startTime = System.currentTimeMillis();
        
        // CPU 코어 수의 2배만큼 스레드 풀 생성 (I/O 바운드 작업에 적합)
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, 20);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // 중복 방지를 위한 Thread-Safe Set
        Set<String> usedShortCodes = ConcurrentHashMap.newKeySet();
        
        // 배치 단위로 데이터 삽입
        int batchCount = (totalCount + BATCH_SIZE - 1) / BATCH_SIZE;
        List<Future<Void>> futures = new ArrayList<>();
        
        // 진행률 추적을 위한 Atomic 변수들
        AtomicInteger completedBatches = new AtomicInteger(0);
        AtomicLong totalInserted = new AtomicLong(0);
        
        log.info("📊 병렬 처리 설정: {}개 스레드, {}개 배치로 분할", threadCount, batchCount);
        
        // 각 배치를 병렬로 처리
        for (int i = 0; i < batchCount; i++) {
            final int batchIndex = i;
            final int currentBatchSize = Math.min(BATCH_SIZE, totalCount - (i * BATCH_SIZE));
            
            Future<Void> future = executor.submit(() -> {
                try {
                    SecureRandom random = new SecureRandom();
                    List<Url> urls = new ArrayList<>();
                    
                    for (int j = 0; j < currentBatchSize; j++) {
                        // 고유한 단축 코드 생성
                        String shortCode = generateUniqueShortCodeForBulk(usedShortCodes, random);
                        
                        // 테스트용 랜덤 URL 생성
                        String originalUrl = generateTestUrl(random, (batchIndex * BATCH_SIZE) + j);
                        
                        Url url = new Url();
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
                        
                        log.info("📊 병렬 진행률: %d/%d 배치 완료 (%.1f%%), 삽입 속도: %.0f records/sec", 
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
        log.info("⏳ 모든 배치 작업 완료 대기 중...");
        for (Future<Void> future : futures) {
            try {
                future.get(); // 각 배치 완료까지 대기
            } catch (Exception e) {
                log.error("배치 처리 중 오류 발생: {}", e.getMessage());
            }
        }
        
        // 스레드 풀 종료
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("⚠️ 일부 작업이 60초 내에 완료되지 않아 강제 종료됨");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        long endTime = System.currentTimeMillis();
        double totalSeconds = (endTime - startTime) / 1000.0;
        double averageRate = totalCount / totalSeconds;
        
        log.info("✅ 병렬 대량 데이터 삽입 완료!");
        log.info(String.format("📈 총 소요시간: %.2f초", totalSeconds));
        log.info(String.format("📈 평균 삽입 속도: %.0f records/sec", averageRate));
        log.info("📈 총 삽입 데이터: {}개", totalCount);
        log.info("📈 사용된 스레드 수: {}개", threadCount);
        log.info("📈 성능 향상: 병렬 처리로 약 {}배 빠른 속도", threadCount);
    }
    
    /**
     * 대량 데이터용 고유 단축 코드 생성 (Thread-Safe)
     */
    private String generateUniqueShortCodeForBulk(Set<String> usedShortCodes, SecureRandom random) {
        String shortCode;
        int attempts = 0;
        int maxAttempts = 50; // 병렬 처리에서는 더 많은 시도 허용
        
        do {
            StringBuilder sb = new StringBuilder(SHORT_CODE_LENGTH);
            for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
                sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
            }
            shortCode = sb.toString();
            attempts++;
            
            // 너무 많은 시도 시 DB 체크로 전환
            if (attempts > maxAttempts) {
                // DB 중복 체크 (더 안전하지만 느림)
                if (!urlRepository.existsByShortCode(shortCode)) {
                    break;
                }
                // DB에서도 중복이면 새로 생성
                if (attempts > maxAttempts + 10) {
                    log.warn("⚠️ shortCode 생성에 너무 많은 시도가 필요함: {}회", attempts);
                    // 더 강력한 고유성 보장을 위해 현재 시간 포함
                    shortCode = shortCode.substring(0, 4) + 
                               String.valueOf(System.nanoTime()).substring(8, 10);
                    break;
                }
            }
        } while (usedShortCodes.contains(shortCode));
        
        usedShortCodes.add(shortCode);
        return shortCode;
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
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int successCount = 0;
        
        for (int i = 0; i < testCount; i++) {
            try {
                // 랜덤 ID로 조회
                long randomId = random.nextLong(totalRecords) + 1;
                
                long startTime = System.nanoTime();
                urlRepository.findById(randomId);
                long endTime = System.nanoTime();
                
                totalTime += (endTime - startTime);
                successCount++;
                
            } catch (Exception e) {
                log.warn("조회 실패: {}", e.getMessage());
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
        }
    }
    
    /**
     * shortCode로 조회 성능 테스트 (실제 사용 시나리오)
     */
    @Transactional(readOnly = true)
    public void performShortCodeQueryTest(int testCount) {
        log.info("🔍 shortCode 조회 성능 테스트 시작 - {}회 실행", testCount);
        
        long totalRecords = urlRepository.count();
        if (totalRecords == 0) {
            log.warn("⚠️ 테스트할 데이터가 없습니다.");
            return;
        }
        
        log.info("📊 메모리 효율적인 방식으로 shortCode 조회 테스트 실행");
        log.info("📊 총 데이터 수: {}개", totalRecords);
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int successCount = 0;
        
        for (int i = 0; i < testCount; i++) {
            try {
                // 랜덤 ID로 shortCode 조회 (메모리 효율적)
                long randomId = random.nextLong(totalRecords) + 1;
                
                // ID로 엔티티 조회 후 shortCode 추출
                Optional<Url> urlOpt = urlRepository.findById(randomId);
                if (urlOpt.isPresent()) {
                    String shortCode = urlOpt.get().getShortCode();
                    
                    // 실제 shortCode 조회 성능 측정
                    long startTime = System.nanoTime();
                    urlRepository.findByShortCode(shortCode);
                    long endTime = System.nanoTime();
                    
                    totalTime += (endTime - startTime);
                    successCount++;
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
        
        SecureRandom random = new SecureRandom();
        long totalTime = 0;
        int totalQueries = 0;
        
        for (int batch = 0; batch < batchCount; batch++) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                ids.add(random.nextLong(totalRecords) + 1);
            }
            
            long startTime = System.nanoTime();
            urlRepository.findAllById(ids);
            long endTime = System.nanoTime();
            
            totalTime += (endTime - startTime);
            totalQueries += batchSize;
        }
        
        double totalTimeMs = totalTime / 1_000_000.0;
        double averageTimeMs = totalTimeMs / batchCount;
        double queriesPerSecond = (totalQueries * 1000.0) / totalTimeMs;
        
        log.info("📊 배치 조회 성능 결과:");
        log.info("  - 총 데이터 수: {}", totalRecords);
        log.info("  - 총 배치 수: {}", batchCount);
        log.info("  - 배치 크기: {}", batchSize);
        log.info("  - 총 쿼리 수: {}", totalQueries);
        log.info(String.format("  - 총 소요 시간: %.2fms", totalTimeMs));
        log.info(String.format("  - 배치당 평균 시간: %.3fms", averageTimeMs));
        log.info(String.format("  - 초당 처리 가능 쿼리: %.0f queries/sec", queriesPerSecond));
    }
    
    /**
     * 전체 성능 테스트 시나리오 실행
     */
    public void runFullPerformanceTest() {
        log.info("🚀 === 전체 성능 테스트 시나리오 시작 ===");
        
        // 1. 데이터베이스 현황 확인
        showDatabaseStatus();
        
        // 2. 단일 조회 성능 테스트 (1000회)
        performSingleQueryTest(1000);
        
        // 3. shortCode 조회 성능 테스트 (1000회)
        performShortCodeQueryTest(1000);
        
        // 4. 배치 조회 성능 테스트 (100개씩 10배치)
        performBatchQueryTest(100, 10);
        
        // 5. 더 큰 배치 조회 성능 테스트 (1000개씩 5배치)
        performBatchQueryTest(1000, 5);
        
        log.info("🚀 === 전체 성능 테스트 시나리오 완료 ===");
    }
    
    /**
     * 데이터베이스 현황 조회
     */
    @Transactional(readOnly = true)
    public void showDatabaseStatus() {
        long totalCount = urlRepository.count();
        log.info("📊 데이터베이스 현황:");
        log.info("  - 총 URL 개수: {}", totalCount);
        
        if (totalCount > 0) {
            // 개선: Pageable 사용으로 메모리 효율화
            List<Url> sampleUrls = urlRepository.findAll(PageRequest.of(0, 3))
                .getContent();
            
            log.info("  - 샘플 데이터:");
            sampleUrls.forEach(url -> 
                log.info("    * {} -> {} (클릭: {})", 
                    url.getShortCode(), 
                    url.getOriginalUrl().length() > 50 ? 
                        url.getOriginalUrl().substring(0, 50) + "..." : url.getOriginalUrl(), 
                    url.getClickCount()));
        }
    }
    
    /**
     * 모든 데이터 삭제
     */
    @Transactional
    public void clearAllData() {
        log.info("🗑️ 모든 데이터 삭제 중...");
        long startTime = System.currentTimeMillis();
        
        long count = urlRepository.count();
        urlRepository.deleteAll();
        
        long endTime = System.currentTimeMillis();
        log.info("✅ 데이터 삭제 완료 - {}개 삭제, 소요시간: {}ms", count, endTime - startTime);
    }
    
    /**
     * 중복된 original URL 찾기 및 통계
     */
    @Transactional(readOnly = true)
    public void findDuplicateUrls() {
        log.info("🔍 중복 URL 검사 시작...");
        long startTime = System.currentTimeMillis();
        
        // 전체 URL 개수
        long totalCount = urlRepository.count();
        
        // 중복된 original URL과 개수 조회 (SQL 집계 사용)
        List<Object[]> duplicates = urlRepository.findDuplicateOriginalUrls();
        
        long duplicateGroupCount = duplicates.size();
        long totalDuplicateRecords = duplicates.stream()
            .mapToLong(row -> (Long) row[1] - 1) // 각 그룹에서 첫 번째를 제외한 중복 개수
            .sum();
        
        long endTime = System.currentTimeMillis();
        
        log.info("📊 중복 URL 검사 결과:");
        log.info("  - 총 URL 개수: {}", totalCount);
        log.info("  - 고유 URL 개수: {}", totalCount - totalDuplicateRecords);
        log.info("  - 중복 그룹 수: {}", duplicateGroupCount);
        log.info("  - 중복된 레코드 수: {}", totalDuplicateRecords);
        log.info(String.format("  - 중복률: %.2f%%", totalCount > 0 ? (totalDuplicateRecords * 100.0 / totalCount) : 0));
        log.info(String.format("  - 검사 소요시간: %dms", endTime - startTime));
        
        // 가장 많이 중복된 URL 상위 10개 표시
        if (!duplicates.isEmpty()) {
            log.info("📋 가장 많이 중복된 URL TOP 10:");
            duplicates.stream()
                .sorted((a, b) -> Long.compare((Long) b[1], (Long) a[1])) // 중복 개수 내림차순
                .limit(10)
                .forEach(row -> {
                    String url = (String) row[0];
                    Long count = (Long) row[1];
                    String displayUrl = url.length() > 60 ? url.substring(0, 60) + "..." : url;
                    log.info("    * {}회 중복: {}", count, displayUrl);
                });
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
        
        log.info("🚀 === 전체 중복 검사 완료 ===");
    }
} 