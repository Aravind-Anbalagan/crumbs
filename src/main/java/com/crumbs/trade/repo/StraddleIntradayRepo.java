package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.StraddleIntraday;
import org.springframework.data.domain.Pageable;
@Repository
public interface StraddleIntradayRepo extends JpaRepository<StraddleIntraday, Long>  {

	@Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.expiry = :expiry AND s.strike = :strike ORDER BY s.timestamp ASC")
	List<StraddleIntraday> getByStrike(String name, String expiry, BigDecimal strike);

	@Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.expiry = :expiry ORDER BY s.timestamp ASC")
	List<StraddleIntraday> getSpotHistory(String name, String expiry);

	@Query("""
		    SELECT s.name, s.expiry, s.strike
		    FROM StraddleIntraday s
		    ORDER BY s.name, s.expiry, s.strike
		""")
	List<Object[]> fetchNameExpiryStrikeRaw();

	@Query("""
		    select s.name, s.expiry, s.strike
		    from StraddleIntraday s
		    where s.name = :name
		    group by s.name, s.expiry, s.strike
		    order by s.expiry, s.strike
		""")
		List<Object[]> fetchNameExpiryStrikeRaw(@Param("name") String name);
	    
	@Modifying
	@Query("delete from StraddleIntraday o")
	void deleteAll();
	
	 // Convenience wrapper (if you prefer direct call)
    default StraddleIntraday findLatest(String name) {
        return findFirstByNameOrderByTimestampDesc(name)
                .orElse(null);
    }
	// =====================================================
    // 1️⃣ Latest row (intraday)
    // =====================================================
    Optional<StraddleIntraday>
        findFirstByNameOrderByTimestampDesc(String name);

    // =====================================================
    // 2️⃣ Full day data (EOD audit) – EXPLICIT QUERY
    // =====================================================
    @Query("""
        SELECT s
        FROM StraddleIntraday s
        WHERE s.name = :name
          AND s.timestamp BETWEEN :start AND :end
        ORDER BY s.timestamp ASC
    """)
    List<StraddleIntraday> findByNameAndTimestampBetweenOrderByTimestamp(
            @Param("name") String name,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    // =====================================================
    // 3️⃣ Convenience wrapper (use this everywhere)
    // =====================================================
    default List<StraddleIntraday> findByNameAndTradeDateOrderByTimestamp(
            String name,
            LocalDate tradeDate) {

        LocalDateTime start = tradeDate.atStartOfDay();
        LocalDateTime end   = tradeDate.atTime(23, 59, 59);

        return findByNameAndTimestampBetweenOrderByTimestamp(
                name, start, end);
    }
    
    @Query("""
    	    SELECT s
    	    FROM StraddleIntraday s
    	    WHERE s.name = :name
    	      AND s.timestamp = (
    	          SELECT MAX(x.timestamp)
    	          FROM StraddleIntraday x
    	          WHERE x.name = :name
    	      )
    	""")
    	List<StraddleIntraday> findLatestSnapshot(@Param("name") String name);
    
    @Query("""
            select s
            from StraddleIntraday s
            where s.name = :name
              and s.timestamp = (
                  select max(x.timestamp)
                  from StraddleIntraday x
                  where x.name = :name
              )
            order by s.strike
        """)
        List<StraddleIntraday> findLatestByName(
                @Param("name") String name
        );
    
    @Query("""
    	    SELECT s
    	    FROM StraddleIntraday s
    	    WHERE s.name = :name
    	      AND s.strike = :strike
    	    ORDER BY s.timestamp DESC
    	""")
    	List<StraddleIntraday> findLastTwo(
    	        @Param("name") String name,
    	        @Param("strike") BigDecimal strike,
    	        Pageable pageable
    	);
 // Add these two methods to your existing StraddleIntradayRepo interface:

    /**
     * Get all records since a specific advice time
     * Used for trailing stop calculations and historical analysis
     */
    @Query("""
        SELECT s 
        FROM StraddleIntraday s 
        WHERE s.name = :name 
          AND s.timestamp >= :sinceTime 
        ORDER BY s.timestamp ASC
    """)
    List<StraddleIntraday> findSinceAdviceTime(
        @Param("name") String name,
        @Param("sinceTime") LocalDateTime sinceTime
    );
    
    /**
     * Get records from last N minutes (convenience wrapper)
     * Used for volume confirmation in pressure stability checks
     */
    default List<StraddleIntraday> findLastNMinutes(String name, int minutes) {
        LocalDateTime sinceTime = LocalDateTime.now()
            .minusMinutes(minutes);
        return findSinceAdviceTime(name, sinceTime);
    }
    
    /**
     * Find all strikes for a specific name and timestamp (used by pre-market analysis)
     */
    @Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.timestamp = :timestamp")
    List<StraddleIntraday> findByNameAndTimestamp(
        @Param("name") String name,
        @Param("timestamp") LocalDateTime timestamp
    );

	StraddleIntraday findTopByNameAndExpiryAndStrikeOrderByTimestampDesc(String name, String expiry, BigDecimal strike);

	List<StraddleIntraday> findByNameAndExpiryAndStrikeOrderByTimestampDesc(String name, String expiry,
			BigDecimal strike);


	// Base rows per strike (first 5 mins)
	List<StraddleIntraday> findTop5ByNameAndStrikeOrderByTimestampAsc(
	    String name, BigDecimal strike);
	
	@Query("SELECT DISTINCT s.strike FROM StraddleIntraday s WHERE s.name = :name")
	List<BigDecimal> findDistinctStrikes(String name);
	
	// 🔹 latest record
    StraddleIntraday findTopByNameOrderByTimestampDesc(String name);

    // 🔹 base rows (first 5)
    List<StraddleIntraday>
    findTop5ByNameAndStrikeAndTimestampBetweenOrderByTimestampAsc(
            String name,
            BigDecimal strike,
            LocalDateTime start,
            LocalDateTime end
    );

    // 🔹 last rows (latest 3)
    List<StraddleIntraday>
    findTop3ByNameAndStrikeAndTimestampBetweenOrderByTimestampDesc(
            String name,
            BigDecimal strike,
            LocalDateTime start,
            LocalDateTime end
    );
}
