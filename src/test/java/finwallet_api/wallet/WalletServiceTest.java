package finwallet_api.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finwallet_api.common.exception.ResourceNotFoundException;
import finwallet_api.common.exception.WalletAlreadyExistsException;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.dto.WalletResponse;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import finwallet_api.wallet.service.WalletService;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private UserRepository userRepository;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, userRepository);
    }

    @Test
    void createWalletForUserCreatesMyrWalletWithZeroBalance() {
        Long userId = 100L;
        when(walletRepository.existsByUserId(userId)).thenReturn(false);
        when(walletRepository.saveAndFlush(any(Wallet.class))).thenAnswer(invocation -> {
            Wallet w = invocation.getArgument(0);
            w.setId(1L);
            return w;
        });

        Wallet created = walletService.createWalletForUser(userId);

        assertNotNull(created);
        assertEquals(1L, created.getId());
        assertEquals(userId, created.getUserId());
        assertEquals("MYR", created.getCurrency());
        assertEquals(0L, created.getBalance());
        assertEquals("ACTIVE", created.getStatus());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());

        ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).saveAndFlush(captor.capture());
        Wallet saved = captor.getValue();
        assertEquals(userId, saved.getUserId());
        assertEquals("MYR", saved.getCurrency());
        assertEquals(0L, saved.getBalance());
        assertEquals("ACTIVE", saved.getStatus());
    }

    @Test
    void createWalletForUserRejectsNullUserId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> walletService.createWalletForUser(null)
        );
        assertEquals("User ID must not be null", ex.getMessage());
    }

    @Test
    void createWalletForUserRejectsDuplicateWhenAlreadyExists() {
        Long userId = 100L;
        when(walletRepository.existsByUserId(userId)).thenReturn(true);

        WalletAlreadyExistsException ex = assertThrows(
                WalletAlreadyExistsException.class,
                () -> walletService.createWalletForUser(userId)
        );
        assertEquals("User already has a wallet", ex.getMessage());
    }

    @Test
    void createWalletForUserRejectsDuplicateOnDataIntegrityViolation() {
        Long userId = 100L;
        when(walletRepository.existsByUserId(userId)).thenReturn(false);

        SQLException sqlException = new SQLException("duplicate key value violates unique constraint \"wallets_user_id_key\"", "23505");
        DataIntegrityViolationException dataIntegrityViolationException =
                new DataIntegrityViolationException("Unique index violation", sqlException);

        when(walletRepository.saveAndFlush(any(Wallet.class))).thenThrow(dataIntegrityViolationException);

        WalletAlreadyExistsException ex = assertThrows(
                WalletAlreadyExistsException.class,
                () -> walletService.createWalletForUser(userId)
        );
        assertEquals("User already has a wallet", ex.getMessage());
    }

    @Test
    void findByUserIdReturnsWallet() {
        Long userId = 100L;
        Wallet wallet = new Wallet();
        wallet.setId(1L);
        wallet.setUserId(userId);
        wallet.setBalance(0L);
        wallet.setCurrency("MYR");

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        Optional<Wallet> found = walletService.findByUserId(userId);

        assertTrue(found.isPresent());
        assertEquals(userId, found.get().getUserId());
    }

    @Test
    void getWalletByUserIdReturnsWalletResponse() {
        Long userId = 100L;
        Wallet wallet = new Wallet();
        wallet.setId(101L);
        wallet.setUserId(userId);
        wallet.setCurrency("MYR");
        wallet.setBalance(5000L);
        wallet.setStatus("ACTIVE");

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWalletByUserId(userId);

        assertNotNull(response);
        assertEquals(101L, response.walletId());
        assertEquals("MYR", response.currency());
        assertEquals(5000L, response.balance());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void getWalletByUserIdThrowsResourceNotFoundExceptionWhenWalletNotFound() {
        Long userId = 100L;
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> walletService.getWalletByUserId(userId)
        );
        assertEquals("Wallet not found", ex.getMessage());
    }

    @Test
    void getWalletByUserIdRejectsNullUserId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> walletService.getWalletByUserId(null)
        );
        assertEquals("User ID must not be null", ex.getMessage());
    }

    @Test
    void getWalletByEmailReturnsWalletResponse() {
        String email = "trader@finwallet.test";
        User user = new User();
        user.setId(200L);
        user.setEmail(email);

        Wallet wallet = new Wallet();
        wallet.setId(201L);
        wallet.setUserId(200L);
        wallet.setCurrency("MYR");
        wallet.setBalance(15000L);
        wallet.setStatus("ACTIVE");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(walletRepository.findByUserId(200L)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWalletByEmail(email);

        assertNotNull(response);
        assertEquals(201L, response.walletId());
        assertEquals("MYR", response.currency());
        assertEquals(15000L, response.balance());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void getWalletByEmailThrowsResourceNotFoundExceptionWhenUserNotFound() {
        String email = "missing@finwallet.test";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> walletService.getWalletByEmail(email)
        );
        assertEquals("User not found", ex.getMessage());
    }
}
