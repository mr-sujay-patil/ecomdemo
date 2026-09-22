## Phase 08: Spring Security (tag: phase-08-complete, PR #8)
**What exists now:** The application knows who is calling. `users` (V5) holds BCrypt-hashed
accounts with roles CUSTOMER and ADMIN; the ADMIN (`admin`/`admin123`) is seeded by the
migration because registration always creates a CUSTOMER. HTTP Basic on a stateless chain:
product reads public, product writes ADMIN, cart and orders CUSTOMER, everything else
authenticated. V6 gave every account its own cart and stamped every order with its owner, so the
shared cart is gone and a stranger's order is a 403. 186 tests (163 + 23), smoke test 110 checks.
**Key code:** `security/SecurityConfig` (the filter chain and the ordered rules, `@EnableWebSecurity`
+ `@EnableMethodSecurity`, `BCryptPasswordEncoder` bean, CSRF off, sessions STATELESS);
`security/AppUserDetailsService` + `AppUserDetails` (the adapter that adds the `ROLE_` prefix and
carries the account id); `security/CurrentUser` (a bean over `SecurityContextHolder`, injected by
`CartService`, `OrderService`, `OrderPlacementService`, `CustomerService`);
`security/ApiErrorWriter` + `ApiErrorAuthenticationEntryPoint` + `ApiErrorAccessDeniedHandler`
(401/403 in the `ApiError` shape); `customer/` (User, Role, UserRepository, CustomerService,
CustomerController, dto/). `OrderService.findAll` is `@PreAuthorize("hasRole('CUSTOMER')")` over a
user-scoped query; `findById` adds `@PostAuthorize("returnObject.username() == authentication.name")`.
**Config & infrastructure:** New dependencies `spring-boot-starter-security` and (test)
`spring-boot-starter-security-test`; Spring Security 7.1.1 from the BOM. No new properties.
Migrations V5 (`users` + seeded admin) and V6 (`cart.user_id` UNIQUE NOT NULL, `orders.user_id`
NOT NULL + `idx_orders_user`) applied incrementally to the live Phase 7 database, now at v6.
OpenAPI declares a `basicAuth` scheme, so Swagger UI has an Authorize button.
**Tests:** +43 unit/slice (`CustomerServiceTest` 8, `CustomerControllerTest` 11,
`AppUserDetailsServiceTest` 4, an `Access` nest in each controller slice, V5/V6 assertions in
`FlywayMigrationTest`, security assertions in `OpenApiDocumentationTest`); +8 IT. New test support:
`support/WithSecurityRules` (imports the real `SecurityConfig` into a `@WebMvcTest`) and
`support/TestAuthentication` (signs a `@SpringBootTest` in as a persisted account). Test report:
`docs/test-reports/phase-08.md`.
**Gotchas:** `@WebMvcTest` auto-configures Spring Security but does NOT pick up your own
`SecurityFilterChain` — without an explicit import the slice runs Boot's "authenticate everything"
fallback and a 401 assertion passes while proving nothing. `@WithMockUser` cannot be used in the
`@SpringBootTest` classes: `CurrentUser` needs an `AppUserDetails` with a real database id.
Spring Boot 4 defines no `com.fasterxml.jackson.databind.ObjectMapper` bean (Jackson 3's
`tools.jackson.databind.json.JsonMapper` is the one to inject) even though Jackson 2 is on the
classpath. `SecurityContextHolder` is a ThreadLocal, so `ConcurrentCheckoutTest`'s worker threads
must authenticate themselves. URL rules are ordered and first-match-wins: the public GET rule for
products must precede the ADMIN rule. macOS bash 3.2 makes `"${arr[@]}"` on an empty array an
error under `set -u`, which is why the smoke test's auth code uses no arrays.
**Follow-ups (not done, out of scope):** JWT instead of Basic, so a BCrypt verification is not paid
per request — Phase 9. Password change, account lockout and login rate limiting — not planned.
An admin view over all orders — not planned; the repository deliberately has no "all orders" query.
