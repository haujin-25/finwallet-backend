package finwallet_api.transfer.repository;

import finwallet_api.transfer.entity.Transfer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByReference(String reference);

    Optional<Transfer> findBySenderWalletIdAndIdempotencyKey(Long senderWalletId, String idempotencyKey);
}
