package com.example.demo.controller;

import com.example.demo.dto.UrlRequestDto;
import com.example.demo.dto.UrlResponseDto;
import com.example.demo.service.UrlService;
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
     * 헬스 체크
     */
    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("URL 단축기 서비스가 정상적으로 동작 중입니다!");
    }
} 