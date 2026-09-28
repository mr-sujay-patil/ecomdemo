package com.ecomdemo.catalog.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DescriptionGenerationRepository extends JpaRepository<DescriptionGeneration, Long> {

    List<DescriptionGeneration> findByProductIdOrderByIdDesc(Long productId);
}
