package finwallet_api.wallet.dto;

public record WalletResponse(
        Long walletId,
        String currency,
        Long balance,
        String status
) {}
