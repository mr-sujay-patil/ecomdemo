package com.ecomdemo.product;

import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Business rules for the catalogue. The controller does HTTP, the repository does SQL,
 * and everything in between lives here.
 */
@Service
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

    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(), request.description(), request.price(), request.stockQuantity(),
                request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse update(Long id, ProductRequest request) {
        Product product = requireProduct(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setCategory(request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
    }

    /** Shared lookup so every caller produces the same 404 message. */
    public Product requireProduct(Long id) {
        return productRepository.findById(id).orElseThrow(() -> NotFoundException.product(id));
    }

    /** Used by the order feature once stock has been reduced. */
    public void save(Product product) {
        productRepository.save(product);
    }
}
