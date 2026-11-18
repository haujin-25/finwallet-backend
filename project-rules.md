# FinWallet Project Context

## Project Overview
Digital wallet and payment processing platform.
Built as a fintech backend engineering portfolio project.

## Current Technology
- Java 21
- Spring Boot 4.x
- Maven
- PostgreSQL 15
- Spring Data JPA
- Hibernate
- Flyway

## Package Structure

src/main/java/finwallet_api/

- auth/
  - controller/
  - service/
  - dto/
- user/
  - controller/
  - service/
  - repository/
  - entity/
  - dto/
- wallet/
  - controller/
  - service/
  - repository/
  - entity/
  - dto/
- common/
  - exception/
  - security/
  - config/

## Database

Database: finwallet
Database user: finwallet_user

Schema management:
- Flyway manages database migrations.
- Hibernate uses ddl-auto=validate.
- PostgreSQL is the source of truth.

## Current Implementation

### Completed
- Spring Boot project initialization.
- PostgreSQL connection.
- Flyway integration.
- Initial users table migration.
- User entity.
- User repository.
- User service.
- User controller.
- UserResponse DTO.
- Global exception handling.
- User registration and password hashing (BCrypt).
- User login and JWT authentication.
- Wallets table migration (V2).
- Wallet entity and repository.
- Wallet service.
- Automatic wallet creation upon registration with transactional rollback.
- Wallet balance retrieval for authenticated user (`GET /api/wallets/me`).
- UserPrincipal representation in Spring Security context.

### Current Features
- User registration (`POST /api/users/register`).
- Automatic creation of default MYR wallet (0 sen balance, ACTIVE) during registration.
- User login (`POST /api/auth/login`).
- Stateless JWT authentication with Bearer token validation and expiration.
- Protected endpoint authorization.
- Retrieve user by email (`GET /api/users/email/{email}`, authenticated).
- Retrieve wallet for authenticated user (`GET /api/wallets/me`, authenticated).
- Return UserResponse and WalletResponse instead of exposing JPA entities.
- Generic 401 on authentication failures, 403 on suspended/closed accounts.

### Not Yet Implemented
- Transfers.
- Double-entry ledger.
- Idempotency.

## Important Decisions

- Use integer minor units for monetary values.
- Never use float or double for money.
- Never expose JPA entities directly through APIs.
- Use Flyway for schema changes.
- Use constructor injection.
- Start with a modular monolith.
- Do not introduce microservices prematurely.
- Never trust client-supplied user IDs; identify users strictly from the cryptographically verified JWT/SecurityContext.
- Use UserPrincipal (id, email) in Spring Security context for direct ID access without extra queries.