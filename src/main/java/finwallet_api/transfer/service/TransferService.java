package finwallet_api.transfer.service;

import finwallet_api.common.exception.CurrencyMismatchException;
import finwallet_api.common.exception.IdempotencyConflictException;
import finwallet_api.common.exception.InactiveWalletException;
import finwallet_api.common.exception.InsufficientFundsException;
import finwallet_api.common.exception.ResourceNotFoundException;
import finwallet_api.common.exception.SelfTransferException;
import finwallet_api.transfer.dto.TransferRequest;
import finwallet_api.transfer.dto.TransferResponse;
import finwallet_api.transfer.entity.LedgerEntry;
import finwallet_api.transfer.entity.Transfer;
import finwallet_api.transfer.repository.LedgerEntryRepository;
import finwallet_api.transfer.repository.TransferRepository;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public TransferService(
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            WalletRepository walletRepository,
            UserRepository userRepository
    ) {
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TransferResponse transfer(Long senderUserId, TransferRequest request) {
        // 1. Resolve sender wallet
        Wallet senderWallet = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found"));

        // 2. Resolve recipient user and wallet
        User recipientUser = userRepository.findByEmail(request.recipientEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No user found with email: " + request.recipientEmail()));

        Wallet recipientWallet = walletRepository.findByUserId(recipientUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient wallet not found"));

        // 3. Self-transfer check
        if (senderWallet.getId().equals(recipientWallet.getId())) {
            throw new SelfTransferException("Cannot transfer money to your own wallet");
        }

        // 4. Status checks
        if (!"ACTIVE".equals(senderWallet.getStatus())) {
            throw new InactiveWalletException("Sender wallet is not active");
        }
        if (!"ACTIVE".equals(recipientWallet.getStatus())) {
            throw new InactiveWalletException("Recipient wallet is not active");
        }

        // 5. Currency check
        if (!senderWallet.getCurrency().equals(recipientWallet.getCurrency())) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: sender uses " + senderWallet.getCurrency()
                    + ", recipient uses " + recipientWallet.getCurrency());
        }

        // 6. Idempotency check (before acquiring locks)
        Optional<Transfer> existing = transferRepository.findBySenderWalletIdAndIdempotencyKey(
                senderWallet.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            Transfer existingTransfer = existing.get();
            // Validate that this is the same request, not a conflicting reuse of the key
            if (!existingTransfer.getRecipientWalletId().equals(recipientWallet.getId())
                    || !existingTransfer.getAmount().equals(request.amount())) {
                throw new IdempotencyConflictException(
                        "Idempotency key already used for a different transfer");
            }
            // Same details — idempotent replay; return existing result
            return toResponse(existingTransfer);
        }

        // 7. Acquire pessimistic locks in deterministic order (ascending wallet ID) to prevent deadlocks
        Long firstLockId = Math.min(senderWallet.getId(), recipientWallet.getId());
        Long secondLockId = Math.max(senderWallet.getId(), recipientWallet.getId());

        Wallet lockedFirst = walletRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + firstLockId));
        Wallet lockedSecond = walletRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + secondLockId));

        // Re-resolve sender / recipient from the locked wallets
        Wallet lockedSender = lockedFirst.getId().equals(senderWallet.getId()) ? lockedFirst : lockedSecond;
        Wallet lockedRecipient = lockedFirst.getId().equals(recipientWallet.getId()) ? lockedFirst : lockedSecond;

        // 8. Re-check status after acquiring lock (another transaction might have changed it)
        if (!"ACTIVE".equals(lockedSender.getStatus())) {
            throw new InactiveWalletException("Sender wallet is not active");
        }
        if (!"ACTIVE".equals(lockedRecipient.getStatus())) {
            throw new InactiveWalletException("Recipient wallet is not active");
        }

        // 9. Funds check (after lock — authoritative balance)
        if (lockedSender.getBalance() < request.amount()) {
            throw new InsufficientFundsException("Insufficient funds in sender wallet");
        }

        // 10. Overflow guard on recipient side
        if (Long.MAX_VALUE - lockedRecipient.getBalance() < request.amount()) {
            throw new ArithmeticException("Recipient balance overflow");
        }

        // 11. Generate unique transfer reference
        String reference = generateReference();

        // 12. Persist the Transfer record — catch idempotency constraint race
        Transfer transferRecord = new Transfer();
        transferRecord.setReference(reference);
        transferRecord.setSenderWalletId(lockedSender.getId());
        transferRecord.setRecipientWalletId(lockedRecipient.getId());
        transferRecord.setAmount(request.amount());
        transferRecord.setCurrency(lockedSender.getCurrency());
        transferRecord.setDescription(request.description());
        transferRecord.setStatus("COMPLETED");
        transferRecord.setIdempotencyKey(request.idempotencyKey());
        transferRecord.setCreatedAt(OffsetDateTime.now());

        try {
            transferRecord = transferRepository.saveAndFlush(transferRecord);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request with same idempotency key reached the DB first
            Optional<Transfer> race = transferRepository.findBySenderWalletIdAndIdempotencyKey(
                    lockedSender.getId(), request.idempotencyKey());
            if (race.isPresent()) {
                Transfer raceTransfer = race.get();
                if (!raceTransfer.getRecipientWalletId().equals(lockedRecipient.getId())
                        || !raceTransfer.getAmount().equals(request.amount())) {
                    throw new IdempotencyConflictException(
                            "Idempotency key already used for a different transfer");
                }
                return toResponse(raceTransfer);
            }
            throw e;
        }

        // 13. Update balances
        lockedSender.setBalance(lockedSender.getBalance() - request.amount());
        lockedSender.setUpdatedAt(OffsetDateTime.now());
        walletRepository.save(lockedSender);

        lockedRecipient.setBalance(lockedRecipient.getBalance() + request.amount());
        lockedRecipient.setUpdatedAt(OffsetDateTime.now());
        walletRepository.save(lockedRecipient);

        // 14. Write double-entry ledger entries
        LedgerEntry debit = new LedgerEntry();
        debit.setTransferId(transferRecord.getId());
        debit.setWalletId(lockedSender.getId());
        debit.setEntryType("DEBIT");
        debit.setAmount(request.amount());
        debit.setCurrency(lockedSender.getCurrency());
        debit.setCreatedAt(OffsetDateTime.now());
        ledgerEntryRepository.save(debit);

        LedgerEntry credit = new LedgerEntry();
        credit.setTransferId(transferRecord.getId());
        credit.setWalletId(lockedRecipient.getId());
        credit.setEntryType("CREDIT");
        credit.setAmount(request.amount());
        credit.setCurrency(lockedRecipient.getCurrency());
        credit.setCreatedAt(OffsetDateTime.now());
        ledgerEntryRepository.save(credit);

        return toResponse(transferRecord);
    }

    private String generateReference() {
        String date = LocalDate.now().toString().replace("-", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "TRF-" + date + "-" + suffix;
    }

    private TransferResponse toResponse(Transfer transfer) {
        return new TransferResponse(
                transfer.getReference(),
                transfer.getSenderWalletId(),
                transfer.getRecipientWalletId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getDescription(),
                transfer.getCreatedAt()
        );
    }
}
