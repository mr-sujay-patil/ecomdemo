package com.ecomdemo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The correlation ID filter, driven directly with mock servlet objects.
 *
 * <p>No Spring context: the filter has no dependencies, and what is being tested — what it puts in
 * the MDC, and crucially what it takes back out — is visible from the outside without one. The
 * chain is a {@link MockFilterChain} subclass that records the MDC as it was DURING the request,
 * because "the ID was set while the chain ran" and "the ID is gone afterwards" are two different
 * assertions and a test that only checks the second passes happily against a filter that never
 * set anything at all.
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /** Records what the MDC held at the moment the rest of the chain was invoked. */
    private static final class MdcCapturingChain extends MockFilterChain {

        private final List<String> seen = new ArrayList<>();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            seen.add(MDC.get(CorrelationId.MDC_KEY));
            super.doFilter(request, response);
        }

        String capturedId() {
            return seen.isEmpty() ? null : seen.get(seen.size() - 1);
        }
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("when the caller sends no ID")
    class WithoutAnInboundId {

        @Test
        @DisplayName("generates one, exposes it to the chain and returns it as a header")
        void generatesAnId() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MdcCapturingChain chain = new MdcCapturingChain();

            filter.doFilter(request, response, chain);

            String header = response.getHeader(CorrelationId.HEADER);
            assertThat(header).isNotNull().matches(CorrelationId.ALLOWED);
            // The same value in both places, which is the entire contract: the ID in the response
            // is the one a human can paste into Grafana to find the lines this request wrote.
            assertThat(chain.capturedId()).isEqualTo(header);
        }

        @Test
        @DisplayName("gives two requests two different IDs")
        void generatesADifferentIdEachTime() throws Exception {
            MockHttpServletResponse first = new MockHttpServletResponse();
            MockHttpServletResponse second = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest("GET", "/api/products"), first, new MockFilterChain());
            filter.doFilter(new MockHttpServletRequest("GET", "/api/products"), second, new MockFilterChain());

            assertThat(first.getHeader(CorrelationId.HEADER))
                    .isNotEqualTo(second.getHeader(CorrelationId.HEADER));
        }
    }

    @Nested
    @DisplayName("when the caller sends an ID")
    class WithAnInboundId {

        @Test
        @DisplayName("reuses it, which is what makes the ID work across a boundary")
        void reusesASafeId() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
            request.addHeader(CorrelationId.HEADER, "checkout-load-test-42");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MdcCapturingChain chain = new MdcCapturingChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.capturedId()).isEqualTo("checkout-load-test-42");
            assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("checkout-load-test-42");
        }

        @Test
        @DisplayName("replaces one carrying a newline, so a caller cannot forge log entries")
        void replacesAnIdThatWouldInjectALogLine() throws Exception {
            // The attack this prevents: the value below is written verbatim into a log line, so
            // the newline ends the real entry and everything after it is a log entry of the
            // caller's own composition — in a store that an incident review, and possibly an
            // audit, will treat as the record of what happened.
            String injection = "abc12345\n{\"message\":\"payment approved\"}";
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
            request.addHeader(CorrelationId.HEADER, injection);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(CorrelationId.HEADER))
                    .isNotEqualTo(injection)
                    .doesNotContain("\n")
                    .matches(CorrelationId.ALLOWED);
        }

        @Test
        @DisplayName("replaces one that is absurdly long or oddly short")
        void replacesAnIdOutsideTheAllowedLength() throws Exception {
            MockHttpServletRequest tooLong = new MockHttpServletRequest("GET", "/api/products");
            tooLong.addHeader(CorrelationId.HEADER, "x".repeat(65));
            MockHttpServletResponse longResponse = new MockHttpServletResponse();

            MockHttpServletRequest tooShort = new MockHttpServletRequest("GET", "/api/products");
            tooShort.addHeader(CorrelationId.HEADER, "short");
            MockHttpServletResponse shortResponse = new MockHttpServletResponse();

            filter.doFilter(tooLong, longResponse, new MockFilterChain());
            filter.doFilter(tooShort, shortResponse, new MockFilterChain());

            assertThat(longResponse.getHeader(CorrelationId.HEADER)).hasSize(32);
            assertThat(shortResponse.getHeader(CorrelationId.HEADER)).hasSize(32);
        }
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("clears the MDC when the request finishes")
        void clearsTheMdcAfterwards() throws Exception {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/products"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        }

        @Test
        @DisplayName("clears the MDC even when the chain throws")
        void clearsTheMdcAfterAnException() {
            // The failure this guards against does not look like a bug in the failing request. It
            // looks like the NEXT request on that pooled Tomcat thread inheriting this request's
            // ID and filing its log lines under somebody else's story — wrong rather than
            // missing, and invisible in the output.
            MockFilterChain exploding = new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                        throws IOException {
                    throw new IOException("the database is on fire");
                }
            };

            assertThat(
                            org.assertj.core.api.Assertions.catchThrowable(
                                    () ->
                                            filter.doFilter(
                                                    new MockHttpServletRequest("POST", "/api/orders"),
                                                    new MockHttpServletResponse(),
                                                    exploding)))
                    .isInstanceOf(IOException.class);

            assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        }
    }
}
