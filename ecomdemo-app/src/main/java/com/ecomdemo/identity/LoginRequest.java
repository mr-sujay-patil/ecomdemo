package com.ecomdemo.identity;

import jakarta.validation.constraints.NotBlank;

/**
 * The login request body.
 *
 * <p><strong>A top-level record, not one nested in the controller</strong>, and that is not a style
 * preference — the nested version produced "Malformed request body" on every login. Every other DTO in
 * this project is top-level, and the one time that convention was broken it cost a debugging session
 * against a stack where sixteen containers were all reporting healthy.
 *
 * <p>Validated here as well as in customer-service. Not duplication for its own sake: a blank username
 * should be a 400 from the edge the caller is talking to, not a round trip that comes back as one.
 * customer-service validates again, because a boundary that trusts its caller is not a boundary.
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") String username,
        @NotBlank(message = "must not be blank") String password) {
}
