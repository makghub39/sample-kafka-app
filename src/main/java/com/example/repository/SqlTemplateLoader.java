package com.example.repository;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

@Component
public class SqlTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> queriesCache = new HashMap<>();

    public SqlTemplateLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String name) {
        // Load and parse the combined queries.sql (only source of truth)
        if (queriesCache.isEmpty()) {
            Resource combined = resourceLoader.getResource("classpath:sql/queries.sql");
            try (InputStream in = combined.getInputStream(); Scanner s = new Scanner(in, StandardCharsets.UTF_8.name())) {
                String currentName = null;
                StringBuilder sb = new StringBuilder();
                while (s.hasNextLine()) {
                    String line = s.nextLine();
                    if (line.trim().startsWith("-- name:")) {
                        if (currentName != null) {
                            queriesCache.put(currentName, sb.toString().trim());
                        }
                        currentName = line.trim().substring("-- name:".length()).trim();
                        sb = new StringBuilder();
                    } else {
                        if (currentName != null) {
                            sb.append(line).append('\n');
                        }
                    }
                }
                if (currentName != null) {
                    queriesCache.put(currentName, sb.toString().trim());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load combined SQL queries file: queries.sql", e);
            }
        }

        String query = queriesCache.get(name);
        if (query == null) {
            throw new IllegalArgumentException("SQL query not found in queries.sql: " + name);
        }
        return query;
    }
}
