package com.example.demo.service;

import com.example.demo.dto.UrlRequestDto;
import com.example.demo.dto.UrlResponseDto;
import com.example.demo.entity.Url;
import com.example.demo.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UrlService {
    
    private final UrlRepository urlRepository;
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHORT_CODE_LENGTH = 6;
    private static final String BASE_URL = "http://localhost:8080";
    
    /**
     * URL 단축
     */
    public UrlResponseDto shortenUrl(UrlRequestDto requestDto) {
        String originalUrl = requestDto.getOriginalUrl();
        
        // 이미 단축된 URL이 있는지 확인
        return urlRepository.findByOriginalUrl(originalUrl)
            .map(existingUrl -> {
                log.info("기존 단축 URL 반환: {}", existingUrl.getShortCode());
                return UrlResponseDto.from(existingUrl, BASE_URL);
            })
            .orElseGet(() -> {
                // 새로운 단축 URL 생성
                String shortCode = generateUniqueShortCode();
                Url url = new Url();
                url.setOriginalUrl(originalUrl);
                url.setShortCode(shortCode);
                
                Url savedUrl = urlRepository.save(url);
                log.info("새로운 단축 URL 생성: {} -> {}", originalUrl, shortCode);
                
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
        
        log.info("단축 URL 접근: {} -> {} (클릭 수: {})", shortCode, url.getOriginalUrl(), url.getClickCount());
        
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
     * 고유한 단축 코드 생성
     */
    private String generateUniqueShortCode() {
        SecureRandom random = new SecureRandom();
        String shortCode;
        
        do {
            StringBuilder sb = new StringBuilder(SHORT_CODE_LENGTH);
            for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
                sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
            }
            shortCode = sb.toString();
        } while (urlRepository.existsByShortCode(shortCode));
        
        return shortCode;
    }
} 