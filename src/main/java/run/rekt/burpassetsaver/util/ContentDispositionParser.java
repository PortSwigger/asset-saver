package run.rekt.burpassetsaver.util;

// Courtesy of Claude Code

import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicHeaderValueParser;
import org.apache.hc.core5.http.message.ParserCursor;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContentDispositionParser {

    private static final int MAX_FILENAME_LENGTH = 255;
    // Matches realistic server limits (Apache/Nginx cap header values at 8 KB).
    // Guards BasicHeaderValueParser, URLDecoder, and all regex passes from
    // unbounded work on attacker-supplied input.
    private static final int MAX_INPUT_LENGTH = 8_192;

    // Matches: charset'language'percent-encoded-value  (RFC 5987)
    private static final Pattern RFC5987_PATTERN = Pattern.compile(
            "^([^']+)'([^']*)'(.+)$", Pattern.CASE_INSENSITIVE
    );

    // Windows reserved device names (case-insensitive, with or without extension)
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    /**
     * Extracts a filename from a Content-Disposition header value.
     *
     * Prefers {@code filename*} (RFC 5987 / RFC 6266) over {@code filename}.
     * The returned value is sanitized: path components are stripped, control
     * characters removed, and Windows reserved names rejected.
     *
     * @param contentDisposition the raw Content-Disposition header value
     * @return the sanitized filename, or {@code null} if absent or invalid
     */
    public static String extractFilename(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return null;
        }
        if (contentDisposition.length() > MAX_INPUT_LENGTH) {
            return null;
        }

        ParserCursor cursor = new ParserCursor(0, contentDisposition.length());
        HeaderElement[] elements = BasicHeaderValueParser.INSTANCE
                .parseElements(contentDisposition, cursor);

        String filename = null;
        String filenameStar = null;

        for (HeaderElement element : elements) {
            for (NameValuePair param : element.getParameters()) {
                String name = param.getName();
                if ("filename*".equalsIgnoreCase(name) && filenameStar == null) {
                    filenameStar = param.getValue();
                } else if ("filename".equalsIgnoreCase(name) && filename == null) {
                    filename = unquote(param.getValue());
                }
            }
        }

        // RFC 6266 §4.1: filename* takes priority over filename
        String raw = (filenameStar != null) ? decodeRfc5987(filenameStar) : filename;
        return sanitize(raw);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Strips surrounding double-quotes that httpcore5 may leave in place. */
    private static String unquote(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Decodes an RFC 5987 extended-value: {@code charset'language'pct-encoded}. */
    private static String decodeRfc5987(String value) {
        if (value == null) return null;

        Matcher m = RFC5987_PATTERN.matcher(value.trim());
        if (!m.matches()) return null;

        String charsetName = m.group(1);
        String encodedValue = m.group(3);

        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (UnsupportedCharsetException e) {
            charset = StandardCharsets.UTF_8;
        }

        try {
            // RFC 5987 percent-encoding; '+' is literal, not a space
            return URLDecoder.decode(encodedValue.replace("+", "%2B"), charset);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sanitizes a raw filename against common security issues:
     * <ul>
     *   <li>Path traversal (strips all directory components)</li>
     *   <li>Null bytes and control characters</li>
     *   <li>Windows trailing dots/spaces</li>
     *   <li>Windows reserved device names</li>
     *   <li>Excessive length</li>
     * </ul>
     */
    private static String sanitize(String filename) {
        if (filename == null) return null;

        // Remove null bytes first (they can truncate paths on some systems)
        filename = filename.replace("\0", "");

        // Normalise path separators and strip all directory components
        filename = filename.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }

        // Remove control characters (U+0000–U+001F and DEL U+007F)
        filename = filename.replaceAll("[\\x00-\\x1F\\x7F]", "");

        // Strip trailing dots and spaces (Windows silently ignores them,
        // which can lead to unexpected file access)
        filename = filename.replaceAll("[. ]+$", "");

        // Truncate before the Windows reserved-name check so a padded name
        // like "NUL.txt" is still caught
        if (filename.length() > MAX_FILENAME_LENGTH) {
            filename = filename.substring(0, MAX_FILENAME_LENGTH);
        }

        if (filename.isBlank()) return null;

        // Reject Windows reserved device names (with or without extension)
        String base = filename.contains(".")
                ? filename.substring(0, filename.indexOf('.')).toUpperCase()
                : filename.toUpperCase();
        if (WINDOWS_RESERVED.contains(base)) return null;

        return filename;
    }
}
