package finwallet_api.transfer;

import finwallet_api.common.exception.CurrencyMismatchException;
import finwallet_api.common.exception.IdempotencyConflictException;
import finwallet_api.common.exception.InactiveWalletException;
import finwallet_api.common.exception.InsufficientFundsException;
import finwallet_api.common.exception.ResourceNotFoundException;
import finwallet_api.common.exception.SelfTransferException;
import finwallet_api.transfer.dto.TransferRequest;
import finwallet_api.transfer.dto.TransferResponse;
import finwallet_api.transfer.entity.Transfer;
import finwallet_api.transfer.repository.LedgerEntryRepository;
import finwallet_api.transfer.repository.TransferRepository;
import finwallet_api.transfer.service.TransferService;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    private TransferService transferService;

    private Wallet senderWallet;
    private Wallet recipientWallet;
    private User recipientUser;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                transferRepository, ledgerEntryRepository, walletRepository, userRepository);

        senderWallet = new Wallet();
        senderWallet.setId(1L);
        senderWallet.setUserId(10L);
        senderWallet.setCurrency("MYR");
        senderWallet.setBalance(10000L);
        senderWallet.setStatus("ACTIVE");
        senderWallet.setCreatedAt(OffsetDateTime.now());
        senderWallet.setUpdatedAt(OffsetDateTime.now());

        recipientUser = new User();
        recipientUser.setId(20L);
        recipientUser.setEmail("recipient@example.com");

        recipientWallet = new Wallet();
        recipientWallet.setId(2L);
        recipientWallet.setUserId(20L);
        recipientWallet.setCurrency("MYR");
        recipientWallet.setBalance(5000L);
        recipientWallet.setStatus("ACTIVE");
        recipientWallet.setCreatedAt(OffsetDateTime.now());
        recipientWallet.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void transfer_success_returnsTransferResponse() {
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 5000L, "Dinner", "key-001");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));
        when(transferRepository.findBySenderWalletIdAndIdempotencyKey(1L, "key-001"))
                .thenReturn(Optional.empty());
        when(walletRepository.findByIdWithLock(1L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdWithLock(2L)).thenReturn(Optional.of(recipientWallet));

        Transfer saved = new Transfer();
        saved.setId(100L);
        saved.setReference("TRF-20261118-ABCD1234");
        saved.setSenderWalletId(1L);
        saved.setRecipientWalletId(2L);
        saved.setAmount(5000L);
        saved.setCurrency("MYR");
        saved.setDescription("Dinner");
        saved.setStatus("COMPLETED");
        saved.setIdempotencyKey("key-001");
        saved.setCreatedAt(OffsetDateTime.now());
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenReturn(saved);
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response = transferService.transfer(10L, request);

        assertThat(response.amount()).isEqualTo(5000L);
        assertThat(response.currency()).isEqualTo("MYR");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(senderWallet.getBalance()).isEqualTo(5000L);
        assertThat(recipientWallet.getBalance()).isEqualTo(10000L);
    }

    @Test
    void transfer_insufficientFunds_throwsException() {
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 99999L, null, "key-002");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));
        when(transferRepository.findBySenderWalletIdAndIdempotencyKey(1L, "key-002"))
                .thenReturn(Optional.empty());
        when(walletRepository.findByIdWithLock(1L)).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByIdWithLock(2L)).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void transfer_selfTransfer_throwsException() {
        recipientWallet.setId(1L); // same wallet as sender
        recipientUser.setId(10L);

        TransferRequest request = new TransferRequest(
                "recipient@example.com", 100L, null, "key-003");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(SelfTransferException.class);
    }

    @Test
    void transfer_senderWalletInactive_throwsException() {
        senderWallet.setStatus("SUSPENDED");
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 100L, null, "key-004");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(InactiveWalletException.class)
                .hasMessageContaining("Sender");
    }

    @Test
    void transfer_recipientWalletInactive_throwsException() {
        recipientWallet.setStatus("CLOSED");
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 100L, null, "key-005");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(InactiveWalletException.class)
                .hasMessageContaining("Recipient");
    }

    @Test
    void transfer_currencyMismatch_throwsException() {
        recipientWallet.setCurrency("USD");
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 100L, null, "key-006");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void transfer_idempotencyReplay_returnsSameResult() {
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 5000L, "Dinner", "key-007");

        Transfer existing = new Transfer();
        existing.setId(50L);
        existing.setReference("TRF-20261118-EXISTING");
        existing.setSenderWalletId(1L);
        existing.setRecipientWalletId(2L);
        existing.setAmount(5000L);
        existing.setCurrency("MYR");
        existing.setDescription("Dinner");
        existing.setStatus("COMPLETED");
        existing.setIdempotencyKey("key-007");
        existing.setCreatedAt(OffsetDateTime.now());

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));
        when(transferRepository.findBySenderWalletIdAndIdempotencyKey(1L, "key-007"))
                .thenReturn(Optional.of(existing));

        TransferResponse response = transferService.transfer(10L, request);

        assertThat(response.transferReference()).isEqualTo("TRF-20261118-EXISTING");
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void transfer_idempotencyConflict_throwsException() {
        TransferRequest request = new TransferRequest(
                "recipient@example.com", 5000L, "Dinner", "key-008");

        Transfer existing = new Transfer();
        existing.setId(50L);
        existing.setSenderWalletId(1L);
        existing.setRecipientWalletId(2L);
        existing.setAmount(9999L); // different amount = conflict
        existing.setIdempotencyKey("key-008");
        existing.setCreatedAt(OffsetDateTime.now());

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipientUser));
        when(walletRepository.findByUserId(20L)).thenReturn(Optional.of(recipientWallet));
        when(transferRepository.findBySenderWalletIdAndIdempotencyKey(1L, "key-008"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void transfer_recipientNotFound_throwsException() {
        TransferRequest request = new TransferRequest(
                "ghost@example.com", 100L, null, "key-009");

        when(walletRepository.findByUserId(10L)).thenReturn(Optional.of(senderWallet));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(10L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
