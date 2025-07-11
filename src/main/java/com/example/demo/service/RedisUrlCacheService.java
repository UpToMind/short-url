package com.example.demo.service;

import com.example.demo.entity.Url;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisUrlCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    
    // 캐시 키 접두사
    private static final String CACHE_KEY_PREFIX = "url:";
    
    // 캐시 TTL (Time To Live) - 1시간
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * shortCode로 URL 캐시에서 조회
     */
    public Optional<Url> getUrlByShortCode(String shortCode) {
        try {
            String cacheKey = CACHE_KEY_PREFIX + shortCode;
            Object cachedUrl = redisTemplate.opsForValue().get(cacheKey);
            
            if (cachedUrl != null) {
                log.debug("🎯 Redis 캐시 HIT: {} (키: {})", shortCode, cacheKey);
                return Optional.of((Url) cachedUrl);
            } else {
                log.debug("❌ Redis 캐시 MISS: {} (키: {})", shortCode, cacheKey);
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Redis 캐시 조회 실패: shortCode={}, error={}", shortCode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * URL을 Redis 캐시에 저장
     */
    public void cacheUrl(Url url) {
        try {
            String cacheKey = CACHE_KEY_PREFIX + url.getShortCode();
            redisTemplate.opsForValue().set(cacheKey, url, CACHE_TTL);
            log.debug("💾 Redis 캐시 저장: {} (키: {}, TTL: {})", url.getShortCode(), cacheKey, CACHE_TTL);
            
            // 저장 후 즉시 확인
            Object verification = redisTemplate.opsForValue().get(cacheKey);
            if (verification != null) {
                log.debug("✅ Redis 캐시 저장 확인: {} 성공", url.getShortCode());
            } else {
                log.warn("⚠️ Redis 캐시 저장 확인: {} 실패", url.getShortCode());
            }
        } catch (Exception e) {
            log.warn("Redis 캐시 저장 실패: shortCode={}, error={}", url.getShortCode(), e.getMessage());
        }
    }

    /**
     * 캐시에서 URL 삭제
     */
    public void evictUrl(String shortCode) {
        try {
            String cacheKey = CACHE_KEY_PREFIX + shortCode;
            Boolean deleted = redisTemplate.delete(cacheKey);
            log.debug("🗑️ Redis 캐시 삭제: {} (키: {}, 결과: {})", shortCode, cacheKey, deleted);
        } catch (Exception e) {
            log.warn("Redis 캐시 삭제 실패: shortCode={}, error={}", shortCode, e.getMessage());
        }
    }

    /**
     * 전체 캐시 클리어
     */
    public void clearAllCache() {
        try {
            String pattern = CACHE_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keys);
                log.info("🧹 Redis 캐시 전체 클리어 완료: {}개 키 삭제", deletedCount);
            } else {
                log.info("🧹 Redis 캐시 전체 클리어: 삭제할 키가 없음");
            }
        } catch (Exception e) {
            log.warn("Redis 캐시 클리어 실패: {}", e.getMessage());
        }
    }

    /**
     * 캐시 통계 조회
     */
    public long getCacheSize() {
        try {
            String pattern = CACHE_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            long size = keys != null ? keys.size() : 0;
            log.debug("📊 Redis 캐시 크기 조회: {}개 키", size);
            return size;
        } catch (Exception e) {
            log.warn("Redis 캐시 크기 조회 실패: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Redis 연결 상태 확인
     */
    public boolean isRedisConnected() {
        try {
            redisTemplate.opsForValue().set("connection:test", "test", Duration.ofSeconds(1));
            String result = (String) redisTemplate.opsForValue().get("connection:test");
            boolean connected = "test".equals(result);
            log.debug("🔗 Redis 연결 상태 확인: {}", connected ? "연결됨" : "연결 안됨");
            return connected;
        } catch (Exception e) {
            log.warn("Redis 연결 확인 실패: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 캐시 상세 정보 조회 (디버깅용)
     */
    public Map<String, Object> getCacheInfo() {
        try {
            String pattern = CACHE_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            Map<String, Object> info = new HashMap<>();
            
            info.put("total_keys", keys != null ? keys.size() : 0);
            info.put("pattern", pattern);
            info.put("cache_prefix", CACHE_KEY_PREFIX);
            info.put("cache_ttl", CACHE_TTL.toString());
            info.put("redis_connected", isRedisConnected());
            
            if (keys != null && !keys.isEmpty()) {
                // 샘플 키 몇 개 조회
                List<String> sampleKeys = keys.stream().limit(5).collect(Collectors.toList());
                info.put("sample_keys", sampleKeys);
            }
            
            return info;
        } catch (Exception e) {
            log.warn("Redis 캐시 정보 조회 실패: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
} 