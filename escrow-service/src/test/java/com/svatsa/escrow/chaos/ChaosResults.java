package com.svatsa.escrow.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes chaos/results/&lt;scenario&gt;-&lt;timestamp&gt;.json:
 * {scenario, startedAt, durationMs, operations, violations, notes}.
 *
 * The directory defaults to ../chaos/results (Maven runs tests from the module directory)
 * and can be overridden with -Dchaos.results.dir.
 */
public final class ChaosResults {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private ChaosResults() {}

    public static Path write(String scenario, Instant startedAt, long durationMs, long operations,
                             List<String> violations, Map<String, Object> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("startedAt", startedAt.toString());
        result.put("durationMs", durationMs);
        result.put("operations", operations);
        result.put("violations", violations.size());
        result.put("violationDetails", violations);
        result.put("notes", notes);
        try {
            Path dir = Path.of(System.getProperty("chaos.results.dir", "../chaos/results"));
            Files.createDirectories(dir);
            Path file = dir.resolve(scenario + "-" + STAMP.format(startedAt) + ".json");
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), result);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("could not write chaos result", e);
        }
    }
}
