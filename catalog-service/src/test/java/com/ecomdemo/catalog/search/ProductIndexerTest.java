package com.ecomdemo.catalog.search;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductIndexer: one event, the product's CURRENT state")
class ProductIndexerTest {

    @Mock
    private ProductService products;

    @Mock
    private ProductSearchIndex index;

    private ProductIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new ProductIndexer(products, index, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("an existing product is re-embedded from what it says now")
    void upsertsTheCurrentProduct() {
        when(index.isConfigured()).thenReturn(true);
        when(products.findProduct(7L)).thenReturn(Optional.of(TestData.product(7L, "Lamp", "10.00")));

        indexer.refresh(7L);

        verify(index).upsert(anyList());
        verify(index).record(any(), eq("indexed"));
    }

    @Test
    @DisplayName("a product that no longer exists has its embedding removed")
    void removesAMissingProduct() {
        when(index.isConfigured()).thenReturn(true);
        when(products.findProduct(7L)).thenReturn(Optional.empty());

        indexer.refresh(7L);

        verify(index).remove(7L);
        verify(index, never()).upsert(anyList());
    }

    @Test
    @DisplayName("deleted between the read and the write: the foreign key's refusal is the right answer, not an error")
    void deletedMidway() {
        when(index.isConfigured()).thenReturn(true);
        when(products.findProduct(7L)).thenReturn(Optional.of(TestData.product(7L, "Lamp", "10.00")));
        doThrow(new DataIntegrityViolationException("fk")).when(index).upsert(anyList());

        indexer.refresh(7L);

        verify(index).record(any(), eq("removed"));
    }

    @Test
    @DisplayName("a model failure is rethrown, so Kafka retries and then dead-letters it")
    void modelFailureIsRethrown() {
        when(index.isConfigured()).thenReturn(true);
        when(products.findProduct(7L)).thenReturn(Optional.of(TestData.product(7L, "Lamp", "10.00")));
        doThrow(new IllegalStateException("model down")).when(index).upsert(anyList());

        assertThatThrownBy(() -> indexer.refresh(7L)).hasMessage("model down");
        verify(index).record(any(), eq("failed"));
    }

    @Test
    @DisplayName("with no model, the event is acknowledged and nothing is read")
    void notConfigured() {
        when(index.isConfigured()).thenReturn(false);

        indexer.refresh(7L);

        verifyNoInteractions(products);
        verify(index).record(any(), eq("not_configured"));
    }
}
