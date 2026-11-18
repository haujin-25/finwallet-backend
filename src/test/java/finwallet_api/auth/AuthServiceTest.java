package finwallet_api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finwallet_api.auth.dto.LoginRequest;
import finwallet_api.auth.dto.LoginResponse;
import finwallet_api.auth.service.AuthService;
import finwallet_api.common.exception.AccountClosedException;
import finwallet_api.common.exception.AccountSuspendedException;
import finwallet_api.common.exception.InvalidCredentialsException;
import finwallet_api.common.security.JwtService;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void loginSuccessfulForActiveUser() {
        User user = new User();
        user.setId(10L);
        user.setEmail("user@finwallet.test");
        user.setPasswordHash("$2a$10$hashedPassword");
        user.setStatus("ACTIVE");

        when(userRepository.findByEmail("user@finwallet.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$10$hashedPassword")).thenReturn(true);
        when(jwtService.generateToken("user@finwallet.test", 10L)).thenReturn("mocked.jwt.token");
        when(jwtService.getExpirationSeconds()).thenReturn(900L);

        LoginRequest request = new LoginRequest("user@finwallet.test", "Password123!");
        LoginResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mocked.jwt.token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(900L, response.expiresIn());
        verify(jwtService).generateToken("user@finwallet.test", 10L);
    }

    @Test
    void loginFailsWhenUserNotFound() {
        when(userRepository.findByEmail("missing@finwallet.test")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("missing@finwallet.test", "Password123!");

        InvalidCredentialsException ex = assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(request)
        );

        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    void loginFailsWhenPasswordIncorrect() {
        User user = new User();
        user.setEmail("user@finwallet.test");
        user.setPasswordHash("$2a$10$hashedPassword");
        user.setStatus("ACTIVE");

        when(userRepository.findByEmail("user@finwallet.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword!", "$2a$10$hashedPassword")).thenReturn(false);

        LoginRequest request = new LoginRequest("user@finwallet.test", "WrongPassword!");

        InvalidCredentialsException ex = assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(request)
        );

        assertEquals("Invalid email or password", ex.getMessage());
    }

    @Test
    void loginFailsWhenAccountIsSuspended() {
        User user = new User();
        user.setEmail("suspended@finwallet.test");
        user.setPasswordHash("$2a$10$hashedPassword");
        user.setStatus("SUSPENDED");

        when(userRepository.findByEmail("suspended@finwallet.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$10$hashedPassword")).thenReturn(true);

        LoginRequest request = new LoginRequest("suspended@finwallet.test", "Password123!");

        AccountSuspendedException ex = assertThrows(
                AccountSuspendedException.class,
                () -> authService.login(request)
        );

        assertEquals("Account is suspended", ex.getMessage());
    }

    @Test
    void loginFailsWhenAccountIsClosed() {
        User user = new User();
        user.setEmail("closed@finwallet.test");
        user.setPasswordHash("$2a$10$hashedPassword");
        user.setStatus("CLOSED");

        when(userRepository.findByEmail("closed@finwallet.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$10$hashedPassword")).thenReturn(true);

        LoginRequest request = new LoginRequest("closed@finwallet.test", "Password123!");

        AccountClosedException ex = assertThrows(
                AccountClosedException.class,
                () -> authService.login(request)
        );

        assertEquals("Account is closed", ex.getMessage());
    }
}
