package com.ecomdemo.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.customer.dto.UpdateProfileRequest;
import com.ecomdemo.support.WithSecurityRules;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link CustomerController}.
 *
 * <p>The class runs anonymously by default, because registration is the one write in this
 * application that an anonymous caller is supposed to be able to make. The profile tests then
 * opt in to a user.
 */
@WebMvcTest(CustomerController.class)
@WithSecurityRules
@WithAnonymousUser
class CustomerControllerTest {

    private static final CustomerResponse ASHA = new CustomerResponse(
            2L, "asha", "Asha Rao", Role.CUSTOMER, Instant.parse("2026-09-22T09:15:00Z"));

    private static final String VALID_BODY =
            """
            {"username":"asha","password":"correct-horse-battery-staple","fullName":"Asha Rao"}
            """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CustomerService customerService;

    @Nested
    @DisplayName("POST /api/customers/register")
    class Register {

        @Test
        void register_whenAnonymousAndValid_returns201() {
            // Given
            when(customerService.register(any(RegisterRequest.class))).thenReturn(ASHA);

            // When / Then: requiring an account in order to create an account would be a closed
            // loop, so this is deliberately reachable with no credentials at all
            assertThat(mvc.post().uri("/api/customers/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .hasStatus(CREATED)
                    .hasHeader("Location", "/api/customers/me")
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"id":2,"username":"asha","fullName":"Asha Rao","role":"CUSTOMER"}
                            """);
        }

        @Test
        void register_whenValid_neverEchoesThePassword() throws Exception {
            // Given
            when(customerService.register(any(RegisterRequest.class))).thenReturn(ASHA);

            // When
            String body = mvc.post().uri("/api/customers/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY)
                    .exchange()
                    .getResponse()
                    .getContentAsString();

            // Then: not the password, and not its hash either
            assertThat(body).doesNotContain("correct-horse-battery-staple").doesNotContain("password");
        }

        @Test
        void register_whenThePasswordIsTooShort_returns400AndNeverReachesTheService() {
            assertThat(mvc.post().uri("/api/customers/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"asha","password":"short","fullName":"Asha Rao"}"""))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":400,"message":"password must be between 8 and 72 characters"}
                            """);
            verify(customerService, never()).register(any());
        }

        @Test
        void register_whenTheUsernameHasIllegalCharacters_returns400() {
            assertThat(mvc.post().uri("/api/customers/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"a sha!","password":"correct-horse-battery","fullName":"Asha Rao"}"""))
                    .hasStatus(BAD_REQUEST);
            verify(customerService, never()).register(any());
        }

        @Test
        void register_whenTheUsernameIsTaken_returns409() {
            // Given
            when(customerService.register(any(RegisterRequest.class)))
                    .thenThrow(new ConflictException("Username asha is already taken"));

            // When / Then
            assertThat(mvc.post().uri("/api/customers/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .hasStatus(CONFLICT)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":409,"message":"Username asha is already taken"}
                            """);
        }

        @Test
        void register_cannotAskForARole() {
            // Given: a body that tries to smuggle a role past the DTO
            when(customerService.register(any(RegisterRequest.class))).thenReturn(ASHA);

            // When / Then: RegisterRequest has no role component, so Jackson drops the field and
            // the account is created as a CUSTOMER regardless of what was sent
            assertThat(mvc.post().uri("/api/customers/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"asha","password":"correct-horse-battery-staple",
                                     "fullName":"Asha Rao","role":"ADMIN"}"""))
                    .hasStatus(CREATED)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"role":"CUSTOMER"}
                            """);
        }
    }

    @Nested
    @DisplayName("GET and PUT /api/customers/me")
    class Profile {

        @Test
        @WithMockUser(username = "asha", roles = "CUSTOMER")
        void me_whenAuthenticated_returnsTheCallersOwnProfile() {
            // Given
            when(customerService.currentProfile()).thenReturn(ASHA);

            // When / Then
            assertThat(mvc.get().uri("/api/customers/me"))
                    .hasStatus(OK)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"id":2,"username":"asha","fullName":"Asha Rao","role":"CUSTOMER"}
                            """);
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        void me_whenAuthenticatedAsAdmin_alsoWorks() {
            // Given: the profile endpoints are behind anyRequest().authenticated(), not behind a
            // role — every account has a profile, whatever it is allowed to do with it
            when(customerService.currentProfile()).thenReturn(ASHA);

            // When / Then
            assertThat(mvc.get().uri("/api/customers/me")).hasStatus(OK);
        }

        @Test
        void me_whenAnonymous_returns401() {
            assertThat(mvc.get().uri("/api/customers/me"))
                    .hasStatus(UNAUTHORIZED)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":401,"message":"Authentication required. Log in at POST /api/auth/login and send the token as 'Authorization: Bearer <token>'."}
                            """);
            verify(customerService, never()).currentProfile();
        }

        @Test
        @WithMockUser(username = "asha", roles = "CUSTOMER")
        void updateMe_whenAuthenticated_changesTheDisplayName() {
            // Given
            when(customerService.updateCurrentProfile(any(UpdateProfileRequest.class)))
                    .thenReturn(new CustomerResponse(
                            2L, "asha", "Asha M. Rao", Role.CUSTOMER, ASHA.createdAt()));

            // When / Then
            assertThat(mvc.put().uri("/api/customers/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"fullName":"Asha M. Rao"}"""))
                    .hasStatus(OK)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"fullName":"Asha M. Rao"}
                            """);
        }

        @Test
        void updateMe_whenAnonymous_returns401() {
            assertThat(mvc.put().uri("/api/customers/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"fullName":"Asha M. Rao"}"""))
                    .hasStatus(UNAUTHORIZED);
            verify(customerService, never()).updateCurrentProfile(any());
        }
    }
}
