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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UrlService {
    
    private final UrlRepository urlRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Base62Encoder base62Encoder;
    private final RedisUrlCacheService redisUrlCacheService;  // Redis 캐시 서비스 추가
    private final RedisTemplate<String, Url> redisTemplate; // RedisTemplate 추가
    private static final String BASE_URL = "http://localhost:8080";
    private static final String CACHE_KEY_PREFIX = "url:"; // 캐시 키 접두사
    
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
                
                // 기존 URL을 캐시에 저장
                redisUrlCacheService.cacheUrl(existingUrl);
                
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
                
                // 새로 생성된 URL을 캐시에 저장
                redisUrlCacheService.cacheUrl(savedUrl);
                
                log.info("새로운 단축 URL 생성: {} -> {} (Snowflake ID: {})", 
                        originalUrl, shortCode, snowflakeId);
                
                return UrlResponseDto.from(savedUrl, BASE_URL);
            });
    }
    
    /**
     * 단축 URL로 원본 URL 조회 및 클릭 수 증가 (Redis 캐시 우선 사용)
     */
    public String getOriginalUrl(String shortCode) {
        log.debug("🔍 단축 URL 조회 시작: {}", shortCode);
        
        // 1. Redis 캐시에서 먼저 조회
        Optional<Url> cachedUrl = redisUrlCacheService.getUrlByShortCode(shortCode);
        
        if (cachedUrl.isPresent()) {
            log.info("🎯 Redis 캐시에서 조회 성공: {}", shortCode);
            Url url = cachedUrl.get();
            
            // 🆕 실시간 만료 확인 및 캐시 무효화
            if (checkAndEvictIfExpired(shortCode, url)) {
                throw new IllegalArgumentException("만료된 단축 URL입니다: " + shortCode);
            }
            
            // 클릭 수 증가 (DB 업데이트)
            url.setClickCount(url.getClickCount() + 1);
            Url updatedUrl = urlRepository.save(url);
            
            // 캐시도 업데이트
            redisUrlCacheService.cacheUrl(updatedUrl);
            
            log.info("단축 URL 접근 (캐시): {} -> {} (클릭 수: {}, Snowflake ID: {})", 
                    shortCode, url.getOriginalUrl(), updatedUrl.getClickCount(), url.getId());
            
            return url.getOriginalUrl();
        }
        
        // 2. 캐시에 없으면 DB에서 조회
        log.info("❌ Redis 캐시 MISS, DB에서 조회: {}", shortCode);
        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 단축 URL입니다: " + shortCode));
        
        // 🆕 실시간 만료 확인 및 캐시 무효화
        if (checkAndEvictIfExpired(shortCode, url)) {
            throw new IllegalArgumentException("만료된 단축 URL입니다: " + shortCode);
        }
        
        // 클릭 수 증가
        url.setClickCount(url.getClickCount() + 1);
        Url updatedUrl = urlRepository.save(url);
        
        // DB에서 조회한 데이터를 캐시에 저장
        redisUrlCacheService.cacheUrl(updatedUrl);
        
        log.info("단축 URL 접근 (DB): {} -> {} (클릭 수: {}, Snowflake ID: {})", 
                shortCode, url.getOriginalUrl(), updatedUrl.getClickCount(), url.getId());
        
        return url.getOriginalUrl();
    }
    
    /**
     * 단축 URL 삭제 (데이터 불일치 문제 재현용)
     */
    @Transactional
    public boolean deleteUrl(String shortCode) {
        log.info("🗑️ 단축 URL 삭제 시작: {}", shortCode);
        
        Optional<Url> urlOptional = urlRepository.findByShortCode(shortCode);
        if (urlOptional.isEmpty()) {
            log.warn("❌ 삭제할 URL이 존재하지 않음: {}", shortCode);
            return false;
        }
        
        Url url = urlOptional.get();
        
        // 1. DB에서 삭제
        urlRepository.delete(url);
        log.info("✅ DB에서 삭제 완료: {}", shortCode);
        
        // 2. 캐시에서는 삭제하지 않음 (의도적으로 데이터 불일치 상황 생성)
        log.warn("⚠️ 캐시에서 삭제하지 않음 - 데이터 불일치 상황 생성: {}", shortCode);
        
        return true;
    }
    
    /**
     * 단축 URL 삭제 (올바른 방법 - 캐시도 함께 삭제)
     */
    @Transactional
    public boolean deleteUrlProperly(String shortCode) {
        log.info("🗑️ 단축 URL 올바른 삭제 시작: {}", shortCode);
        
        Optional<Url> urlOptional = urlRepository.findByShortCode(shortCode);
        if (urlOptional.isEmpty()) {
            log.warn("❌ 삭제할 URL이 존재하지 않음: {}", shortCode);
            return false;
        }
        
        Url url = urlOptional.get();
        
        // 1. DB에서 삭제
        urlRepository.delete(url);
        log.info("✅ DB에서 삭제 완료: {}", shortCode);
        
        // 2. Pub/Sub으로 캐시 무효화 메시지 발행
        redisUrlCacheService.publishCacheEviction(shortCode);
        log.info("✅ Pub/Sub 캐시 무효화 메시지 발행 완료: {}", shortCode);
        
        return true;
    }
    
    /**
     * URL 만료 처리 (올바른 방법 - Pub/Sub 사용)
     */
    @Transactional
    public boolean expireUrl(String shortCode) {
        log.info("⏰ URL 만료 처리 시작: {}", shortCode);
        
        Optional<Url> urlOptional = urlRepository.findByShortCode(shortCode);
        if (urlOptional.isEmpty()) {
            log.warn("❌ 만료시킬 URL이 존재하지 않음: {}", shortCode);
            return false;
        }
        
        Url url = urlOptional.get();
        
        // 1. DB에서 만료 시간 설정 (현재 시간으로 설정하여 즉시 만료)
        url.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        urlRepository.save(url);
        log.info("✅ DB에서 만료 처리 완료: {}", shortCode);
        
        // 2. Pub/Sub으로 캐시 무효화 메시지 발행
        redisUrlCacheService.publishCacheEviction(shortCode);
        log.info("✅ Pub/Sub 캐시 무효화 메시지 발행 완료: {}", shortCode);
        
        return true;
    }
    
    /**
     * 만료된 URL 시뮬레이션 (데이터 불일치 문제 재현용)
     */
    @Transactional
    public boolean simulateExpiredUrl(String shortCode) {
        log.info("⏰ URL 만료 시뮬레이션 시작: {}", shortCode);
        
        Optional<Url> urlOptional = urlRepository.findByShortCode(shortCode);
        if (urlOptional.isEmpty()) {
            log.warn("❌ 만료시킬 URL이 존재하지 않음: {}", shortCode);
            return false;
        }
        
        Url url = urlOptional.get();
        
        // 1. DB에서 URL을 "만료됨"으로 표시 (originalUrl을 특별한 값으로 변경)
        url.setOriginalUrl("EXPIRED_URL");
        urlRepository.save(url);
        log.info("✅ DB에서 만료 처리 완료: {}", shortCode);
        
        // 2. 캐시는 그대로 두어서 데이터 불일치 상황 생성
        log.warn("⚠️ 캐시는 그대로 둠 - 데이터 불일치 상황 생성: {}", shortCode);
        
        return true;
    }
    
    /**
     * 만료된 URL 정리 (배치 작업용 - Pub/Sub 사용)
     */
    @Transactional
    public void cleanupExpiredUrls() {
        log.info("🧹 만료된 URL 정리 시작");
        
        // 만료된 URL 조회
        List<Url> expiredUrls = urlRepository.findAll().stream()
            .filter(Url::isExpired)
            .collect(Collectors.toList());
        
        log.info("만료된 URL 개수: {}", expiredUrls.size());
        
        for (Url expiredUrl : expiredUrls) {
            // Pub/Sub으로 캐시 무효화 메시지 발행
            redisUrlCacheService.publishCacheEviction(expiredUrl.getShortCode());
            log.info("만료된 URL 캐시 무효화 메시지 발행: {}", expiredUrl.getShortCode());
        }
        
        log.info("🧹 만료된 URL 정리 완료");
    }
    
    /**
     * 🆕 자동 만료 감지 및 캐시 무효화 (스케줄러)
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void autoDetectAndEvictExpiredUrls() {
        try {
            log.info("🕐 자동 만료 감지 스케줄러 시작");
            
            // 1. DB에서 만료된 URL들 조회
            List<Url> expiredUrls = urlRepository.findAll().stream()
                .filter(Url::isExpired)
                .collect(Collectors.toList());
            
            if (expiredUrls.isEmpty()) {
                log.debug("✅ 만료된 URL이 없습니다");
                return;
            }
            
            log.info("🔍 만료된 URL {} 개 발견", expiredUrls.size());
            
            // 2. 만료된 URL들의 캐시 무효화
            for (Url expiredUrl : expiredUrls) {
                String shortCode = expiredUrl.getShortCode();
                
                // 캐시에 해당 URL이 있는지 확인
                Optional<Url> cachedUrl = redisUrlCacheService.getUrlByShortCode(shortCode);
                if (cachedUrl.isPresent()) {
                    // 캐시에 있으면 Pub/Sub으로 무효화
                    redisUrlCacheService.publishCacheEviction(shortCode);
                    log.info("📤 만료된 URL 캐시 무효화: shortCode={}, expiresAt={}", 
                            shortCode, expiredUrl.getExpiresAt());
                }
            }
            
            log.info("✅ 자동 만료 감지 완료: {}개 URL 처리", expiredUrls.size());
            
        } catch (Exception e) {
            log.error("❌ 자동 만료 감지 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 🆕 캐시와 DB 간 만료 상태 불일치 감지 및 수정
     */
    @Scheduled(fixedRate = 300000) // 5분마다 실행
    public void detectAndFixExpirationInconsistency() {
        try {
            log.info("🔍 캐시-DB 만료 상태 불일치 감지 시작");
            
            // 캐시에 있는 모든 URL 키들 조회
            Set<String> cacheKeys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
            if (cacheKeys == null || cacheKeys.isEmpty()) {
                log.debug("✅ 캐시가 비어있습니다");
                return;
            }
            
            int inconsistencyCount = 0;
            
            for (String cacheKey : cacheKeys) {
                try {
                    String shortCode = cacheKey.replace(CACHE_KEY_PREFIX, "");
                    
                    // 캐시에서 URL 조회
                    Optional<Url> cachedUrl = redisUrlCacheService.getUrlByShortCode(shortCode);
                    if (cachedUrl.isEmpty()) {
                        continue; // 캐시에서 조회 실패
                    }
                    
                    // DB에서 URL 조회
                    Optional<Url> dbUrl = urlRepository.findByShortCode(shortCode);
                    if (dbUrl.isEmpty()) {
                        // DB에는 없는데 캐시에는 있음 - 캐시 무효화
                        redisUrlCacheService.publishCacheEviction(shortCode);
                        inconsistencyCount++;
                        log.warn("🔧 불일치 수정: DB에서 삭제된 URL의 캐시 무효화 - shortCode={}", shortCode);
                        continue;
                    }
                    
                    // 둘 다 있는 경우 만료 상태 비교
                    Url cached = cachedUrl.get();
                    Url db = dbUrl.get();
                    
                    if (!cached.isExpired() && db.isExpired()) {
                        // 캐시는 유효하지만 DB는 만료됨 - 캐시 무효화
                        redisUrlCacheService.publishCacheEviction(shortCode);
                        inconsistencyCount++;
                        log.warn("🔧 불일치 수정: DB에서 만료된 URL의 캐시 무효화 - shortCode={}, dbExpiresAt={}", 
                                shortCode, db.getExpiresAt());
                    }
                    
                } catch (Exception e) {
                    log.warn("캐시 키 처리 실패: key={}, error={}", cacheKey, e.getMessage());
                }
            }
            
            log.info("✅ 캐시-DB 만료 상태 불일치 감지 완료: {}개 불일치 수정", inconsistencyCount);
            
        } catch (Exception e) {
            log.error("❌ 캐시-DB 만료 상태 불일치 감지 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 🆕 실시간 만료 확인 및 캐시 무효화 (요청 시점)
     */
    private boolean checkAndEvictIfExpired(String shortCode, Url url) {
        if (url.isExpired()) {
            log.warn("⏰ 실시간 만료 감지: shortCode={}, expiresAt={}", shortCode, url.getExpiresAt());
            
            // 즉시 캐시 무효화
            redisUrlCacheService.publishCacheEviction(shortCode);
            
            return true; // 만료됨
        }
        return false; // 유효함
    }
    
    /**
     * 캐시와 DB 데이터 일치성 검증
     */
    public boolean validateCacheConsistency(String shortCode) {
        log.info("🔍 캐시-DB 일치성 검증 시작: {}", shortCode);
        
        // 캐시에서 조회
        Optional<Url> cachedUrl = redisUrlCacheService.getUrlByShortCode(shortCode);
        
        // DB에서 조회
        Optional<Url> dbUrl = urlRepository.findByShortCode(shortCode);
        
        if (cachedUrl.isEmpty() && dbUrl.isEmpty()) {
            log.info("✅ 일치성 검증 통과: 캐시와 DB 모두 데이터 없음 - {}", shortCode);
            return true;
        }
        
        if (cachedUrl.isPresent() && dbUrl.isEmpty()) {
            log.error("❌ 일치성 검증 실패: 캐시에는 있지만 DB에는 없음 - {}", shortCode);
            log.error("   캐시 데이터: {}", cachedUrl.get().getOriginalUrl());
            return false;
        }
        
        if (cachedUrl.isEmpty() && dbUrl.isPresent()) {
            log.warn("⚠️ 캐시 MISS: 캐시에는 없지만 DB에는 있음 - {}", shortCode);
            log.warn("   DB 데이터: {}", dbUrl.get().getOriginalUrl());
            return true; // 이건 문제가 아님 (캐시 MISS)
        }
        
        // 둘 다 있는 경우 내용 비교
        Url cached = cachedUrl.get();
        Url db = dbUrl.get();
        
        boolean isConsistent = cached.getOriginalUrl().equals(db.getOriginalUrl()) &&
                              cached.getShortCode().equals(db.getShortCode()) &&
                              cached.getId().equals(db.getId());
        
        if (isConsistent) {
            log.info("✅ 일치성 검증 통과: 캐시와 DB 데이터 일치 - {}", shortCode);
        } else {
            log.error("❌ 일치성 검증 실패: 캐시와 DB 데이터 불일치 - {}", shortCode);
            log.error("   캐시 데이터: {}", cached.getOriginalUrl());
            log.error("   DB 데이터: {}", db.getOriginalUrl());
        }
        
        return isConsistent;
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