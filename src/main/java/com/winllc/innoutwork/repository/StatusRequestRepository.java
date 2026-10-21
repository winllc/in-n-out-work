package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.model.StatusRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StatusRequestRepository extends JpaRepository<StatusRequest, Long> {

    /** The request, locked until the transaction ends, so two approvers' decisions are taken one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StatusRequest r where r.id = :id")
    Optional<StatusRequest> findByIdForUpdate(@Param("id") Long id);

    /** A person's requests, newest first; lower(dn), for the index. */
    @Query("select r from StatusRequest r where lower(r.requesterDn) = lower(:dn) order by r.createdAt desc")
    List<StatusRequest> findByRequester(@Param("dn") String dn);

    /** A person's requests in a state that cover any day from {@code from} to {@code to}. */
    @Query("select r from StatusRequest r where lower(r.requesterDn) = lower(:dn) and r.state = :state"
            + " and r.fromDate <= :to and r.toDate >= :from")
    List<StatusRequest> findByRequesterAndStateOverlapping(@Param("dn") String dn, @Param("state") RequestState state,
                                                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Every request in a state, oldest first: an administrator's queue. */
    List<StatusRequest> findByStateOrderByCreatedAtAsc(RequestState state);

    /** Requests in a state from a set of people, oldest first; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select r from StatusRequest r where r.state = :state and lower(r.requesterDn) in :dns"
            + " order by r.createdAt asc")
    List<StatusRequest> findByStateAndLowercaseRequesterIn(@Param("state") RequestState state,
                                                           @Param("dns") Collection<String> dns);

    // --- retention and erasure ------------------------------------------------------------------------------

    /** Ids of requests whose last day is before {@code cutoff}, a batch at a time. */
    @Query("select r.id from StatusRequest r where r.toDate < :cutoff order by r.id")
    List<Long> findIdsEndingBefore(@Param("cutoff") LocalDate cutoff, org.springframework.data.domain.Pageable page);

    @Modifying
    @Query("delete from StatusRequest r where r.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("delete from StatusRequest r where lower(r.requesterDn) = lower(:dn)")
    int deleteByRequester(@Param("dn") String dn);

    /** Requests in {@code states} covering any day from {@code from} to {@code to}, a page at a time. */
    @Query("select r from StatusRequest r where r.state in :states and r.fromDate <= :to and r.toDate >= :from")
    org.springframework.data.domain.Page<StatusRequest> findPageOverlapping(
            @Param("states") java.util.Collection<RequestState> states,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            org.springframework.data.domain.Pageable pageable);

    /** {@link #findPageOverlapping} for the people given, by lower-cased DN. */
    @Query("select r from StatusRequest r where lower(r.requesterDn) in :dns and r.state in :states"
            + " and r.fromDate <= :to and r.toDate >= :from")
    org.springframework.data.domain.Page<StatusRequest> findPageByLowercaseRequesterInOverlapping(
            @Param("dns") java.util.Collection<String> dns,
            @Param("states") java.util.Collection<RequestState> states,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            org.springframework.data.domain.Pageable pageable);
}
