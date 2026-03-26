package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.OIResult;

public interface OIResultRepo extends JpaRepository<OIResult, Long> {

    @Query("""
        SELECT o FROM OIResult o
        WHERE o.name = :name
        AND o.timestamp = (
            SELECT MAX(o2.timestamp)
            FROM OIResult o2
            WHERE o2.name = :name
        )
        ORDER BY o.strike ASC
    """)
    List<OIResult> findLatestByName(@Param("name") String name);
    
    List<OIResult> findByNameAndTimestampBetweenOrderByTimestampAsc(
            String name,
            LocalDateTime start,
            LocalDateTime end
    );
    @Query("""
    	    SELECT o FROM OIResult o
    	    WHERE o.name = :name
    	    AND o.timestamp = :timestamp
    	    ORDER BY o.strike ASC
    	""")
    	List<OIResult> findByNameAndTimestamp(
    	        String name,
    	        LocalDateTime timestamp
    	);
    
}