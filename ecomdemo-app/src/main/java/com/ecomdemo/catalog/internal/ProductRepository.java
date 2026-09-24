package com.ecomdemo.catalog.internal;

import com.ecomdemo.catalog.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation at runtime: no class to write, and
 * findAll/findById/save/deleteById come from {@link JpaRepository}.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * The product with this exact name, oldest first.
     *
     * <p>Added in Phase 14 for the CSV import, which treats the product name as the natural key
     * of a catalogue feed and updates rather than duplicates. {@code findFirst ... OrderByIdAsc}
     * rather than {@code findByName} because the column is NOT unique: this schema has never
     * required it and the API has always allowed two products to share a name. Returning
     * {@code Optional} from a non-unique column would blow up with an
     * {@code IncorrectResultSizeDataAccessException} the first time it did.
     *
     * <p>Spring Data derives the query from the method name: {@code First} becomes
     * {@code LIMIT 1}, {@code ByName} the {@code WHERE}, {@code OrderByIdAsc} the ordering that
     * makes "the oldest one" a defined answer rather than whatever the database returned first.
     */
    Optional<Product> findFirstByNameOrderByIdAsc(String name);
}
