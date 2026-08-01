package com.jeltechnologies.keycunda.secret;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Exports/imports secrets as a {@code .env} file - {@code KEY=VALUE} lines, one per secret.
 *
 * <p>The import parser is deliberately <b>not</b> standard dotenv: a real-world example this
 * feature was built against has entries like a GCP service-account key whose JSON value spans
 * many raw lines with no wrapping quotes, e.g.:
 * <pre>
 * IDP_GCP_SERVICE_ACCOUNT={
 * "type": "service_account",
 * ...
 * }
 * NEXT_KEY=value
 * </pre>
 * That's invalid for strict dotenv parsers (python-dotenv, Docker's --env-file, etc.), which
 * require a value to either fit on one line or be explicitly quoted across lines. Here, any line
 * that doesn't look like the start of a new {@code KEY=} assignment is treated as a continuation
 * of the previous key's value (its newline preserved) - this is intentional, not a bug, and should
 * not be "fixed" into strict dotenv parsing.
 */
@Component
public class SecretEnvCodec {

    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    public String export(List<Secret> secrets) {
        StringBuilder out = new StringBuilder();
        for (Secret secret : secrets) {
            out.append(secret.key()).append('=').append(secret.value()).append('\n');
        }
        return out.toString();
    }

    public Map<String, String> parse(String env) {
        Map<String, String> result = new LinkedHashMap<>();
        // Strip exactly one trailing newline before splitting - it's just the EOF marker every
        // line (including export()'s last one) ends with, not a genuine blank line to fold into
        // the final value as a continuation.
        String normalized = env.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] lines = normalized.split("\n", -1);

        String currentKey = null;
        StringBuilder currentValue = null;
        for (String line : lines) {
            Matcher matcher = KEY_LINE.matcher(line);
            if (matcher.matches()) {
                if (currentKey != null) {
                    result.put(currentKey, currentValue.toString());
                }
                currentKey = matcher.group(1);
                currentValue = new StringBuilder(matcher.group(2));
            } else if (currentKey != null) {
                currentValue.append('\n').append(line);
            }
            // Lines before the first KEY= assignment (blank lines, comments) are ignored.
        }
        if (currentKey != null) {
            result.put(currentKey, currentValue.toString());
        }
        return result;
    }
}
