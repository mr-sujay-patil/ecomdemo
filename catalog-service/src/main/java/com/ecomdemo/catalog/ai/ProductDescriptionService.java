package com.ecomdemo.catalog.ai;

import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generate, then save - in that order and in two steps, because of how long the first one takes.
 *
 * <p><strong>No transaction around the model call.</strong> A generation takes seconds, sometimes
 * tens of them. A {@code @Transactional} method spanning it would hold a database connection for
 * all of that time, doing nothing; this service's pool has ten, so ten admins pressing "generate"
 * at once would stall every product read behind them. The product is read in one short
 * transaction, the model is called with none open, and the result is written in a second short
 * one.
 *
 * <p>The price of that is a window: the product can change, or be deleted, while the model is
 * thinking. A deletion makes the save fail with a 404, which is right. An edit in the meantime is
 * overwritten by the generated description - acceptable for an admin-only action that the admin
 * just asked for, and recorded in the generation history either way.
 */
@Service
public class ProductDescriptionService {

    private final ProductService productService;
    private final ProductCopyGenerator generator;
    private final DescriptionGenerationRepository generations;
    private final TransactionTemplate transaction;

    public ProductDescriptionService(
            ProductService productService,
            ProductCopyGenerator generator,
            DescriptionGenerationRepository generations,
            TransactionTemplate transaction) {
        this.productService = productService;
        this.generator = generator;
        this.generations = generations;
        this.transaction = transaction;
    }

    public GeneratedDescriptionResponse generate(Long productId) {
        Product product = productService.requireProduct(productId);

        ProductCopyGenerator.GeneratedCopy generated = generator.generate(product);

        DescriptionGeneration saved = transaction.execute(status -> {
            productService.replaceDescription(productId, generated.copy().description());
            return generations.save(new DescriptionGeneration(productId, generated, Instant.now()));
        });
        return GeneratedDescriptionResponse.from(saved);
    }
}
