package finwallet_api.user;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
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
class UserRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersUserAndStoresPasswordHash() throws Exception {
        String password = "SecurePassword123!";

        MvcResult result = mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "John@finwallet.test",
                                  "password": "%s",
                                  "fullName": " John Doe "
                                }
                                """.formatted(password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("john@finwallet.test"))
                .andExpect(jsonPath("$.fullName").value("John Doe"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        User user = userRepository.findByEmail("john@finwallet.test").orElseThrow();

        assertFalse(body.contains(password));
        assertFalse(body.contains(user.getPasswordHash()));
        assertNotEquals(password, user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2a$"));
        assertTrue(passwordEncoder.matches(password, user.getPasswordHash()));
        assertEquals("ACTIVE", user.getStatus());
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "SecurePassword123!",
                                  "fullName": "John Doe"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("email")));

        assertTrue(userRepository.findByEmail("not-an-email").isEmpty());
    }

    @Test
    void rejectsMissingFullName() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "noname@finwallet.test",
                                  "password": "SecurePassword123!",
                                  "fullName": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("fullName")));

        assertTrue(userRepository.findByEmail("noname@finwallet.test").isEmpty());
    }

    @Test
    void rejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "short@finwallet.test",
                                  "password": "short",
                                  "fullName": "John Doe"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("password")));

        assertTrue(userRepository.findByEmail("short@finwallet.test").isEmpty());
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String firstRequest = """
                {
                  "email": "ada@finwallet.test",
                  "password": "SecurePassword123!",
                  "fullName": "Ada Lovelace"
                }
                """;
        String secondRequest = """
                {
                  "email": "ADA@finwallet.test",
                  "password": "AnotherPassword123!",
                  "fullName": "Ada Lovelace"
                }
                """;

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRequest))
                .andExpect(status().isConflict())
                .andExpect(content().string("Email is already registered"));

        assertEquals(1, userRepository.findAll().stream()
                .filter(user -> user.getEmail().equals("ada@finwallet.test"))
                .count());
    }
}
