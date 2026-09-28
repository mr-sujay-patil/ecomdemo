package com.ecomdemo.catalog.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * One description a model wrote, with what it cost. Written once and never updated, so it has no
 * {@code @Version}: there is nothing for two writers to race over.
 *
 * <p>The product is referenced by id, not by a {@code @ManyToOne}. Nothing here navigates to the
 * product, and the foreign key in V3 is what keeps the reference honest.
 */
@Entity
@Table(name = "product_description_generation")
public class DescriptionGeneration {

    private static final String TAG_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "seo_title", nullable = false, length = 70)
    private String seoTitle;

    @Column(nullable = false, length = 500)
    private String tags;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected DescriptionGeneration() {
    }

    public DescriptionGeneration(Long productId, ProductCopyGenerator.GeneratedCopy generated, Instant generatedAt) {
        this.productId = productId;
        this.description = generated.copy().description();
        this.seoTitle = generated.copy().seoTitle();
        this.tags = String.join(TAG_SEPARATOR, generated.copy().tags());
        this.model = generated.model();
        this.promptTokens = generated.promptTokens();
        this.completionTokens = generated.completionTokens();
        this.generatedAt = generatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getDescription() {
        return description;
    }

    public String getSeoTitle() {
        return seoTitle;
    }

    public List<String> getTags() {
        return tags.isEmpty() ? List.of() : Arrays.asList(tags.split(TAG_SEPARATOR));
    }

    public String getModel() {
        return model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
