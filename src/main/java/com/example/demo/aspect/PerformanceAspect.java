package com.example.demo.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class PerformanceAspect {

    /**
     * Repository의 모든 메서드 실행 시간 측정
     */
    @Around("execution(* com.example.demo.repository.*.*(..))")
    public Object measureRepositoryPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        
        try {
            Object result = joinPoint.proceed();
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;
            
            String methodName = joinPoint.getSignature().getName();
            String className = joinPoint.getTarget().getClass().getSimpleName();
            
            log.info("🗄️ DB 쿼리 성능 - {}.{}: {}ms", className, methodName, executionTimeMs);
            
            return result;
        } catch (Exception e) {
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;
            log.error("❌ DB 쿼리 실패 - 소요시간: {}ms, 오류: {}", executionTimeMs, e.getMessage());
            throw e;
        }
    }

    /**
     * 특정 서비스 메서드 성능 측정
     */
    @Around("execution(* com.example.demo.service.UrlService.getOriginalUrl(..))")
    public Object measureUrlLookupPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        Object[] args = joinPoint.getArgs();
        String shortCode = (String) args[0];
        
        try {
            Object result = joinPoint.proceed();
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;
            
            log.info("🚀 URL 조회 전체 성능 - shortCode: {}, 총 소요시간: {}ms", shortCode, executionTimeMs);
            
            return result;
        } catch (Exception e) {
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;
            log.error("❌ URL 조회 실패 - shortCode: {}, 소요시간: {}ms, 오류: {}", 
                     shortCode, executionTimeMs, e.getMessage());
            throw e;
        }
    }
} 