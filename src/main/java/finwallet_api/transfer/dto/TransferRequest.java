package finwallet_api.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotBlank(message = "Recipient email is required")
        String recipientEmail,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        Long amount,

        String description,

        @NotBlank(message = "Idempotency key is required")
        String idempotencyKey
) {
}
