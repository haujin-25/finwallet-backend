package finwallet_api.wallet.controller;

import finwallet_api.common.security.UserPrincipal;
import finwallet_api.wallet.dto.WalletResponse;
import finwallet_api.wallet.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/me")
    public ResponseEntity<WalletResponse> getMyWallet(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return ResponseEntity.ok(walletService.getWalletByUserId(principal.id()));
        }
        return ResponseEntity.ok(walletService.getWalletByEmail(authentication.getName()));
    }
}
