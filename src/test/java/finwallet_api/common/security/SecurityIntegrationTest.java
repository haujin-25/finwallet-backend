package finwallet_api.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User testUser;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        User user = new User();
        user.setEmail("secuser@finwallet.test");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setFullName("Security User");
        user.setStatus("ACTIVE");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        testUser = userRepository.saveAndFlush(user);
    }

    @Test
    void protectedEndpointRejectsMissingAuthorizationHeaderWith401() throws Exception {
        mockMvc.perform(get("/api/users/email/secuser@finwallet.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void protectedEndpointAllowsValidJwt() throws Exception {
        String token = jwtService.generateToken(testUser.getEmail(), testUser.getId());

        mockMvc.perform(get("/api/users/email/secuser@finwallet.test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("secuser@finwallet.test"))
                .andExpect(jsonPath("$.fullName").value("Security User"));
    }

    @Test
    void protectedEndpointRejectsExpiredJwtWith401() throws Exception {
        String expiredToken = jwtService.generateToken(testUser.getEmail(), testUser.getId(), -1000L);

        mockMvc.perform(get("/api/users/email/secuser@finwallet.test")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void protectedEndpointRejectsTamperedJwtWith401() throws Exception {
        String token = jwtService.generateToken(testUser.getEmail(), testUser.getId());
        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "badSig";

        mockMvc.perform(get("/api/users/email/secuser@finwallet.test")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void registrationEndpointRemainsPubliclyAccessible() throws Exception {
        String registrationPayload = """
                {
                  "email": "publicreg@finwallet.test",
                  "password": "SecurePassword123!",
                  "fullName": "Public User"
                }
                """;

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("publicreg@finwallet.test"));
    }
}
