package com.ecomdemo.product;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation at runtime: no class to write, and
 * findAll/findById/save/deleteById come from {@link JpaRepository}.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
