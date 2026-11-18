package finwallet_api.transfer.repository;

import finwallet_api.transfer.entity.LedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransferId(Long transferId);
}
