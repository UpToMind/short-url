package com.example.demo.controller;

import com.example.demo.service.PerformanceTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.example.demo.service.RedisUrlCacheService;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestController {
    
    private final PerformanceTestService performanceTestService;
    private final RedisUrlCacheService redisUrlCacheService;
    
    /**
     * 대량 데이터 삽입 (1000만 개 기본) - 병렬 처리
     * 예: POST /api/performance/insert-bulk?count=10000000
     */
    @PostMapping("/insert-bulk")
    public ResponseEntity<Map<String, Object>> insertBulkData(
            @RequestParam(defaultValue = "10000000") int count) {
        
        log.info("🚀 병렬 대량 데이터 삽입 요청 - {}개", count);
        
        // 최대 제한 설정 (메모리 보호)
        if (count > 50000000) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "최대 5천만 개까지만 삽입 가능합니다.",
                "maxCount", 50000000
            ));
        }
        
        try {
            int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, 20);
            
            // 비동기로 실행 (요청이 타임아웃되지 않도록)
            CompletableFuture.runAsync(() -> {
                performanceTestService.insertBulkTestData(count);
            });
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "병렬 대량 데이터 삽입이 백그라운드에서 시작되었습니다.",
                "count", count,
                "parallelProcessing", true,
                "expectedThreads", threadCount,
                "estimatedSpeedup", threadCount + "배 빠른 속도 예상",
                "note", "진행 상황은 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("병렬 대량 데이터 삽입 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "병렬 대량 데이터 삽입 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 소량 데이터 삽입 (동기 처리) - 병렬 처리
     * 예: POST /api/performance/insert-small?count=1000
     */
    @PostMapping("/insert-small")
    public ResponseEntity<Map<String, Object>> insertSmallData(
            @RequestParam(defaultValue = "1000") int count) {
        
        log.info("🚀 병렬 소량 데이터 삽입 요청 (동기) - {}개", count);
        
        // 동기 처리는 최대 10만 개로 제한
        if (count > 100000) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "동기 처리는 최대 10만 개까지만 가능합니다.",
                "maxCount", 100000
            ));
        }
        
        try {
            long startTime = System.currentTimeMillis();
            int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, 20);
            
            performanceTestService.insertBulkTestData(count);
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "병렬 데이터 삽입이 완료되었습니다.",
                "count", count,
                "parallelProcessing", true,
                "usedThreads", threadCount,
                "executionTimeMs", endTime - startTime
            ));
            
        } catch (Exception e) {
            log.error("병렬 데이터 삽입 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "병렬 데이터 삽입 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 단일 조회 성능 테스트
     * 예: POST /api/performance/test-single?count=1000
     */
    @PostMapping("/test-single")
    public ResponseEntity<Map<String, Object>> testSingleQuery(
            @RequestParam(defaultValue = "1000") int count) {
        
        log.info("🔍 단일 조회 성능 테스트 요청 - {}회", count);
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.performSingleQueryTest(count);
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "단일 조회 성능 테스트가 완료되었습니다.",
                "testCount", count,
                "executionTimeMs", endTime - startTime,
                "note", "상세한 성능 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("성능 테스트 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "성능 테스트 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * shortCode 조회 성능 테스트
     * 예: POST /api/performance/test-shortcode?count=1000
     */
    @PostMapping("/test-shortcode")
    public ResponseEntity<Map<String, Object>> testShortCodeQuery(
            @RequestParam(defaultValue = "1000") int count) {
        
        log.info("🔍 shortCode 조회 성능 테스트 요청 - {}회", count);
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.performShortCodeQueryTest(count);
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "shortCode 조회 성능 테스트가 완료되었습니다.",
                "testCount", count,
                "executionTimeMs", endTime - startTime,
                "note", "상세한 성능 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("shortCode 성능 테스트 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "shortCode 성능 테스트 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 배치 조회 성능 테스트
     * 예: POST /api/performance/test-batch?batchSize=100&batchCount=10
     */
    @PostMapping("/test-batch")
    public ResponseEntity<Map<String, Object>> testBatchQuery(
            @RequestParam(defaultValue = "100") int batchSize,
            @RequestParam(defaultValue = "10") int batchCount) {
        
        log.info("🔍 배치 조회 성능 테스트 요청 - {} 배치 x {} 크기", batchCount, batchSize);
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.performBatchQueryTest(batchSize, batchCount);
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "배치 조회 성능 테스트가 완료되었습니다.",
                "batchSize", batchSize,
                "batchCount", batchCount,
                "totalQueries", batchSize * batchCount,
                "executionTimeMs", endTime - startTime,
                "note", "상세한 성능 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("배치 성능 테스트 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "배치 성능 테스트 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 전체 성능 테스트 시나리오 실행
     */
    @PostMapping("/test-full")
    public ResponseEntity<Map<String, Object>> runFullPerformanceTest() {
        log.info("🚀 전체 성능 테스트 시나리오 시작");
        
        try {
            // 비동기로 실행
            CompletableFuture.runAsync(() -> {
                performanceTestService.runFullPerformanceTest();
            });
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "전체 성능 테스트 시나리오가 백그라운드에서 시작되었습니다.",
                "tests", new String[]{
                    "데이터베이스 현황 확인",
                    "단일 조회 성능 테스트 (1000회)",
                    "shortCode 조회 성능 테스트 (1000회)",
                    "배치 조회 성능 테스트 (100개 x 10배치)",
                    "배치 조회 성능 테스트 (1000개 x 5배치)"
                },
                "note", "진행 상황과 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("성능 테스트 시나리오 실행 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "성능 테스트 시나리오 실행 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * shortCode 응답 속도 정밀 측정 (Redis 비교용)
     */
    @PostMapping("/response-time")
    public ResponseEntity<?> measureResponseTime(@RequestParam(defaultValue = "1000") int testCount) {
        try {
            log.info("📊 shortCode 응답 속도 측정 API 호출 - {} 회", testCount);
            
            Map<String, Object> results = performanceTestService.measureRedirectResponseTime(testCount);
            
            if (results.containsKey("error")) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "테스트 실행 실패",
                    "error", results.get("error")
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "shortCode 응답 속도 측정 완료",
                "results", results
            ));
            
        } catch (Exception e) {
            log.error("shortCode 응답 속도 측정 중 오류: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "shortCode 응답 속도 측정 실패",
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * 실제 리디렉션 응답 속도 측정 (End-to-End 성능)
     * Redis 비교를 위한 실제 서비스 시나리오 테스트
     */
    @PostMapping("/redirect-response-time")
    public ResponseEntity<?> measureRedirectResponseTime(@RequestParam(defaultValue = "1000") int testCount) {
        try {
            log.info("📊 실제 리디렉션 응답 속도 측정 API 호출 - {} 회 (End-to-End)", testCount);
            
            Map<String, Object> results = performanceTestService.measureRedirectResponseTime(testCount);
            
            if (results.containsKey("error")) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "테스트 실행 실패",
                    "error", results.get("error")
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "실제 리디렉션 응답 속도 측정 완료 (End-to-End)",
                "results", results,
                "note", "이 결과를 Redis 캐시 적용 후 결과와 비교하세요"
            ));
            
        } catch (Exception e) {
            log.error("실제 리디렉션 응답 속도 측정 중 오류: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "실제 리디렉션 응답 속도 측정 실패",
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Redis 캐시 성능 테스트 (DB와 비교)
     * 예: POST /api/performance/redis-cache-test?testCount=1000
     */
    @PostMapping("/redis-cache-test")
    public ResponseEntity<?> measureRedisCachePerformance(@RequestParam(defaultValue = "1000") int testCount) {
        try {
            log.info("🚀 Redis 캐시 성능 테스트 API 호출 - {} 회", testCount);
            
            Map<String, Object> results = performanceTestService.measureRedisCachePerformance(testCount);
            
            if (results.containsKey("error")) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "테스트 실행 실패",
                    "error", results.get("error")
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Redis 캐시 성능 테스트 완료",
                "results", results,
                "note", "캐시 워밍업 후 Cache Hit/Miss 성능을 DB 직접 조회와 비교했습니다"
            ));
            
        } catch (Exception e) {
            log.error("Redis 캐시 성능 테스트 중 오류: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Redis 캐시 성능 테스트 실패",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Redis 캐시 상태 확인 (디버깅용)
     * 예: GET /api/performance/redis-cache-status
     */
    @GetMapping("/redis-cache-status")
    public ResponseEntity<?> getRedisCacheStatus() {
        try {
            log.info("🔍 Redis 캐시 상태 확인 API 호출");
            
            Map<String, Object> cacheInfo = redisUrlCacheService.getCacheInfo();
            boolean isConnected = redisUrlCacheService.isRedisConnected();
            long cacheSize = redisUrlCacheService.getCacheSize();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Redis 캐시 상태 조회 완료",
                "redis_connected", isConnected,
                "cache_size", cacheSize,
                "cache_info", cacheInfo,
                "timestamp", System.currentTimeMillis()
            ));
            
        } catch (Exception e) {
            log.error("Redis 캐시 상태 확인 중 오류: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Redis 캐시 상태 확인 실패",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Redis 캐시 클리어 (디버깅용)
     * 예: DELETE /api/performance/redis-cache-clear
     */
    @DeleteMapping("/redis-cache-clear")
    public ResponseEntity<?> clearRedisCache() {
        try {
            log.info("🧹 Redis 캐시 클리어 API 호출");
            
            long beforeSize = redisUrlCacheService.getCacheSize();
            redisUrlCacheService.clearAllCache();
            long afterSize = redisUrlCacheService.getCacheSize();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Redis 캐시 클리어 완료",
                "before_size", beforeSize,
                "after_size", afterSize,
                "cleared_count", beforeSize - afterSize
            ));
            
        } catch (Exception e) {
            log.error("Redis 캐시 클리어 중 오류: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Redis 캐시 클리어 실패",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 데이터베이스 현황 조회
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDatabaseStatus() {
        try {
            performanceTestService.showDatabaseStatus();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "데이터베이스 현황이 서버 로그에 출력되었습니다.",
                "note", "상세한 정보는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("데이터베이스 현황 조회 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "데이터베이스 현황 조회 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 모든 데이터 삭제
     * 예: DELETE /api/performance/clear?confirm=true
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAllData(
            @RequestParam(defaultValue = "false") boolean confirm) {
        
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "데이터 삭제를 확인하려면 confirm=true 파라미터를 추가하세요.",
                "example", "/api/performance/clear?confirm=true"
            ));
        }
        
        log.info("🗑️ 모든 데이터 삭제 요청");
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.clearAllData();
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "모든 데이터가 성공적으로 삭제되었습니다.",
                "executionTimeMs", endTime - startTime
            ));
            
        } catch (Exception e) {
            log.error("데이터 삭제 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "데이터 삭제 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 중복된 original URL 검사
     * 예: GET /api/performance/check-duplicate-urls
     */
    @GetMapping("/check-duplicate-urls")
    public ResponseEntity<Map<String, Object>> checkDuplicateUrls() {
        log.info("🔍 중복 URL 검사 요청");
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.findDuplicateUrls();
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "중복 URL 검사가 완료되었습니다.",
                "executionTimeMs", endTime - startTime,
                "note", "상세한 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("중복 URL 검사 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "중복 URL 검사 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 중복된 shortCode 검사 (데이터 무결성)
     * 예: GET /api/performance/check-duplicate-codes
     */
    @GetMapping("/check-duplicate-codes")
    public ResponseEntity<Map<String, Object>> checkDuplicateShortCodes() {
        log.info("🔍 중복 shortCode 검사 요청");
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.findDuplicateShortCodes();
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "중복 shortCode 검사가 완료되었습니다.",
                "executionTimeMs", endTime - startTime,
                "note", "상세한 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("중복 shortCode 검사 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "중복 shortCode 검사 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 중복 Snowflake ID 검사
     * 예: GET /api/performance/check-duplicate-snowflake-ids
     */
    @GetMapping("/check-duplicate-snowflake-ids")
    public ResponseEntity<Map<String, Object>> checkDuplicateIds() {
        log.info("🔍 중복 Snowflake ID 검사 요청");
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.findDuplicateSnowflakeIds();
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "중복 Snowflake ID 검사가 완료되었습니다.",
                "executionTimeMs", endTime - startTime,
                "note", "상세한 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("중복 Snowflake ID 검사 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "중복 Snowflake ID 검사 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 전체 중복 검사 실행 (URL + shortCode)
     * 예: GET /api/performance/check-all-duplicates
     */
    @GetMapping("/check-all-duplicates")
    public ResponseEntity<Map<String, Object>> checkAllDuplicates() {
        log.info("🔍 전체 중복 검사 요청");
        
        try {
            long startTime = System.currentTimeMillis();
            performanceTestService.runDuplicateAnalysis();
            long endTime = System.currentTimeMillis();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "전체 중복 검사가 완료되었습니다.",
                "executionTimeMs", endTime - startTime,
                "checkedItems", new String[]{"데이터베이스 현황", "중복 original URL", "중복 shortCode", "중복 Snowflake ID"},
                "note", "상세한 결과는 서버 로그를 확인하세요."
            ));
            
        } catch (Exception e) {
            log.error("전체 중복 검사 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "전체 중복 검사 중 오류가 발생했습니다: " + e.getMessage()
            ));
        }
    }
    
    /**
     * API 도움말
     */
    @GetMapping("/help")
    public ResponseEntity<Map<String, Object>> getHelp() {
        return ResponseEntity.ok(Map.of(
            "title", "Short URL 성능 테스트 API 도움말",
            "description", "대량 데이터 삽입, 조회 성능 테스트, 중복 검사 등의 기능을 제공합니다.",
            "parallelProcessing", "모든 삽입 작업은 병렬 처리로 최적화되어 있습니다.",
            "endpoints", Map.of(
                "데이터 삽입", Map.of(
                    "POST /api/performance/insert-bulk?count=10000000", "대량 데이터 삽입 (백그라운드)",
                    "POST /api/performance/insert-small?count=1000", "소량 데이터 삽입 (동기)"
                ),
                "성능 테스트", Map.of(
                    "POST /api/performance/test-single?count=1000", "단일 조회 성능 테스트",
                    "POST /api/performance/test-shortcode?count=1000", "shortCode 조회 성능 테스트",
                    "POST /api/performance/test-batch?batchSize=100&batchCount=10", "배치 조회 성능 테스트",
                    "POST /api/performance/test-full", "전체 성능 테스트"
                ),
                "Redis 비교 테스트", Map.of(
                    "POST /api/performance/redirect-response-time?testCount=1000", "실제 리디렉션 응답 속도 (End-to-End)",
                    "POST /api/performance/redis-cache-test?testCount=1000", "Redis 캐시 성능 테스트"
                ),
                "중복 검사", Map.of(
                    "GET /api/performance/check-duplicate-urls", "중복 original URL 검사",
                    "GET /api/performance/check-duplicate-codes", "중복 shortCode 검사 (무결성)",
                    "GET /api/performance/check-duplicate-snowflake-ids", "중복 Snowflake ID 검사",
                    "GET /api/performance/check-all-duplicates", "전체 중복 검사"
                ),
                "관리", Map.of(
                    "GET /api/performance/status", "데이터베이스 현황 조회",
                    "DELETE /api/performance/clear?confirm=true", "모든 데이터 삭제"
                )
            ),
            "redis_comparison", Map.of(
                "description", "Redis 캐시 적용 전후 성능 비교를 위한 측정 API",
                "redirect_response_time", "실제 서비스 시나리오 (shortCode → 리디렉션) 전체 처리 시간 측정",
                "metrics", new String[]{
                    "평균 응답 시간 (ms)",
                    "P50, P90, P95, P99 퍼센타일",
                    "QPS (Queries Per Second)",
                    "성공률 및 실패율",
                    "DB 조회 비율 (전체 시간 대비)"
                }
            ),
            "notes", new String[]{
                "대량 삽입은 병렬 처리로 CPU 코어 수의 2배 스레드를 사용합니다.",
                "진행 상황과 상세 결과는 서버 로그에서 확인할 수 있습니다.",
                "중복 검사는 SQL 집계 쿼리를 사용하여 효율적으로 처리됩니다.",
                "Redis 비교 테스트는 캐시 적용 전후 성능 측정을 위해 설계되었습니다.",
                "redirect-response-time은 실제 서비스 시나리오에 가장 가까운 측정입니다."
            }
        ));
    }
} 