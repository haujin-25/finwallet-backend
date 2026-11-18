package finwallet_api.auth;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import finwallet_api.common.security.JwtService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private static final String PASSWORD = "CorrectPassword123!";

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        User activeUser = new User();
        activeUser.setEmail("active@finwallet.test");
        activeUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        activeUser.setFullName("Active User");
        activeUser.setStatus("ACTIVE");
        activeUser.setCreatedAt(now);
        activeUser.setUpdatedAt(now);
        userRepository.saveAndFlush(activeUser);

        User suspendedUser = new User();
        suspendedUser.setEmail("suspended@finwallet.test");
        suspendedUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        suspendedUser.setFullName("Suspended User");
        suspendedUser.setStatus("SUSPENDED");
        suspendedUser.setCreatedAt(now);
        suspendedUser.setUpdatedAt(now);
        userRepository.saveAndFlush(suspendedUser);

        User closedUser = new User();
        closedUser.setEmail("closed@finwallet.test");
        closedUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        closedUser.setFullName("Closed User");
        closedUser.setStatus("CLOSED");
        closedUser.setCreatedAt(now);
        closedUser.setUpdatedAt(now);
        userRepository.saveAndFlush(closedUser);
    }

    @Test
    void successfulLoginReturnsAccessTokenAndTokenType() throws Exception {
        String payload = """
                {
                  "email": "active@finwallet.test",
                  "password": "%s"
                }
                """.formatted(PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(jwtService.getExpirationSeconds()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");

        assertNotNull(token);
        assertFalse(body.contains(PASSWORD));
        assertTrueValidToken(token, "active@finwallet.test");
    }

    @Test
    void rejectsIncorrectPasswordWith401() throws Exception {
        String payload = """
                {
                  "email": "active@finwallet.test",
                  "password": "WrongPassword123!"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid email or password"));
    }

    @Test
    void rejectsNonExistentUserWith401() throws Exception {
        String payload = """
                {
                  "email": "unknown@finwallet.test",
                  "password": "AnyPassword123!"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid email or password"));
    }

    @Test
    void rejectsSuspendedAccountWith403() throws Exception {
        String payload = """
                {
                  "email": "suspended@finwallet.test",
                  "password": "%s"
                }
                """.formatted(PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Account is suspended"));
    }

    @Test
    void rejectsClosedAccountWith403() throws Exception {
        String payload = """
                {
                  "email": "closed@finwallet.test",
                  "password": "%s"
                }
                """.formatted(PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Account is closed"));
    }

    @Test
    void rejectsMissingEmail() throws Exception {
        String payload = """
                {
                  "email": " ",
                  "password": "%s"
                }
                """.formatted(PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("email")));
    }

    @Test
    void rejectsMissingPassword() throws Exception {
        String payload = """
                {
                  "email": "active@finwallet.test",
                  "password": ""
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("password")));
    }

    private void assertTrueValidToken(String token, String expectedEmail) {
        assertEquals(expectedEmail, jwtService.extractEmail(token));
        org.junit.jupiter.api.Assertions.assertTrue(jwtService.validateToken(token));
    }
}
