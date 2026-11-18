package finwallet_api.transfer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finwallet_api.common.security.JwtService;
import finwallet_api.transfer.entity.LedgerEntry;
import finwallet_api.transfer.repository.LedgerEntryRepository;
import finwallet_api.transfer.repository.TransferRepository;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.entity.Wallet;
import finwallet_api.wallet.repository.WalletRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransferIntegrationTest {

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
    private Wallet walletA;
    private Wallet walletB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        userA = new User();
        userA.setEmail("transfer-a@finwallet.test");
        userA.setPasswordHash(passwordEncoder.encode("Password123!"));
        userA.setFullName("Transfer User A");
        userA.setStatus("ACTIVE");
        userA.setCreatedAt(now);
        userA.setUpdatedAt(now);
        userA = userRepository.saveAndFlush(userA);

        walletA = new Wallet();
        walletA.setUserId(userA.getId());
        walletA.setCurrency("MYR");
        walletA.setBalance(10000L);
        walletA.setStatus("ACTIVE");
        walletA.setCreatedAt(now);
        walletA.setUpdatedAt(now);
        walletA = walletRepository.saveAndFlush(walletA);

        userB = new User();
        userB.setEmail("transfer-b@finwallet.test");
        userB.setPasswordHash(passwordEncoder.encode("Password123!"));
        userB.setFullName("Transfer User B");
        userB.setStatus("ACTIVE");
        userB.setCreatedAt(now);
        userB.setUpdatedAt(now);
        userB = userRepository.saveAndFlush(userB);

        walletB = new Wallet();
        walletB.setUserId(userB.getId());
        walletB.setCurrency("MYR");
        walletB.setBalance(0L);
        walletB.setStatus("ACTIVE");
        walletB.setCreatedAt(now);
        walletB.setUpdatedAt(now);
        walletB = walletRepository.saveAndFlush(walletB);

        tokenA = jwtService.generateToken(userA.getEmail(), userA.getId());
        tokenB = jwtService.generateToken(userB.getEmail(), userB.getId());
    }

    private String transferPayload(String recipientEmail, long amount, String description, String idempotencyKey) {
        return """
                {
                  "recipientEmail": "%s",
                  "amount": %d,
                  "description": "%s",
                  "idempotencyKey": "%s"
                }
                """.formatted(recipientEmail, amount, description, idempotencyKey);
    }

    @Test
    void successfulTransferReturns201() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 3000L, "Test payment", "idem-001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferReference").isString())
                .andExpect(jsonPath("$.amount").value(3000L))
                .andExpect(jsonPath("$.currency").value("MYR"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void senderBalanceIsDeductedAfterTransfer() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 4000L, "Payment", "idem-002")))
                .andExpect(status().isCreated());

        Wallet updatedSender = walletRepository.findByUserId(userA.getId()).orElseThrow();
        assertThat(updatedSender.getBalance()).isEqualTo(6000L);
    }

    @Test
    void recipientBalanceIsCreditedAfterTransfer() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 2500L, "Credit", "idem-003")))
                .andExpect(status().isCreated());

        Wallet updatedRecipient = walletRepository.findByUserId(userB.getId()).orElseThrow();
        assertThat(updatedRecipient.getBalance()).isEqualTo(2500L);
    }

    @Test
    void twoLedgerEntriesAreCreatedPerTransfer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 1000L, "Ledger check", "idem-004")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String reference = JsonPath.read(body, "$.transferReference");

        var transfer = transferRepository.findByReference(reference).orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transfer.getId());

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(LedgerEntry::getEntryType))
                .containsExactlyInAnyOrder("DEBIT", "CREDIT");
    }

    @Test
    void ledgerEntryAmountsEqual() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 750L, "Amounts equal", "idem-005")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String reference = JsonPath.read(body, "$.transferReference");

        var transfer = transferRepository.findByReference(reference).orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transfer.getId());

        assertThat(entries).hasSize(2);
        entries.forEach(e -> assertThat(e.getAmount()).isEqualTo(750L));
    }

    @Test
    void bothLedgerEntriesShareSameTransferId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 500L, "Same transfer ID", "idem-006")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String reference = JsonPath.read(body, "$.transferReference");

        var transfer = transferRepository.findByReference(reference).orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(transfer.getId());

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(LedgerEntry::getTransferId))
                .allMatch(id -> id.equals(transfer.getId()));
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 99999L, "Too much", "idem-007")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        String payload = """
                {
                  "recipientEmail": "%s",
                  "amount": 0,
                  "description": "Zero",
                  "idempotencyKey": "idem-008"
                }
                """.formatted(userB.getEmail());

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        String payload = """
                {
                  "recipientEmail": "%s",
                  "amount": -100,
                  "description": "Negative",
                  "idempotencyKey": "idem-neg"
                }
                """.formatted(userB.getEmail());

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selfTransferReturns400() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userA.getEmail(), 100L, "Self", "idem-009")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRecipientReturns404() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload("nobody@finwallet.test", 100L, "No recipient", "idem-010")))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveRecipientWalletReturns409() throws Exception {
        walletB.setStatus("SUSPENDED");
        walletRepository.saveAndFlush(walletB);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 100L, "Inactive", "idem-011")))
                .andExpect(status().isConflict());
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 100L, "No auth", "idem-012")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingRecipientEmailReturns400() throws Exception {
        String payload = """
                {
                  "amount": 100,
                  "idempotencyKey": "idem-013"
                }
                """;

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        String payload = """
                {
                  "recipientEmail": "%s",
                  "amount": 100
                }
                """.formatted(userB.getEmail());

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotentReplayReturnsSameTransferReference() throws Exception {
        String payload = transferPayload(userB.getEmail(), 1000L, "Replay", "idem-replay-1");

        MvcResult first = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String refFirst = JsonPath.read(first.getResponse().getContentAsString(), "$.transferReference");
        String refSecond = JsonPath.read(second.getResponse().getContentAsString(), "$.transferReference");
        assertThat(refFirst).isEqualTo(refSecond);

        // Balance should only have changed once
        Wallet updatedSender = walletRepository.findByUserId(userA.getId()).orElseThrow();
        assertThat(updatedSender.getBalance()).isEqualTo(9000L);
    }

    @Test
    void idempotencyKeyReuseWithDifferentDetailsReturns409() throws Exception {
        // First transfer with key
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 1000L, "First", "idem-conflict-1")))
                .andExpect(status().isCreated());

        // Same key but different amount → conflict
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 2000L, "Second", "idem-conflict-1")))
                .andExpect(status().isConflict());
    }

    @Test
    void balancesRollBackOnError() throws Exception {
        // Attempt transfer that exceeds balance — both balances must remain unchanged
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferPayload(userB.getEmail(), 999999L, "Rollback", "idem-rollback")))
                .andExpect(status().isUnprocessableEntity());

        Wallet sender = walletRepository.findByUserId(userA.getId()).orElseThrow();
        Wallet recipient = walletRepository.findByUserId(userB.getId()).orElseThrow();
        assertThat(sender.getBalance()).isEqualTo(10000L);
        assertThat(recipient.getBalance()).isEqualTo(0L);
    }
}
