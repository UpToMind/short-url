package com.example.demo.util;

import org.springframework.stereotype.Component;

/**
 * Base62 인코딩/디코딩 유틸리티
 * 
 * Base62는 0-9, A-Z, a-z 총 62개의 문자를 사용하여 숫자를 인코딩
 * URL에 안전하고 읽기 쉬운 문자열을 생성하는 데 적합
 */
@Component
public class Base62Encoder {
    
    // Base62 문자셋 (0-9, A-Z, a-z)
    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;
    private static final int MIN_LENGTH = 7; // 정확히 7자리로 고정
    
    /**
     * 숫자를 Base62 문자열로 인코딩
     * 
     * @param number 인코딩할 숫자
     * @return Base62 인코딩된 문자열 (최소 6자리)
     */
    public String encode(long number) {
        if (number == 0) {
            return padToMinLength("0");
        }
        
        StringBuilder sb = new StringBuilder();
        long num = Math.abs(number); // 음수 처리
        
        while (num > 0) {
            sb.append(BASE62_CHARS.charAt((int)(num % BASE)));
            num /= BASE;
        }
        
        return padToMinLength(sb.reverse().toString());
    }
    
    /**
     * Base62 문자열을 숫자로 디코딩
     * 
     * @param encoded Base62 인코딩된 문자열
     * @return 디코딩된 숫자
     * @throws IllegalArgumentException 잘못된 문자가 포함된 경우
     */
    public long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("인코딩된 문자열이 null이거나 비어있습니다");
        }
        
        long result = 0;
        long power = 1;
        
        // 뒤에서부터 처리
        for (int i = encoded.length() - 1; i >= 0; i--) {
            char c = encoded.charAt(i);
            int index = BASE62_CHARS.indexOf(c);
            
            if (index == -1) {
                throw new IllegalArgumentException("잘못된 Base62 문자: " + c);
            }
            
            result += index * power;
            power *= BASE;
        }
        
        return result;
    }
    
    /**
     * 최소 길이를 보장하기 위해 앞에 0을 패딩
     * 
     * @param encoded 인코딩된 문자열
     * @return 패딩된 문자열
     */
    private String padToMinLength(String encoded) {
        if (encoded.length() >= MIN_LENGTH) {
            return encoded;
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MIN_LENGTH - encoded.length(); i++) {
            sb.append('0');
        }
        sb.append(encoded);
        
        return sb.toString();
    }
    
    /**
     * 문자열이 유효한 Base62 인코딩인지 확인
     * 
     * @param encoded 확인할 문자열
     * @return 유효하면 true, 그렇지 않으면 false
     */
    public boolean isValidBase62(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }
        
        for (char c : encoded.toCharArray()) {
            if (BASE62_CHARS.indexOf(c) == -1) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Snowflake ID를 7자리 Base62 shortCode로 변환
     * 
     * @param snowflakeId Snowflake ID
     * @return 정확히 7자리 Base62 shortCode
     */
    public String generateShortCode(long snowflakeId) {
        // Snowflake ID의 하위 32비트를 사용하여 Base62 인코딩
        long truncatedId = snowflakeId & 0xFFFFFFFFL; // 하위 32비트만 사용
        
        // 추가적인 해싱을 통해 더 균등한 분포 보장
        truncatedId = hash(truncatedId);
        
        // Base62 인코딩 후 정확히 7자리로 조정
        String encoded = encode(truncatedId);
        
        // 7자리보다 길면 앞의 7자리만 사용
        if (encoded.length() > 7) {
            encoded = encoded.substring(0, 7);
        }
        // 7자리보다 짧으면 앞에 0을 패딩
        else if (encoded.length() < 7) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7 - encoded.length(); i++) {
                sb.append('0');
            }
            sb.append(encoded);
            encoded = sb.toString();
        }
        
        return encoded;
    }
    
    /**
     * 간단한 해시 함수 (더 균등한 분포를 위해)
     * 
     * @param value 해시할 값
     * @return 해시된 값
     */
    private long hash(long value) {
        // FNV-1a 해시의 간단한 버전
        long hash = 2166136261L;
        for (int i = 0; i < 8; i++) {
            hash ^= (value >>> (i * 8)) & 0xFF;
            hash *= 16777619L;
        }
        return Math.abs(hash);
    }
    
    /**
     * 테스트용 메서드: 인코딩/디코딩 테스트
     */
    public void testEncodeDecode() {
        long[] testNumbers = {0, 1, 61, 62, 3843, 238327, 14776335, 916132831};
        
        System.out.println("=== Base62 인코딩/디코딩 테스트 ===");
        for (long num : testNumbers) {
            String encoded = encode(num);
            long decoded = decode(encoded);
            System.out.printf("숫자: %d -> 인코딩: %s -> 디코딩: %d (일치: %s)%n", 
                            num, encoded, decoded, num == decoded ? "✓" : "✗");
        }
    }
} 