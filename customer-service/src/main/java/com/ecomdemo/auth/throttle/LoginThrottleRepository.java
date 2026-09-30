package com.ecomdemo.auth.throttle;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoginThrottleRepository extends JpaRepository<LoginThrottle, String> {

    /**
     * The row, locked until the transaction ends: two failed logins arriving together must BOTH be
     * counted, and a read-modify-write without the lock would let one overwrite the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LoginThrottle t where t.key = :key")
    Optional<LoginThrottle> findForUpdate(@Param("key") String key);
}
