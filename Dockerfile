# 가벼운 Alpine 기반 Java 17 런타임 이미지 사용
FROM eclipse-temurin:17-jre-alpine

# 시간대 설정
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

# 작업 디렉토리 설정
WORKDIR /app

# 로컬에서 빌드된 jar 파일 복사
COPY build/libs/short-url-0.0.1-SNAPSHOT.jar app.jar

# 포트 8080 노출
EXPOSE 8080

# 애플리케이션 실행
CMD ["java", "-jar", "app.jar"] 