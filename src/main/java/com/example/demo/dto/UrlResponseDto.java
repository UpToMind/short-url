package com.example.demo.dto;

import com.example.demo.entity.Url;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlResponseDto {
    
    private Long id;
    private String originalUrl;
    private String shortCode;
    private String shortUrl;
    private LocalDateTime createdAt;
    private Long clickCount;
    
    public static UrlResponseDto from(Url url, String baseUrl) {
        return new UrlResponseDto(
            url.getId(),
            url.getOriginalUrl(),
            url.getShortCode(),
            baseUrl + "/" + url.getShortCode(),
            url.getCreatedAt(),
            url.getClickCount()
        );
    }
} 