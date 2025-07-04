package com.example.demo.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
public class UrlRequestDto {
    
    @NotBlank(message = "URL은 필수입니다")
    @Pattern(regexp = "^https?://.*", message = "올바른 URL 형식이 아닙니다 (http:// 또는 https://로 시작해야 합니다)")
    private String originalUrl;
} 