package com.ecomdemo.catalog;

import org.springframework.http.MediaType;

/**
 * One product image, ready to serve.
 *
 * @param content the file's bytes
 * @param mediaType the type derived from the allow-listed file extension, never from the content
 * @param etag the quoted strong validator, the SHA-256 of {@code content}: it changes exactly when the bytes do
 */
public record ProductImage(byte[] content, MediaType mediaType, String etag) {
}
