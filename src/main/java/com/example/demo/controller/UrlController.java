package com.example.demo.controller;

import com.example.demo.dto.UrlRequestDto;
import com.example.demo.dto.UrlResponseDto;
import com.example.demo.service.UrlService;
import com.example.demo.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@Validated
public class UrlController {
    
    private final UrlService urlService;
    
    /**
     * URL 단축 API
     */
    @PostMapping("/api/shorten")
    public ResponseEntity<UrlResponseDto> shortenUrl(@Valid @RequestBody UrlRequestDto requestDto) {
        try {
            UrlResponseDto response = urlService.shortenUrl(requestDto);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("URL 단축 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 단축 URL 리다이렉트
     * HTTP 상태 코드:
     * - 302 (Found): 임시 리디렉션, 매번 서버 요청으로 클릭 수 추적 가능
     * - 301 (Moved Permanently): 영구 리디렉션, SEO 유리하지만 브라우저 캐싱으로 클릭 수 추적 어려움
     */
    @GetMapping("/{shortCode}")
    public void redirect(@PathVariable String shortCode, HttpServletResponse response) throws IOException {
        try {
            String originalUrl = urlService.getOriginalUrl(shortCode);
            
            // 방법 1: 302 임시 리디렉션 (기본값) - 클릭 수 추적에 유리
            response.sendRedirect(originalUrl);
            
            // 방법 2: 301 영구 리디렉션 - SEO에 유리 (주석 처리됨)
            // response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
            // response.setHeader("Location", originalUrl);
            
        } catch (IllegalArgumentException e) {
            log.warn("존재하지 않는 단축 URL 접근: {}", shortCode);
            response.sendError(HttpStatus.NOT_FOUND.value(), "단축 URL을 찾을 수 없습니다");
        } catch (Exception e) {
            log.error("리다이렉트 중 오류 발생: {}", e.getMessage());
            response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 오류가 발생했습니다");
        }
    }
    
    /**
     * 모든 단축 URL 조회
     */
    @GetMapping("/api/urls")
    public ResponseEntity<List<UrlResponseDto>> getAllUrls() {
        try {
            List<UrlResponseDto> urls = urlService.getAllUrls();
            return ResponseEntity.ok(urls);
        } catch (Exception e) {
            log.error("URL 목록 조회 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 단축 URL 상세 정보 조회
     */
    @GetMapping("/api/urls/{shortCode}")
    public ResponseEntity<UrlResponseDto> getUrlInfo(@PathVariable String shortCode) {
        try {
            UrlResponseDto response = urlService.getUrlInfo(shortCode);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("존재하지 않는 단축 URL 정보 요청: {}", shortCode);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("URL 정보 조회 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Snowflake ID로 URL 조회 (디버깅/관리용)
     */
    @GetMapping("/api/urls/snowflake/{snowflakeId}")
    public ResponseEntity<UrlResponseDto> getUrlBySnowflakeId(@PathVariable Long snowflakeId) {
        try {
            UrlResponseDto response = urlService.getUrlBySnowflakeId(snowflakeId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("존재하지 않는 Snowflake ID 요청: {}", snowflakeId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Snowflake ID로 URL 조회 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Snowflake ID 정보 파싱 (디버깅용)
     */
    @GetMapping("/api/snowflake/parse/{snowflakeId}")
    public ResponseEntity<Map<String, Object>> parseSnowflakeId(@PathVariable Long snowflakeId) {
        try {
            SnowflakeIdGenerator.IdInfo idInfo = urlService.parseSnowflakeId(snowflakeId);
            
            Map<String, Object> result = Map.of(
                "snowflakeId", snowflakeId,
                "timestamp", idInfo.getTimestamp(),
                "timestampFormatted", new java.util.Date(idInfo.getTimestamp()).toString(),
                "datacenterId", idInfo.getDatacenterId(),
                "workerId", idInfo.getWorkerId(),
                "sequence", idInfo.getSequence(),
                "info", idInfo.toString()
            );
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Snowflake ID 파싱 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Base62 인코딩/디코딩 테스트 (개발용)
     */
    @GetMapping("/api/test/base62")
    public ResponseEntity<String> testBase62Encoding() {
        try {
            urlService.testBase62Encoding();
            return ResponseEntity.ok("Base62 인코딩/디코딩 테스트가 완료되었습니다. 서버 로그를 확인하세요.");
        } catch (Exception e) {
            log.error("Base62 테스트 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 헬스 체크
     */
    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Snowflake ID 기반 URL 단축기 서비스가 정상적으로 동작 중입니다!");
    }

    /**
     * 단축 URL 삭제 (데이터 불일치 문제 재현용)
     */
    @DeleteMapping("/api/urls/{shortCode}")
    public ResponseEntity<Map<String, Object>> deleteUrl(@PathVariable String shortCode) {
        try {
            boolean deleted = urlService.deleteUrl(shortCode);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "URL이 삭제되었습니다 (캐시는 그대로 둠 - 데이터 불일치 상황 생성)",
                    "shortCode", shortCode,
                    "warning", "이제 캐시와 DB 간 데이터 불일치 상황이 발생했습니다!"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("URL 삭제 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", e.getMessage()));
        }
    }
    
    /**
     * 단축 URL 올바른 삭제 (캐시도 함께 삭제)
     */
    @DeleteMapping("/api/urls/{shortCode}/properly")
    public ResponseEntity<Map<String, Object>> deleteUrlProperly(@PathVariable String shortCode) {
        try {
            boolean deleted = urlService.deleteUrlProperly(shortCode);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "URL이 올바르게 삭제되었습니다 (Redis Pub/Sub으로 캐시 무효화)",
                    "shortCode", shortCode
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("URL 올바른 삭제 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", e.getMessage()));
        }
    }
    
    /**
     * 만료된 URL 시뮬레이션 (데이터 불일치 문제 재현용)
     */
    @PostMapping("/api/urls/{shortCode}/expire")
    public ResponseEntity<Map<String, Object>> simulateExpiredUrl(@PathVariable String shortCode) {
        log.info("⏰ URL 만료 시뮬레이션 API 호출: {}", shortCode);
        boolean expired = urlService.simulateExpiredUrl(shortCode);
        if (expired) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "URL 만료 시뮬레이션 완료 (캐시는 그대로 둠 - 데이터 불일치 상황 생성)",
                "shortCode", shortCode,
                "warning", "이제 캐시와 DB 간 데이터 불일치 상황이 발생했습니다!"
            ));
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * URL 만료 처리 (올바른 방법)
     */
    @PostMapping("/api/urls/{shortCode}/expire-properly")
    public ResponseEntity<Map<String, Object>> expireUrlProperly(@PathVariable String shortCode) {
        log.info("⏰ URL 올바른 만료 처리 API 호출: {}", shortCode);
        boolean expired = urlService.expireUrl(shortCode);
        if (expired) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "URL이 올바르게 만료 처리되었습니다 (Redis Pub/Sub으로 캐시 무효화)",
                "shortCode", shortCode
            ));
        }
        return ResponseEntity.notFound().build();
    }
    
    /**
     * 만료된 URL 정리 (배치 작업)
     */
    @PostMapping("/api/urls/cleanup-expired")
    public ResponseEntity<Map<String, Object>> cleanupExpiredUrls() {
        log.info("🧹 만료된 URL 정리 API 호출");
        urlService.cleanupExpiredUrls();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "만료된 URL 정리 작업이 완료되었습니다"
        ));
    }
    
    /**
     * 캐시와 DB 데이터 일치성 검증
     */
    @GetMapping("/api/urls/{shortCode}/validate")
    public ResponseEntity<Map<String, Object>> validateCacheConsistency(@PathVariable String shortCode) {
        try {
            boolean isConsistent = urlService.validateCacheConsistency(shortCode);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "shortCode", shortCode,
                "isConsistent", isConsistent,
                "message", isConsistent ? "캐시와 DB 데이터가 일치합니다" : "캐시와 DB 데이터가 불일치합니다!"
            ));
        } catch (Exception e) {
            log.error("일치성 검증 중 오류 발생: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", e.getMessage()));
        }
    }
} 