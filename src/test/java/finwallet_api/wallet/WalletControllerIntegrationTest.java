package finwallet_api.wallet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finwallet_api.common.security.JwtService;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WalletControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User userA;
    private User userB;
    private Wallet walletA;
    private Wallet walletB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        userA = new User();
        userA.setEmail("usera@finwallet.test");
        userA.setPasswordHash(passwordEncoder.encode("Password123!"));
        userA.setFullName("User Alpha");
        userA.setStatus("ACTIVE");
        userA.setCreatedAt(now);
        userA.setUpdatedAt(now);
        userA = userRepository.saveAndFlush(userA);

        walletA = new Wallet();
        walletA.setUserId(userA.getId());
        walletA.setCurrency("MYR");
        walletA.setBalance(12550L);
        walletA.setStatus("ACTIVE");
        walletA.setCreatedAt(now);
        walletA.setUpdatedAt(now);
        walletA = walletRepository.saveAndFlush(walletA);

        userB = new User();
        userB.setEmail("userb@finwallet.test");
        userB.setPasswordHash(passwordEncoder.encode("Password123!"));
        userB.setFullName("User Beta");
        userB.setStatus("ACTIVE");
        userB.setCreatedAt(now);
        userB.setUpdatedAt(now);
        userB = userRepository.saveAndFlush(userB);

        walletB = new Wallet();
        walletB.setUserId(userB.getId());
        walletB.setCurrency("MYR");
        walletB.setBalance(499900L);
        walletB.setStatus("ACTIVE");
        walletB.setCreatedAt(now);
        walletB.setUpdatedAt(now);
        walletB = walletRepository.saveAndFlush(walletB);

        tokenA = jwtService.generateToken(userA.getEmail(), userA.getId());
        tokenB = jwtService.generateToken(userB.getEmail(), userB.getId());
    }

    @Test
    void authenticatedUserCanRetrieveTheirOwnWallet() throws Exception {
        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletA.getId()))
                .andExpect(jsonPath("$.currency").value("MYR"))
                .andExpect(jsonPath("$.balance").value(12550L))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void returnedBalanceAndCurrencyMatchDatabaseValues() throws Exception {
        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletB.getId()))
                .andExpect(jsonPath("$.currency").value(walletB.getCurrency()))
                .andExpect(jsonPath("$.balance").value(walletB.getBalance()))
                .andExpect(jsonPath("$.status").value(walletB.getStatus()));
    }

    @Test
    void userCannotRetrieveAnotherUsersWallet() throws Exception {
        // User A requests /api/wallets/me with User A's token -> receives Wallet A, NOT Wallet B
        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletA.getId()))
                .andExpect(jsonPath("$.balance").value(12550L));

        // User B requests /api/wallets/me with User B's token -> receives Wallet B, NOT Wallet A
        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletB.getId()))
                .andExpect(jsonPath("$.balance").value(499900L));
    }

    @Test
    void unauthenticatedRequestReceives401() throws Exception {
        mockMvc.perform(get("/api/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void userWithoutWalletReceives404() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        User userWithoutWallet = new User();
        userWithoutWallet.setEmail("nowallet@finwallet.test");
        userWithoutWallet.setPasswordHash(passwordEncoder.encode("Password123!"));
        userWithoutWallet.setFullName("No Wallet User");
        userWithoutWallet.setStatus("ACTIVE");
        userWithoutWallet.setCreatedAt(now);
        userWithoutWallet.setUpdatedAt(now);
        userWithoutWallet = userRepository.saveAndFlush(userWithoutWallet);

        String token = jwtService.generateToken(userWithoutWallet.getEmail(), userWithoutWallet.getId());

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void responseDoesNotExposeInternalOrSensitiveEntityFields() throws Exception {
        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").exists())
                .andExpect(jsonPath("$.currency").exists())
                .andExpect(jsonPath("$.balance").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
