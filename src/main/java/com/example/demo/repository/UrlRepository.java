package com.example.demo.repository;

import com.example.demo.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    
    /**
     * 단축 코드로 URL 찾기
     */
    Optional<Url> findByShortCode(String shortCode);
    
    /**
     * 원본 URL로 기존 단축 URL 찾기 (중복 방지)
     */
    Optional<Url> findByOriginalUrl(String originalUrl);
    
    /**
     * 단축 코드 존재 여부 확인
     */
    boolean existsByShortCode(String shortCode);
} 