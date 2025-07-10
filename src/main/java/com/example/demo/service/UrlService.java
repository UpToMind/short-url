package com.example.demo.service;

import com.example.demo.dto.UrlRequestDto;
import com.example.demo.dto.UrlResponseDto;
import com.example.demo.entity.Url;
import com.example.demo.repository.UrlRepository;
import com.example.demo.util.SnowflakeIdGenerator;
import com.example.demo.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UrlService {
    
    private final UrlRepository urlRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Base62Encoder base62Encoder;
    private static final String BASE_URL = "http://localhost:8080";
    
    /**
     * URL 단축
     */
    public UrlResponseDto shortenUrl(UrlRequestDto requestDto) {
        String originalUrl = requestDto.getOriginalUrl();
        
        // 이미 단축된 URL이 있는지 확인
        return urlRepository.findByOriginalUrl(originalUrl)
            .map(existingUrl -> {
                log.info("기존 단축 URL 반환: {} (Snowflake ID: {})", 
                        existingUrl.getShortCode(), existingUrl.getId());
                return UrlResponseDto.from(existingUrl, BASE_URL);
            })
            .orElseGet(() -> {
                // 새로운 단축 URL 생성
                long snowflakeId = snowflakeIdGenerator.nextId();
                String shortCode = base62Encoder.generateShortCode(snowflakeId);
                
                // 중복 검사 (매우 낮은 확률이지만 안전장치)
                while (urlRepository.existsByShortCode(shortCode) || urlRepository.existsById(snowflakeId)) {
                    log.warn("⚠️ shortCode 또는 Snowflake ID 중복 발생 (매우 드문 경우): shortCode={}, snowflakeId={}", shortCode, snowflakeId);
                    snowflakeId = snowflakeIdGenerator.nextId();
                    shortCode = base62Encoder.generateShortCode(snowflakeId);
                }
                
                Url url = new Url();
                url.setId(snowflakeId);  // Snowflake ID를 직접 Primary Key로 설정
                url.setOriginalUrl(originalUrl);
                url.setShortCode(shortCode);
                
                Url savedUrl = urlRepository.save(url);
                log.info("새로운 단축 URL 생성: {} -> {} (Snowflake ID: {})", 
                        originalUrl, shortCode, snowflakeId);
                
                return UrlResponseDto.from(savedUrl, BASE_URL);
            });
    }
    
    /**
     * 단축 URL로 원본 URL 조회 및 클릭 수 증가
     */
    public String getOriginalUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 단축 URL입니다: " + shortCode));
        
        // 클릭 수 증가
        url.setClickCount(url.getClickCount() + 1);
        urlRepository.save(url);
        
        log.info("단축 URL 접근: {} -> {} (클릭 수: {}, Snowflake ID: {})", 
                shortCode, url.getOriginalUrl(), url.getClickCount(), url.getId());
        
        return url.getOriginalUrl();
    }
    
    /**
     * 모든 단축 URL 조회
     */
    @Transactional(readOnly = true)
    public List<UrlResponseDto> getAllUrls() {
        return urlRepository.findAll().stream()
            .map(url -> UrlResponseDto.from(url, BASE_URL))
            .collect(Collectors.toList());
    }
    
    /**
     * 단축 URL 상세 정보 조회
     */
    @Transactional(readOnly = true)
    public UrlResponseDto getUrlInfo(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 단축 URL입니다: " + shortCode));
        
        return UrlResponseDto.from(url, BASE_URL);
    }
    
    /**
     * Snowflake ID로 URL 조회 (디버깅/관리용)
     */
    @Transactional(readOnly = true)
    public UrlResponseDto getUrlBySnowflakeId(Long snowflakeId) {
        Url url = urlRepository.findById(snowflakeId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Snowflake ID입니다: " + snowflakeId));
        
        return UrlResponseDto.from(url, BASE_URL);
    }
    
    /**
     * Snowflake ID 정보 파싱 (디버깅용)
     */
    public SnowflakeIdGenerator.IdInfo parseSnowflakeId(Long snowflakeId) {
        return snowflakeIdGenerator.parseId(snowflakeId);
    }
    
    /**
     * Base62 인코딩/디코딩 테스트 (개발용)
     */
    public void testBase62Encoding() {
        base62Encoder.testEncodeDecode();
    }
} 