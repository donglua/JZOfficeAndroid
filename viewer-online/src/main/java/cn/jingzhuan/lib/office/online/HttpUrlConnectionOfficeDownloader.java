package cn.jingzhuan.lib.office.online;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpUrlConnectionOfficeDownloader implements RemoteOfficeDownloader {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_STEP = 256L * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern DISPOSITION_PARAMETER = Pattern.compile(
        "(?:^|;)\\s*([^\\s=;]+)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)");

    @Override public Result download(RemoteOfficeRequest request, File destination, Progress progress, Cancellation cancellation) throws IOException {
        URL initialUrl = request.getUrl();
        URL currentUrl = initialUrl;
        HttpURLConnection connection = null;
        int redirects = 0;
        try {
            while (true) {
                checkCancelled(cancellation);
                connection = (HttpURLConnection) currentUrl.openConnection();
                cancellation.onCancel(connection::disconnect);
                checkCancelled(cancellation);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(request.getConnectTimeoutMillis());
                connection.setReadTimeout(request.getReadTimeoutMillis());
                connection.setRequestProperty("Accept", "application/vnd.openxmlformats-officedocument.wordprocessingml.document, application/vnd.openxmlformats-officedocument.presentationml.presentation, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/octet-stream");
                if (sameOrigin(initialUrl, currentUrl)) {
                    for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                checkCancelled(cancellation);
                if (isRedirect(status)) {
                    if (++redirects > MAX_REDIRECTS) throw new IOException("Too many redirects");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("Redirect response missing Location header");
                    URL nextUrl;
                    try {
                        nextUrl = RemoteOfficeRequest.parseUrl(new URL(currentUrl, location.trim()).toString());
                    } catch (IllegalArgumentException error) {
                        throw new IOException("Invalid redirect URL", error);
                    }
                    if ("https".equalsIgnoreCase(currentUrl.getProtocol()) && "http".equalsIgnoreCase(nextUrl.getProtocol())) {
                        throw new IOException("HTTPS to HTTP redirect is not allowed");
                    }
                    currentUrl = nextUrl;
                    cancellation.onCancel(null);
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("Download failed with HTTP " + status);
                long totalBytes = contentLength(connection);
                if (totalBytes > request.getMaxBytes()) throw new IOException("Document exceeds download limit");
                if (totalBytes == 0) throw new IOException("Document is empty");
                String contentType = connection.getContentType();
                String displayName = firstNonEmpty(request.getDisplayName(), fileName(connection.getHeaderField("Content-Disposition")), fileName(currentUrl));
                long bytesRead = copy(connection, destination, request.getMaxBytes(), totalBytes, progress, cancellation);
                checkCancelled(cancellation);
                return new Result(displayName == null ? "Document" : displayName, contentType, bytesRead);
            }
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted() || cancellation.isCancelled()) {
                InterruptedIOException cancelled = new InterruptedIOException("Download cancelled");
                cancelled.initCause(error);
                throw cancelled;
            }
            throw error;
        } finally {
            cancellation.onCancel(null);
            if (connection != null) connection.disconnect();
        }
    }

    private static long copy(HttpURLConnection connection, File destination, long maxBytes, long totalBytes, Progress progress, Cancellation cancellation) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create cache directory");
        long bytesRead = 0;
        long lastProgress = 0;
        progress.onProgress(0, totalBytes);
        checkCancelled(cancellation);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                checkCancelled(cancellation);
                int count = input.read(buffer);
                checkCancelled(cancellation);
                if (count == -1) break;
                if (count > maxBytes - bytesRead) throw new IOException("Document exceeds download limit");
                bytesRead += count;
                if (totalBytes >= 0 && bytesRead > totalBytes) throw new IOException("Document exceeds declared length");
                output.write(buffer, 0, count);
                if (bytesRead == totalBytes || bytesRead - lastProgress >= PROGRESS_STEP) {
                    progress.onProgress(bytesRead, totalBytes);
                    lastProgress = bytesRead;
                }
            }
        }
        checkCancelled(cancellation);
        if (bytesRead == 0) throw new IOException("Document is empty");
        if (totalBytes >= 0 && bytesRead != totalBytes) throw new IOException("Document body is truncated");
        progress.onProgress(bytesRead, totalBytes);
        return bytesRead;
    }

    private static long contentLength(HttpURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value.trim());
            if (length >= 0) return length;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid Content-Length", error);
        }
        throw new IOException("Invalid Content-Length");
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
            || status == HttpURLConnection.HTTP_MOVED_TEMP
            || status == HttpURLConnection.HTTP_SEE_OTHER
            || status == 307
            || status == 308;
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
            && a.getHost().equalsIgnoreCase(b.getHost())
            && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URL url) {
        if (url.getPort() >= 0) return url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    private static void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancellation.isCancelled()) throw new InterruptedIOException("Download cancelled");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return null;
    }

    private static String fileName(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return decode(name);
    }

    private static String fileName(String contentDisposition) {
        if (contentDisposition == null) return null;
        String fallback = null;
        Matcher parameters = DISPOSITION_PARAMETER.matcher(contentDisposition);
        while (parameters.find()) {
            String name = parameters.group(1);
            String value = stripQuotes(parameters.group(2).trim());
            if ("filename*".equalsIgnoreCase(name)) {
                int firstQuote = value.indexOf('\'');
                int secondQuote = value.indexOf('\'', firstQuote + 1);
                if (firstQuote <= 0 || secondQuote < 0) continue;
                try {
                    String decoded = URLDecoder.decode(value.substring(secondQuote + 1).replace("+", "%2B"), value.substring(0, firstQuote));
                    if (!decoded.trim().isEmpty()) return decoded;
                } catch (IllegalArgumentException | IOException ignored) {
                    continue;
                }
            } else if ("filename".equalsIgnoreCase(name) && fallback == null && !value.trim().isEmpty()) {
                fallback = value;
            }
        }
        return fallback;
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return value;
        try { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()); }
        catch (IllegalArgumentException | IOException ignored) { return value; }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replaceAll("\\\\(.)", "$1");
        }
        return value;
    }
}
