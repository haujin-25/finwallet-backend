package finwallet_api.wallet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import finwallet_api.user.dto.UserRegistrationRequest;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.user.service.UserService;
import finwallet_api.wallet.repository.WalletRepository;
import finwallet_api.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class WalletRegistrationRollbackIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @MockitoSpyBean
    private WalletService walletService;

    @Test
    void userRegistrationRollsBackWhenWalletCreationFails() {
        String email = "rollbacktest@finwallet.test";
        UserRegistrationRequest request = new UserRegistrationRequest(
                email,
                "SecurePassword123!",
                "Rollback Test User"
        );

        doThrow(new RuntimeException("Simulated wallet creation failure"))
                .when(walletService).createWalletForUser(any());

        assertThrows(RuntimeException.class, () -> userService.register(request));

        // Verify that the user was NOT persisted in database
        assertTrue(userRepository.findByEmail(email).isEmpty(),
                "User must be rolled back from the database when wallet creation fails");

        // Verify that no wallet exists for this email
        assertTrue(walletRepository.findAll().stream().noneMatch(w ->
                userRepository.findById(w.getUserId()).map(u -> email.equals(u.getEmail())).orElse(false)));
    }
}
