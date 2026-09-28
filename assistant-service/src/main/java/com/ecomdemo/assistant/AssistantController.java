package com.ecomdemo.assistant;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
@Tag(name = "Assistant", description = "A shopping assistant grounded in the store's products, policies and your orders")
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    @Operation(
            summary = "Ask the shopping assistant",
            description = "Answers from the store's policies and from what its tools find: product search, "
                    + "the status of YOUR orders, and proposing a cart addition (which you then confirm). "
                    + "Send the returned conversationId back to continue the conversation.")
    @ApiResponse(responseCode = "200", description = "The answer, what it was based on, and any proposed cart addition")
    @ApiResponse(responseCode = "400", description = "No message, a message over 1000 characters, or a malformed conversationId",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "No model configured, or the model did not answer (see Retry-After)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public AssistantReply chat(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody ChatRequest request) {
        return assistant.chat(caller, request);
    }

    @PostMapping("/actions/{actionId}/confirm")
    @Operation(
            summary = "Confirm a cart addition the assistant proposed",
            description = "Adds the product to your cart. Each proposal can be confirmed once, only by you, "
                    + "and expires after 10 minutes.")
    @ApiResponse(responseCode = "200", description = "Added; the cart's new total")
    @ApiResponse(responseCode = "404", description = "No such proposal for you: expired, already confirmed, or never made",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ConfirmedAddition confirm(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pendingAction.id from an answer") @PathVariable String actionId) {
        return assistant.confirm(caller, actionId);
    }
}
