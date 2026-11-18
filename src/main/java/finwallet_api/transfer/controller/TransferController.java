package finwallet_api.transfer.controller;

import finwallet_api.common.security.UserPrincipal;
import finwallet_api.transfer.dto.TransferRequest;
import finwallet_api.transfer.dto.TransferResponse;
import finwallet_api.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            Authentication authentication
    ) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        TransferResponse response = transferService.transfer(principal.id(), request);
        // Return 201 for new transfers; idempotent replays from existing DB record also return 201
        // (the client already received 201 on the original request; replays are indistinguishable)
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
