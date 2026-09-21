package com.ecomdemo.product;

import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for the catalogue. The controller does HTTP, the repository does SQL,
 * and everything in between lives here.
 *
 * <p>The class defaults to {@code readOnly = true} and the four writing methods override it.
 * Declaring it this way round means a new read method is safe by default and a new write method
 * fails loudly if its author forgets to say so, rather than the other way round.
 *
 * <p>A note on what {@code readOnly} means here, because it is easy to over-read. It is a hint,
 * not a guarantee: Hibernate skips the dirty-check snapshot of every loaded entity and sets
 * manual flush mode, and the JDBC connection is marked read-only for drivers that care. It also
 * only applies when this bean actually starts the transaction. {@link #requireProduct(Long)} is
 * called from inside the cart's and the order's read-write transactions, and propagation
 * {@code REQUIRED} joins theirs — the outer transaction's settings win, which is precisely why
 * the product they load can still be modified and saved.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * Constructor injection: the dependency is final, the object cannot exist in a half-built
     * state, and the class can be instantiated in a plain unit test with {@code new}.
     */
    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream().map(ProductResponse::from).toList();
    }

    public ProductResponse findById(Long id) {
        return ProductResponse.from(requireProduct(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(), request.description(), request.price(), request.stockQuantity(),
                request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = requireProduct(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setCategory(request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
    }

    /** Shared lookup so every caller produces the same 404 message. */
    public Product requireProduct(Long id) {
        return productRepository.findById(id).orElseThrow(() -> NotFoundException.product(id));
    }

    /**
     * Used by the order feature once stock has been reduced.
     *
     * <p>Always called from inside the checkout transaction, which it joins. The versioned
     * UPDATE that this eventually produces is not issued here — it is issued when that
     * transaction flushes, which is where an optimistic lock failure surfaces.
     */
    @Transactional
    public void save(Product product) {
        productRepository.save(product);
    }
}
