package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "urls")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Url {
    
    /**
     * Snowflake ID를 Primary Key로 사용
     * auto-increment 대신 Snowflake 알고리즘으로 생성되는 고유 ID
     */
    @Id
    private Long id;
    
    @Column(nullable = false, length = 2000)
    private String originalUrl;
    
    @Column(nullable = false, unique = true, length = 7)
    private String shortCode;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long clickCount;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (clickCount == null) {
            clickCount = 0L;
        }
    }
    
    /**
     * Snowflake ID 기반 Primary Key 설정
     */
    public void setSnowflakeId(Long snowflakeId) {
        this.id = snowflakeId;
    }
    
    /**
     * Snowflake ID 조회 (id와 동일)
     */
    public Long getSnowflakeId() {
        return this.id;
    }
} 