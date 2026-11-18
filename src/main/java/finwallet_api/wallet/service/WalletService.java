package finwallet_api.wallet.service;

import finwallet_api.common.exception.ResourceNotFoundException;
import finwallet_api.common.exception.WalletAlreadyExistsException;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.dto.WalletResponse;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private static final String DEFAULT_CURRENCY = "MYR";
    private static final long DEFAULT_BALANCE = 0L;
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String DUPLICATE_WALLET_MESSAGE = "User already has a wallet";

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public WalletService(WalletRepository walletRepository, UserRepository userRepository) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Wallet createWalletForUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }

        if (walletRepository.existsByUserId(userId)) {
            throw new WalletAlreadyExistsException(DUPLICATE_WALLET_MESSAGE);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setCurrency(DEFAULT_CURRENCY);
        wallet.setBalance(DEFAULT_BALANCE);
        wallet.setStatus(ACTIVE_STATUS);
        wallet.setCreatedAt(now);
        wallet.setUpdatedAt(now);

        try {
            return walletRepository.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateWallet(exception)) {
                throw new WalletAlreadyExistsException(DUPLICATE_WALLET_MESSAGE);
            }
            throw exception;
        }
    }

    @Transactional
    public Wallet createWallet(Long userId) {
        return createWalletForUser(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findByUserId(Long userId) {
        return walletRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        return toResponse(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or blank");
        }
        if (userRepository == null) {
            throw new IllegalStateException("UserRepository is not configured");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return getWalletByUserId(user.getId());
    }

    private WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus()
        );
    }

    private boolean isDuplicateWallet(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (!(cause instanceof SQLException sqlException) || !"23505".equals(sqlException.getSQLState())) {
            return false;
        }

        String message = sqlException.getMessage();
        return message != null && message.contains("wallets_user_id_key");
    }
}
