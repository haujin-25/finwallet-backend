package finwallet_api.user.service;

import finwallet_api.common.exception.EmailAlreadyRegisteredException;
import finwallet_api.common.exception.ResourceNotFoundException;
import finwallet_api.user.dto.UserRegistrationRequest;
import finwallet_api.user.dto.UserResponse;
import finwallet_api.user.entity.User;
import finwallet_api.user.repository.UserRepository;
import finwallet_api.wallet.service.WalletService;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final String ACTIVE = "ACTIVE";
    private static final String DUPLICATE_EMAIL_MESSAGE = "Email is already registered";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            WalletService walletService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
    }

    public UserResponse findUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return toResponse(user);
    }

    @Transactional
    public UserResponse register(UserRegistrationRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String fullName = request.fullName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(DUPLICATE_EMAIL_MESSAGE);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(fullName);
        user.setStatus(ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmail(exception)) {
                throw new EmailAlreadyRegisteredException(DUPLICATE_EMAIL_MESSAGE);
            }
            throw exception;
        }

        walletService.createWalletForUser(user.getId());

        return toResponse(user);
    }

    private boolean isDuplicateEmail(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (!(cause instanceof SQLException sqlException) || !"23505".equals(sqlException.getSQLState())) {
            return false;
        }

        String message = sqlException.getMessage();
        return message != null && message.contains("users_email_key");
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus()
        );
    }
}
