package com.chen.football.media.service;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.media.config.MediaProxyProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allow-listed image fetcher with two cache layers:
 *
 * <ul>
 *   <li>a small in-memory cache for hot cards in the current process;</li>
 *   <li>a persistent file cache which survives service restarts and can be
 *       mounted as a Docker volume.</li>
 * </ul>
 *
 * The cache key is the validated source URL.  Bytes are never stored in the
 * business database and writes use a temporary file followed by an atomic
 * move, so a process restart cannot leave a partially-written asset visible.
 */
@Service
public class MediaAssetCacheService {

    private static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "static.files.bbci.co.uk",
            "a.espncdn.com",
            "images.a.transfermarkt.technology",
            "img.a.transfermarkt.technology",
            "tmssl.akamaized.net",
            "upload.wikimedia.org",
            "commons.wikimedia.org"
    );
    private static final String META_SEPARATOR = "\n";

    private final MediaProxyProperties properties;
    private final Path cacheDirectory;
    private final HttpClient httpClient;
    private final Map<String, CachedImage> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    public MediaAssetCacheService(MediaProxyProperties properties, CrawlerProperties crawlerProperties) {
        this.properties = properties;
        this.cacheDirectory = Path.of(properties.getCacheDir()).toAbsolutePath().normalize();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER);
        CrawlerProperties.Proxy proxy = crawlerProperties == null ? null : crawlerProperties.getProxy();
        if (proxy != null && proxy.isEnabled() && proxy.getHost() != null
                && !proxy.getHost().isBlank() && proxy.getPort() > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost().trim(), proxy.getPort())));
        }
        this.httpClient = builder.build();
        ensureDirectory();
    }

    public Optional<MediaImage> load(String rawUrl) {
        URI uri = validateUrl(rawUrl);
        if (uri == null || !properties.isEnabled()) return Optional.empty();

        String key = uri.toString();
        long now = System.currentTimeMillis();
        CachedImage hot = memoryCache.get(key);
        if (hot != null && !isExpired(hot.loadedAt(), now)) return Optional.of(hot.image());

        Object lock = keyLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            hot = memoryCache.get(key);
            if (hot != null && !isExpired(hot.loadedAt(), now)) return Optional.of(hot.image());

            Optional<CachedImage> disk = readDisk(key, now);
            if (disk.isPresent()) {
                putMemory(key, disk.get());
                return Optional.of(disk.get().image());
            }

            Optional<MediaImage> fetched = fetch(uri);
            fetched.ifPresent(image -> {
                CachedImage cached = new CachedImage(image, now());
                writeDisk(key, cached);
                putMemory(key, cached);
            });
            return fetched;
        }
    }

    /** Downloads one asset ahead of time. Errors are deliberately ignored. */
    public boolean warmup(String rawUrl) {
        return load(rawUrl).isPresent();
    }

    public int warmup(List<String> urls) {
        if (urls == null || urls.isEmpty() || !properties.isEnabled()) return 0;
        int loaded = 0;
        for (String url : urls) {
            if (warmup(url)) loaded++;
        }
        return loaded;
    }

    public URI validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH) return null;
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) return null;
            if (uri.getPort() != -1 && uri.getPort() != 443) return null;
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean allowed = ALLOWED_HOSTS.stream().anyMatch(item -> normalizedHost.equals(item)
                    || normalizedHost.endsWith("." + item));
            return allowed ? uri : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Optional<MediaImage> fetch(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                    .header("User-Agent", "ChenFootball-media-proxy/1.0")
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/svg+xml,image/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> result = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (result.statusCode() < 200 || result.statusCode() >= 300) return Optional.empty();
            byte[] bytes = result.body();
            if (bytes == null || bytes.length == 0 || bytes.length > properties.getMaxImageBytes()) return Optional.empty();
            String contentType = result.headers().firstValue("content-type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            contentType = normalizeContentType(contentType, bytes);
            if (!contentType.startsWith("image/")) return Optional.empty();
            String etag = result.headers().firstValue("etag").orElse("").trim();
            return Optional.of(new MediaImage(bytes, contentType, etag));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String normalizeContentType(String contentType, byte[] bytes) {
        if (contentType.startsWith("image/")) return contentType;
        if (startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) return "image/png";
        if (startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (startsWith(bytes, new byte[]{'G', 'I', 'F', '8'})) return "image/gif";
        if (startsWith(bytes, new byte[]{'R', 'I', 'F', 'F'}) && bytes.length > 11
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        String prefix = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        return prefix.startsWith("<svg") || prefix.startsWith("<?xml") && prefix.contains("<svg")
                ? "image/svg+xml" : "";
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        if (source == null || source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (source[i] != prefix[i]) return false;
        return true;
    }

    private Optional<CachedImage> readDisk(String key, long now) {
        Path imagePath = imagePath(key);
        Path metaPath = metaPath(key);
        if (!Files.isRegularFile(imagePath) || !Files.isRegularFile(metaPath)) return Optional.empty();
        try {
            long loadedAt = Files.getLastModifiedTime(imagePath).toMillis();
            if (isExpired(loadedAt, now)) {
                Files.deleteIfExists(imagePath);
                Files.deleteIfExists(metaPath);
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(imagePath);
            if (bytes.length == 0 || bytes.length > properties.getMaxImageBytes()) return Optional.empty();
            List<String> metadata = Files.readAllLines(metaPath, StandardCharsets.UTF_8);
            String contentType = metadata.isEmpty() ? "" : metadata.get(0).trim();
            String etag = metadata.size() > 1 ? metadata.get(1).trim() : "";
            if (!contentType.startsWith("image/")) return Optional.empty();
            return Optional.of(new CachedImage(new MediaImage(bytes, contentType, etag), loadedAt));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void writeDisk(String key, CachedImage image) {
        try {
            ensureDirectory();
            String hash = hash(key);
            Path imageTmp = Files.createTempFile(cacheDirectory, hash, ".img.tmp");
            Path metaTmp = Files.createTempFile(cacheDirectory, hash, ".meta.tmp");
            try {
                Files.write(imageTmp, image.image().bytes());
                Files.writeString(metaTmp, image.image().contentType() + META_SEPARATOR + image.image().etag(), StandardCharsets.UTF_8);
                moveReplace(imageTmp, imagePath(key));
                moveReplace(metaTmp, metaPath(key));
            } finally {
                Files.deleteIfExists(imageTmp);
                Files.deleteIfExists(metaTmp);
            }
            pruneDisk();
        } catch (IOException | RuntimeException ignored) {
            // The proxy must remain usable when the configured volume is read-only.
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void pruneDisk() throws IOException {
        if (properties.getMaxDiskBytes() <= 0) return;
        List<Path> images = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDirectory, "*.img")) {
            stream.forEach(images::add);
        }
        long total = 0;
        for (Path path : images) total += Files.size(path);
        if (total <= properties.getMaxDiskBytes()) return;
        images.sort(Comparator.comparingLong(this::lastModified));
        for (Path path : images) {
            if (total <= properties.getMaxDiskBytes()) break;
            long size = Files.size(path);
            Files.deleteIfExists(path);
            Files.deleteIfExists(metaPathFromImage(path));
            total -= size;
        }
    }

    private long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    private Path metaPathFromImage(Path image) {
        String name = image.getFileName().toString();
        return image.resolveSibling(name.substring(0, name.length() - 4) + ".meta");
    }

    private void putMemory(String key, CachedImage image) {
        if (properties.getMaxMemoryEntries() <= 0) return;
        if (memoryCache.size() >= properties.getMaxMemoryEntries()) {
            memoryCache.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().loadedAt()))
                    .map(Map.Entry::getKey).ifPresent(memoryCache::remove);
        }
        memoryCache.put(key, image);
    }

    private boolean isExpired(long loadedAt, long now) {
        long ttl = Math.max(1, properties.getDiskTtlHours()) * 60 * 60 * 1000L;
        return loadedAt <= 0 || now - loadedAt >= ttl;
    }

    private void ensureDirectory() {
        try { Files.createDirectories(cacheDirectory); }
        catch (IOException | RuntimeException ignored) { }
    }

    private Path imagePath(String key) { return cacheDirectory.resolve(hash(key) + ".img"); }

    private Path metaPath(String key) { return cacheDirectory.resolve(hash(key) + ".meta"); }

    private String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private long now() { return System.currentTimeMillis(); }

    public record MediaImage(byte[] bytes, String contentType, String etag) { }

    private record CachedImage(MediaImage image, long loadedAt) { }
}
