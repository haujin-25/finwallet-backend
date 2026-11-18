package finwallet_api.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finwallet_api.common.exception.WalletAlreadyExistsException;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import finwallet_api.wallet.service.WalletService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WalletRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

    @Test
    void registrationAutomaticallyCreatesMyrWalletWithZeroBalance() throws Exception {
        String email = "walletowner@finwallet.test";
        String payload = """
                {
                  "email": "%s",
                  "password": "SecurePassword123!",
                  "fullName": "Wallet Owner"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(email));

        User user = userRepository.findByEmail(email).orElseThrow();
        Optional<Wallet> walletOptional = walletRepository.findByUserId(user.getId());

        assertTrue(walletOptional.isPresent(), "Wallet should be created for newly registered user");
        Wallet wallet = walletOptional.get();

        assertNotNull(wallet.getId());
        assertEquals(user.getId(), wallet.getUserId());
        assertEquals("MYR", wallet.getCurrency());
        assertEquals(0L, wallet.getBalance());
        assertEquals("ACTIVE", wallet.getStatus());
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());
    }

    @Test
    void userCannotHaveMultipleWalletsViaService() throws Exception {
        String email = "singlewallet@finwallet.test";
        String payload = """
                {
                  "email": "%s",
                  "password": "SecurePassword123!",
                  "fullName": "Single Wallet User"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail(email).orElseThrow();
        Long userId = user.getId();

        // Already has 1 wallet from registration
        assertTrue(walletRepository.findByUserId(userId).isPresent());

        // Attempt to create second wallet via service
        assertThrows(WalletAlreadyExistsException.class, () -> walletService.createWalletForUser(userId));

        // Verify still exactly 1 wallet in repository
        assertEquals(1, walletRepository.findAll().stream().filter(w -> w.getUserId().equals(userId)).count());
    }

    @Test
    void userCannotHaveMultipleWalletsViaDatabaseConstraint() throws Exception {
        String email = "dbconstraint@finwallet.test";
        String payload = """
                {
                  "email": "%s",
                  "password": "SecurePassword123!",
                  "fullName": "DB Constraint User"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail(email).orElseThrow();
        Long userId = user.getId();

        // Attempt direct repository save of duplicate wallet to trigger database unique constraint
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Wallet duplicateWallet = new Wallet();
        duplicateWallet.setUserId(userId);
        duplicateWallet.setCurrency("MYR");
        duplicateWallet.setBalance(0L);
        duplicateWallet.setStatus("ACTIVE");
        duplicateWallet.setCreatedAt(now);
        duplicateWallet.setUpdatedAt(now);

        assertThrows(DataIntegrityViolationException.class, () -> walletRepository.saveAndFlush(duplicateWallet));
    }
}
