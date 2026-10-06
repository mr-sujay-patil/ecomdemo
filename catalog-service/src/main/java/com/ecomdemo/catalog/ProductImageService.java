package com.ecomdemo.catalog;

import com.ecomdemo.catalog.internal.ProductRepository;
import com.ecomdemo.shared.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the image a product names and hands it over with its media type and a validator.
 *
 * <p>The image files ship inside this service, and which one a request gets is decided by the
 * database row for the product id, never by anything the client sends. Even so, the stored name is
 * treated as untrusted: it must be a plain file name with an allow-listed extension before it is
 * opened. A row that fails that (a path, a double extension, an unknown type) is a configuration
 * fault, so it is an {@link IllegalStateException} (a 500 and a log line), not a 404 that would
 * hide it.
 *
 * <p>The media type comes from the extension allow-list and not from sniffing the bytes, and the
 * controller sends {@code nosniff}, so the browser does not get to decide either.
 */
@Service
public class ProductImageService {

    /** A plain file name: letters, digits, dot, dash, underscore. No separator, so no way out of the directory. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,98}");

    private static final Map<String, MediaType> ALLOWED = Map.of(
            "svg", MediaType.valueOf("image/svg+xml"),
            "png", MediaType.IMAGE_PNG,
            "webp", MediaType.valueOf("image/webp"),
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG);

    private static final String DIRECTORY = "product-images/";

    private final ProductRepository repository;

    /** The files are tiny and immutable for the life of the process; read and hash each once. */
    private final Map<String, ProductImage> loaded = new ConcurrentHashMap<>();

    public ProductImageService(ProductRepository repository) {
        this.repository = repository;
    }

    /** The media type an extension is allowed to be served as, or empty if it is not on the allow-list. */
    public static Optional<MediaType> mediaTypeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(ALLOWED.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public ProductImage find(Long productId) {
        Product product = repository.findById(productId).orElseThrow(() -> NotFoundException.product(productId));
        String fileName = product.getImageFile();
        if (fileName == null) {
            throw new NotFoundException("Product " + productId + " has no image");
        }
        return loaded.computeIfAbsent(fileName, ProductImageService::load);
    }

    private static ProductImage load(String fileName) {
        if (!PLAIN_NAME.matcher(fileName).matches()) {
            throw new IllegalStateException("product.image_file is not a plain file name: " + fileName);
        }
        MediaType mediaType = mediaTypeFor(fileName).orElseThrow(
                () -> new IllegalStateException("product.image_file has a type outside the allow-list: " + fileName));
        try (InputStream in = new ClassPathResource(DIRECTORY + fileName).getInputStream()) {
            byte[] content = in.readAllBytes();
            return new ProductImage(content, mediaType, "\"" + sha256(content) + "\"");
        } catch (IOException e) {
            throw new IllegalStateException("product.image_file names a file this service does not ship: " + fileName, e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
