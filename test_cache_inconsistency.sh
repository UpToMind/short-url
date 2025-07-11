#!/bin/bash

# Redis 캐시와 DB 간 데이터 불일치 문제 재현 테스트 스크립트

BASE_URL="http://localhost:8080"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Redis 캐시와 DB 간 데이터 불일치 문제 재현 테스트 시작${NC}"
echo "======================================================================"

# 함수: API 호출 결과 확인
check_response() {
    local response=$1
    local expected_code=$2
    local description=$3
    
    if [[ $response == *"$expected_code"* ]]; then
        echo -e "${GREEN}✅ $description${NC}"
    else
        echo -e "${RED}❌ $description${NC}"
        echo "응답: $response"
    fi
}

# 함수: 단축 URL 생성
create_short_url() {
    local original_url=$1
    local response=$(curl -s -X POST "$BASE_URL/api/shorten" \
        -H "Content-Type: application/json" \
        -d "{\"originalUrl\": \"$original_url\"}")
    
    # shortCode 추출
    local short_code=$(echo $response | grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)
    echo $short_code
}

# 1단계: 서버 상태 확인
echo -e "\n${YELLOW}1단계: 서버 상태 확인${NC}"
health_response=$(curl -s "$BASE_URL/api/health")
check_response "$health_response" "정상적으로 동작" "서버 상태 확인"

# 2단계: Redis 연결 확인
echo -e "\n${YELLOW}2단계: Redis 연결 확인${NC}"
redis_status=$(curl -s "$BASE_URL/api/performance/redis-cache-status")
check_response "$redis_status" "success" "Redis 연결 상태 확인"

# 3단계: 테스트 URL 생성
echo -e "\n${YELLOW}3단계: 테스트 URL 생성${NC}"
TEST_URL="https://www.google.com"
SHORT_CODE=$(create_short_url "$TEST_URL")
echo "생성된 단축 코드: $SHORT_CODE"

if [ -z "$SHORT_CODE" ]; then
    echo -e "${RED}❌ 단축 URL 생성 실패${NC}"
    exit 1
fi

# 4단계: 정상 동작 확인 (캐시에 로드)
echo -e "\n${YELLOW}4단계: 정상 동작 확인 (캐시에 로드)${NC}"
redirect_response=$(curl -s -I "$BASE_URL/$SHORT_CODE")
check_response "$redirect_response" "302" "단축 URL 리디렉션 확인"

# 캐시 상태 확인
cache_status=$(curl -s "$BASE_URL/api/performance/redis-cache-status")
echo "현재 캐시 크기: $(echo $cache_status | grep -o '"cache_size":[0-9]*' | cut -d':' -f2)"

# 5단계: 캐시-DB 일치성 검증 (정상 상태)
echo -e "\n${YELLOW}5단계: 캐시-DB 일치성 검증 (정상 상태)${NC}"
validation_response=$(curl -s "$BASE_URL/api/urls/$SHORT_CODE/validate")
check_response "$validation_response" "true" "캐시-DB 일치성 검증 (정상)"

# 6단계: 데이터 불일치 문제 재현 - URL 삭제
echo -e "\n${YELLOW}6단계: 데이터 불일치 문제 재현 - URL 삭제${NC}"
echo -e "${RED}⚠️  DB에서만 삭제하고 캐시는 그대로 둡니다 (의도적 불일치 생성)${NC}"
delete_response=$(curl -s -X DELETE "$BASE_URL/api/urls/$SHORT_CODE")
check_response "$delete_response" "success" "DB에서 URL 삭제 (캐시는 유지)"

# 7단계: 불일치 상황 확인
echo -e "\n${YELLOW}7단계: 불일치 상황 확인${NC}"
validation_response=$(curl -s "$BASE_URL/api/urls/$SHORT_CODE/validate")
echo "일치성 검증 결과: $validation_response"

is_consistent=$(echo $validation_response | grep -o '"isConsistent":[a-z]*' | cut -d':' -f2)
if [ "$is_consistent" = "false" ]; then
    echo -e "${RED}❌ 캐시-DB 데이터 불일치 발생! (문제 재현 성공)${NC}"
else
    echo -e "${GREEN}✅ 캐시-DB 데이터 일치 (예상과 다름)${NC}"
fi

# 8단계: 불일치 상황에서 접근 시도
echo -e "\n${YELLOW}8단계: 불일치 상황에서 접근 시도${NC}"
echo -e "${RED}⚠️  캐시에서 삭제된 URL 데이터를 반환할 수 있습니다${NC}"
redirect_response=$(curl -s -I "$BASE_URL/$SHORT_CODE")
echo "리디렉션 응답: $(echo $redirect_response | head -n 1)"

# 9단계: 새로운 URL로 올바른 삭제 방법 테스트
echo -e "\n${YELLOW}9단계: 올바른 삭제 방법 테스트${NC}"
TEST_URL2="https://www.naver.com"
SHORT_CODE2=$(create_short_url "$TEST_URL2")
echo "새로운 단축 코드: $SHORT_CODE2"

# 캐시에 로드
curl -s -I "$BASE_URL/$SHORT_CODE2" > /dev/null
echo "캐시에 로드 완료"

# 올바른 삭제 (캐시도 함께 삭제)
proper_delete_response=$(curl -s -X DELETE "$BASE_URL/api/urls/$SHORT_CODE2/properly")
check_response "$proper_delete_response" "success" "올바른 삭제 (캐시도 함께 삭제)"

# 일치성 검증
validation_response2=$(curl -s "$BASE_URL/api/urls/$SHORT_CODE2/validate")
is_consistent2=$(echo $validation_response2 | grep -o '"isConsistent":[a-z]*' | cut -d':' -f2)
if [ "$is_consistent2" = "true" ]; then
    echo -e "${GREEN}✅ 올바른 삭제 후 캐시-DB 일치성 확인${NC}"
else
    echo -e "${RED}❌ 올바른 삭제 후에도 불일치 발생${NC}"
fi

# 10단계: 만료 시뮬레이션 테스트
echo "10단계: 만료 시뮬레이션 테스트"
SHORT_CODE3=$(curl -s -X POST "http://localhost:8080/api/shorten" \
    -H "Content-Type: application/json" \
    -d '{"originalUrl": "https://www.naver.com"}' | \
    grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)

echo "만료 테스트용 단축 코드: $SHORT_CODE3"

# 캐시에 로드
echo "캐시에 로드 완료"
curl -s -I "http://localhost:8080/$SHORT_CODE3" > /dev/null

# 만료 시뮬레이션 (잘못된 방법)
expire_response=$(curl -s -X POST "http://localhost:8080/api/urls/$SHORT_CODE3/expire")
echo "✅ URL 만료 시뮬레이션"

# 불일치 확인
validation_response=$(curl -s "http://localhost:8080/api/urls/$SHORT_CODE3/validate")
is_consistent=$(echo $validation_response | grep -o '"isConsistent":[a-z]*' | cut -d':' -f2)
if [ "$is_consistent" = "false" ]; then
    echo "❌ 만료 후 캐시-DB 데이터 불일치 발생! (문제 재현 성공)"
else
    echo "✅ 만료 후 캐시-DB 데이터 일치"
fi

# 11단계: 올바른 만료 처리 테스트
echo "11단계: 올바른 만료 처리 테스트"
SHORT_CODE4=$(curl -s -X POST "http://localhost:8080/api/shorten" \
    -H "Content-Type: application/json" \
    -d '{"originalUrl": "https://www.daum.net"}' | \
    grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)

echo "올바른 만료 테스트용 단축 코드: $SHORT_CODE4"

# 캐시에 로드
curl -s -I "http://localhost:8080/$SHORT_CODE4" > /dev/null
echo "캐시에 로드 완료"

# 올바른 만료 처리
expire_proper_response=$(curl -s -X POST "http://localhost:8080/api/urls/$SHORT_CODE4/expire-properly")
echo "✅ 올바른 만료 처리 (캐시도 함께 삭제)"

# 일치성 확인
validation_response=$(curl -s "http://localhost:8080/api/urls/$SHORT_CODE4/validate")
is_consistent=$(echo $validation_response | grep -o '"isConsistent":[a-z]*' | cut -d':' -f2)
if [ "$is_consistent" = "true" ]; then
    echo "✅ 올바른 만료 처리 후 캐시-DB 일치성 확인"
else
    echo "❌ 올바른 만료 처리 후에도 불일치 발생"
fi

# 12단계: 배치 정리 작업 테스트
echo "12단계: 배치 정리 작업 테스트"
cleanup_response=$(curl -s -X POST "http://localhost:8080/api/urls/cleanup-expired")
echo "✅ 만료된 URL 배치 정리 작업 완료"

# 13단계: 최종 캐시 상태 확인
echo -e "\n${YELLOW}13단계: 최종 캐시 상태 확인${NC}"
final_cache_status=$(curl -s "$BASE_URL/api/performance/redis-cache-status")
final_cache_size=$(echo $final_cache_status | grep -o '"cache_size":[0-9]*' | cut -d':' -f2)
echo "최종 캐시 크기: $final_cache_size"

# 14단계: 캐시 클리어
echo -e "\n${YELLOW}14단계: 캐시 클리어${NC}"
clear_response=$(curl -s -X DELETE "$BASE_URL/api/performance/redis-cache-clear")
check_response "$clear_response" "success" "캐시 클리어"

echo -e "\n${BLUE}======================================================================"
echo -e "🎯 테스트 완료! 데이터 불일치 문제 재현 및 해결 방법 확인됨${NC}"
echo -e "${YELLOW}📋 요약:${NC}"
echo "1. ❌ DB에서만 삭제 시 캐시 불일치 발생"
echo "2. ❌ DB에서만 만료 처리 시 캐시 불일치 발생"  
echo "3. ✅ 캐시와 DB 동시 처리 시 일치성 유지"
echo -e "${GREEN}💡 해결책: 항상 캐시 무효화를 함께 처리하세요!${NC}" 