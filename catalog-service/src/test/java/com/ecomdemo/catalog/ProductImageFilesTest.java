package com.ecomdemo.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The image files this service ships, checked as files: every one has an allowed type and stays
 * under the size cap, and every file the seed migration names exists.
 *
 * <p>Reads {@code src/main/resources} directly (Maven runs tests from the module directory), so
 * a file added without the check, or a migration naming a file that was never committed, fails
 * here and not in the browser.
 */
class ProductImageFilesTest {

    private static final long MAX_BYTES = 256 * 1024;
    private static final Path IMAGES = Paths.get("src/main/resources/product-images");
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    private static List<Path> shippedImages() throws IOException {
        try (Stream<Path> files = Files.list(IMAGES)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    @DisplayName("every shipped image has an allowed extension and is at most 256 KiB")
    void allowedAndSmall() throws IOException {
        List<Path> images = shippedImages();

        assertThat(images).isNotEmpty();
        for (Path image : images) {
            String name = image.getFileName().toString();
            assertThat(ProductImageService.mediaTypeFor(name)).as(name + " is allow-listed").isPresent();
            assertThat(Files.size(image)).as(name + " size").isLessThanOrEqualTo(MAX_BYTES);
        }
    }

    @Test
    @DisplayName("every SVG is plain markup: no script, no event handler, no external reference")
    void svgsAreInert() throws IOException {
        for (Path image : shippedImages()) {
            if (!image.getFileName().toString().endsWith(".svg")) {
                continue;
            }
            // The one URL an SVG must contain is its namespace; remove it, then no other URL may remain.
            String svg = Files.readString(image, StandardCharsets.UTF_8).toLowerCase()
                    .replace("xmlns=\"http://www.w3.org/2000/svg\"", "");
            assertThat(svg).as(image.getFileName().toString()).startsWith("<svg").doesNotContain("<script", "onload=", "onclick=", "javascript:", "http://", "href=");
        }
    }

    @Test
    @DisplayName("every file the seed migration names is shipped, and V5 names at least one")
    void migrationNamesOnlyShippedFiles() throws IOException {
        Path v5;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            v5 = files.filter(p -> p.getFileName().toString().startsWith("V5__")).findFirst().orElseThrow();
        }
        Matcher named = Pattern.compile("'([A-Za-z0-9._-]+\\.(?:svg|png|webp|jpg|jpeg))'")
                .matcher(Files.readString(v5, StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        while (named.find()) {
            names.add(named.group(1));
        }

        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(IMAGES.resolve(name)).as(name).exists();
        }
    }
}
