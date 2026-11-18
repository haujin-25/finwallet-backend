package finwallet_api.transfer;

import finwallet_api.common.security.JwtService;
import finwallet_api.transfer.repository.LedgerEntryRepository;
import finwallet_api.transfer.repository.TransferRepository;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency tests for the transfer endpoint.
 *
 * <p>These tests do NOT use @Transactional. Concurrent threads need to operate on committed
 * database rows to exercise real PostgreSQL row-level locking. Each test commits its data
 * and cleans up manually in @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User userA;
    private User userB;
    private User userC;
    private Wallet walletA;
    private Wallet walletB;
    private Wallet walletC;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        userA = createUser("concurrency-a@finwallet.test", now);
        walletA = createWallet(userA.getId(), 50000L, now);
        tokenA = jwtService.generateToken(userA.getEmail(), userA.getId());

        userB = createUser("concurrency-b@finwallet.test", now);
        walletB = createWallet(userB.getId(), 50000L, now);
        tokenB = jwtService.generateToken(userB.getEmail(), userB.getId());

        userC = createUser("concurrency-c@finwallet.test", now);
        walletC = createWallet(userC.getId(), 0L, now);
    }

    @AfterEach
    void tearDown() {
        // Delete in FK dependency order: ledger_entries → transfers → wallets → users
        ledgerEntryRepository.deleteAll(
                ledgerEntryRepository.findAll().stream()
                        .filter(e -> isOwnedByTestWallet(e.getWalletId()))
                        .toList()
        );
        transferRepository.deleteAll(
                transferRepository.findAll().stream()
                        .filter(t -> isOwnedByTestWallet(t.getSenderWalletId())
                                || isOwnedByTestWallet(t.getRecipientWalletId()))
                        .toList()
        );
        safeDeleteWallet(walletA);
        safeDeleteWallet(walletB);
        safeDeleteWallet(walletC);
        safeDeleteUser(userA);
        safeDeleteUser(userB);
        safeDeleteUser(userC);
    }

    private boolean isOwnedByTestWallet(Long walletId) {
        return walletId != null && (walletId.equals(walletA.getId())
                || walletId.equals(walletB.getId())
                || walletId.equals(walletC.getId()));
    }

    private void safeDeleteWallet(Wallet wallet) {
        if (wallet != null && wallet.getId() != null) {
            walletRepository.findById(wallet.getId()).ifPresent(walletRepository::delete);
        }
    }

    private void safeDeleteUser(User user) {
        if (user != null && user.getId() != null) {
            userRepository.findById(user.getId()).ifPresent(userRepository::delete);
        }
    }

    @Test
    void concurrentTransfersFromSameSenderDoNotCreateNegativeBalance() throws Exception {
        // Send 10 concurrent requests each transferring 8000 sen from A (balance=50000).
        // Only 6 can succeed (6 * 8000 = 48000 <= 50000); 7th onward must fail with 422.
        int threadCount = 10;
        long transferAmount = 8000L;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + tokenA)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "recipientEmail": "%s",
                                              "amount": %d,
                                              "description": "Concurrent %d",
                                              "idempotencyKey": "concurrency-key-a-%d"
                                            }
                                            """.formatted(userC.getEmail(), transferAmount, idx, idx)))
                            .andReturn();
                } catch (Exception ignored) {
                    // Some threads may fail — that's expected
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Core invariant: balance must NEVER go below zero
        Wallet finalSender = walletRepository.findByUserId(userA.getId()).orElseThrow();
        assertThat(finalSender.getBalance()).isGreaterThanOrEqualTo(0L);

        // The actual balance must be a valid multiple: 50000 - (k * 8000) for some k in [0..6]
        long remainingBalance = finalSender.getBalance();
        long deducted = 50000L - remainingBalance;
        assertThat(deducted % transferAmount).isEqualTo(0L);
        assertThat(deducted / transferAmount).isLessThanOrEqualTo(6L);

        // Recipient balance must equal what was deducted from sender
        Wallet finalRecipient = walletRepository.findByUserId(userC.getId()).orElseThrow();
        assertThat(finalRecipient.getBalance()).isEqualTo(deducted);
    }

    @Test
    void sameIdempotencyKeyFromConcurrentThreadsCreatesOnlyOneTransfer() throws Exception {
        // Send the same idempotency key concurrently — only one transfer must be created
        int threadCount = 5;
        String sharedKey = "concurrency-idem-shared-key";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    var result = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + tokenA)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "recipientEmail": "%s",
                                              "amount": 500,
                                              "description": "Same key",
                                              "idempotencyKey": "%s"
                                            }
                                            """.formatted(userC.getEmail(), sharedKey)))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // expected for some concurrent threads
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Exactly one transfer record for this idempotency key
        long transferCount = transferRepository.findAll().stream()
                .filter(t -> sharedKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(transferCount).isEqualTo(1L);

        // Sender balance should have only decreased by 500 (one real transfer)
        Wallet finalSender = walletRepository.findByUserId(userA.getId()).orElseThrow();
        assertThat(finalSender.getBalance()).isEqualTo(49500L);
    }

    // ---- helpers ----

    private User createUser(String email, OffsetDateTime now) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode("Password123!"));
        u.setFullName("Concurrency User");
        u.setStatus("ACTIVE");
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.saveAndFlush(u);
    }

    private Wallet createWallet(Long userId, long balance, OffsetDateTime now) {
        Wallet w = new Wallet();
        w.setUserId(userId);
        w.setCurrency("MYR");
        w.setBalance(balance);
        w.setStatus("ACTIVE");
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        return walletRepository.saveAndFlush(w);
    }
}
