package com.example.demo.repository;

import com.example.demo.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

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
    
    /**
     * 중복된 original URL 찾기 (2개 이상 존재하는 URL들)
     * @return [originalUrl, count] 형태의 Object[] 리스트
     */
    @Query("SELECT u.originalUrl, COUNT(u) FROM Url u " +
           "GROUP BY u.originalUrl " +
           "HAVING COUNT(u) > 1 " +
           "ORDER BY COUNT(u) DESC")
    List<Object[]> findDuplicateOriginalUrls();
    
    /**
     * 중복된 shortCode 찾기 (데이터 무결성 검사용)
     * @return [shortCode, count] 형태의 Object[] 리스트
     */
    @Query("SELECT u.shortCode, COUNT(u) FROM Url u " +
           "GROUP BY u.shortCode " +
           "HAVING COUNT(u) > 1 " +
           "ORDER BY COUNT(u) DESC")
    List<Object[]> findDuplicateShortCodes();
    
    /**
     * 중복된 ID 찾기 (데이터 무결성 검사용)
     * Snowflake ID가 Primary Key이므로 이론적으로 중복 불가능하지만 안전장치
     * @return [id, count] 형태의 Object[] 리스트
     */
    @Query("SELECT u.id, COUNT(u) FROM Url u " +
           "GROUP BY u.id " +
           "HAVING COUNT(u) > 1 " +
           "ORDER BY COUNT(u) DESC")
    List<Object[]> findDuplicateIds();
    
    /**
     * 특정 original URL의 모든 중복 레코드 조회
     * @param originalUrl 검색할 원본 URL
     * @return 해당 URL의 모든 레코드 리스트
     */
    List<Url> findAllByOriginalUrl(String originalUrl);
} 