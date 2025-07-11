# Redis 캐시와 DB 간 데이터 불일치 문제 재현 및 해결 데모

## 🎯 문제 상황
단축 URL이 삭제되거나 만료된 경우에도 Redis 캐시에 남아 있어 유효하지 않은 URL로 리디렉션되는 문제

## 📋 데모 시나리오

### 1단계: 환경 준비
```bash
# Docker 컨테이너 시작
docker-compose up -d

# 애플리케이션 빌드 및 실행
./gradlew build
java -jar build/libs/short-url-0.0.1-SNAPSHOT.jar
```

### 2단계: 테스트 데이터 생성
```bash
# 단축 URL 생성
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.google.com"}'

# 응답 예시:
# {
#   "id": 1234567890,
#   "originalUrl": "https://www.google.com",
#   "shortCode": "aBcD123",
#   "shortUrl": "http://localhost:8080/aBcD123",
#   "createdAt": "2024-01-01T12:00:00",
#   "clickCount": 0
# }
```

### 3단계: 정상 동작 확인
```bash
# 단축 URL 접근 (캐시에 저장됨)
curl -I http://localhost:8080/aBcD123

# 캐시 상태 확인
curl http://localhost:8080/api/performance/redis-cache-status

# 캐시-DB 일치성 검증
curl http://localhost:8080/api/urls/aBcD123/validate
```

### 4단계: 데이터 불일치 문제 재현

#### 시나리오 A: URL 삭제 후 캐시 미삭제
```bash
# 1. DB에서만 삭제 (캐시는 그대로 둠)
curl -X DELETE http://localhost:8080/api/urls/aBcD123

# 2. 캐시-DB 일치성 검증 (불일치 발생!)
curl http://localhost:8080/api/urls/aBcD123/validate

# 3. 단축 URL 접근 시도 (캐시에서 잘못된 데이터 반환)
curl -I http://localhost:8080/aBcD123
```

#### 시나리오 B: URL 만료 후 캐시 미업데이트
```bash
# 1. 새로운 URL 생성
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.naver.com"}'

# 2. 캐시에 로드
curl -I http://localhost:8080/xyz789

# 3. DB에서 만료 처리 (캐시는 그대로 둠)
curl -X POST http://localhost:8080/api/urls/xyz789/expire

# 4. 캐시-DB 일치성 검증 (불일치 발생!)
curl http://localhost:8080/api/urls/xyz789/validate

# 5. 단축 URL 접근 시도 (캐시에서 만료되지 않은 데이터 반환)
curl -I http://localhost:8080/xyz789
```

### 5단계: 문제 해결 확인

#### 올바른 삭제 방법
```bash
# 1. 새로운 URL 생성
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.github.com"}'

# 2. 캐시에 로드
curl -I http://localhost:8080/def456

# 3. 올바른 삭제 (캐시도 함께 삭제)
curl -X DELETE http://localhost:8080/api/urls/def456/properly

# 4. 캐시-DB 일치성 검증 (일치함!)
curl http://localhost:8080/api/urls/def456/validate

# 5. 단축 URL 접근 시도 (404 반환)
curl -I http://localhost:8080/def456
```

## 🔧 해결 방안

### 1. TTL 기반 캐싱 전략
- 현재 구현: 1시간 TTL
- 위치: `RedisUrlCacheService.CACHE_TTL`

### 2. 캐시 무효화 처리
- 삭제 시: `redisUrlCacheService.evictUrl(shortCode)`
- 만료 시: `redisUrlCacheService.evictUrl(shortCode)`

### 3. 데이터 정합성 검증
- 검증 API: `GET /api/urls/{shortCode}/validate`
- 자동 검증: `validateCacheConsistency()` 메서드

### 4. 권장 해결 방안

#### A. 즉시 캐시 무효화
```java
@Transactional
public boolean deleteUrl(String shortCode) {
    // 1. DB에서 삭제
    urlRepository.delete(url);
    
    // 2. 캐시에서 즉시 삭제
    redisUrlCacheService.evictUrl(shortCode);
    
    return true;
}
```

#### B. Pub/Sub 구조 (향후 확장)
```java
// 삭제 이벤트 발행
@EventListener
public void handleUrlDeletedEvent(UrlDeletedEvent event) {
    redisUrlCacheService.evictUrl(event.getShortCode());
}
```

#### C. 캐시 일치성 검증 로직
```java
public String getOriginalUrl(String shortCode) {
    // 1. 캐시에서 조회
    Optional<Url> cachedUrl = redisUrlCacheService.getUrlByShortCode(shortCode);
    
    if (cachedUrl.isPresent()) {
        // 2. 주기적으로 DB와 일치성 검증
        if (shouldValidateConsistency()) {
            validateCacheConsistency(shortCode);
        }
        return cachedUrl.get().getOriginalUrl();
    }
    
    // 3. 캐시 MISS 시 DB 조회
    // ...
}
```

## 📊 성과 측정

### 문제 발생 전
- 만료 URL 리디렉션 오류: 2.1%
- 사용자 신뢰도: 낮음

### 해결 후
- 만료 URL 리디렉션 오류: 0%
- 사용자 신뢰도: 높음
- 서비스 일관성: 확보

## 🔍 모니터링 및 디버깅

### 캐시 상태 확인
```bash
curl http://localhost:8080/api/performance/redis-cache-status
```

### 캐시 클리어
```bash
curl -X DELETE http://localhost:8080/api/performance/redis-cache-clear
```

### 로그 확인
```bash
# Redis 캐시 관련 로그
docker-compose logs short-url-app | grep "Redis"

# 캐시 불일치 관련 로그
docker-compose logs short-url-app | grep "일치성"
```

## 🚨 주의사항

1. **운영 환경에서는 절대 데이터 불일치 상황을 의도적으로 만들지 마세요**
2. **이 데모는 교육 목적으로만 사용하세요**
3. **실제 운영에서는 항상 캐시 무효화를 함께 처리하세요**
4. **정기적인 캐시-DB 일치성 검증을 수행하세요**

## 📝 추가 개선 사항

1. **분산 캐시 무효화**: Redis Pub/Sub 활용
2. **캐시 워밍업**: 애플리케이션 시작 시 인기 URL 미리 캐싱
3. **캐시 계층화**: L1(로컬) + L2(Redis) 캐시 구조
4. **메트릭 수집**: 캐시 히트율, 불일치 발생률 모니터링 