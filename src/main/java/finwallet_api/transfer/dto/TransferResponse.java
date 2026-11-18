package finwallet_api.transfer.dto;

import java.time.OffsetDateTime;

public record TransferResponse(
        String transferReference,
        Long senderWalletId,
        Long recipientWalletId,
        Long amount,
        String currency,
        String status,
        String description,
        OffsetDateTime createdAt
) {
}
