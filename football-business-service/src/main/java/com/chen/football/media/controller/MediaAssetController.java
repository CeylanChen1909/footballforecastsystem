package com.chen.football.media.controller;

import com.chen.football.media.service.MediaAssetCacheService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/** Same-origin image proxy for allow-listed team crests and player portraits. */
@RestController
@RequestMapping("/api/media")
public class MediaAssetController {

    private final MediaAssetCacheService mediaAssetCacheService;

    public MediaAssetController(MediaAssetCacheService mediaAssetCacheService) {
        this.mediaAssetCacheService = mediaAssetCacheService;
    }

    @GetMapping(value = "/image", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam("url") String rawUrl, HttpServletRequest request) {
        URI validated = mediaAssetCacheService.validateUrl(rawUrl);
        if (validated == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        Optional<MediaAssetCacheService.MediaImage> loaded = mediaAssetCacheService.load(rawUrl);
        if (loaded.isEmpty()) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();

        MediaAssetCacheService.MediaImage image = loaded.get();
        String etag = image.etag();
        if (etag != null && !etag.isBlank() && etag.equals(request.getHeader(HttpHeaders.IF_NONE_MATCH))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1))
                            .staleWhileRevalidate(java.time.Duration.ofHours(1)).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        }

        MediaType type;
        try {
            type = MediaType.parseMediaType(image.contentType());
        } catch (RuntimeException ignored) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setCacheControl("public, max-age=86400, stale-while-revalidate=3600");
        if (etag != null && !etag.isBlank()) headers.setETag(etag);
        headers.add("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(image.bytes(), headers, HttpStatus.OK);
    }
}
