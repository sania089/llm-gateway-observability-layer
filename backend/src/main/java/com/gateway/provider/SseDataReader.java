package com.gateway.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Reads a Server-Sent Events body line by line, handing each {@code data:} payload to a consumer. */
public final class SseDataReader {

    private static final String DATA_PREFIX = "data:";

    private SseDataReader() {
    }

    /** Blocks until the stream ends or the consumer throws; either way the caller owns closing the stream. */
    public static void forEachData(InputStream body, Consumer<String> onData) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(DATA_PREFIX)) {
                onData.accept(line.substring(DATA_PREFIX.length()).strip());
            }
        }
    }
}
