package com.crumbs.trade.repo;

import java.util.List;



import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Indexes;

import jakarta.transaction.Transactional;



@Repository
public interface IndexesRepo extends JpaRepository<Indexes, Long> {

	Indexes findByNameAndSymbol(String name, String symbol);

	@Modifying
	@Query("delete from Indexes I")
	void deleteAll();

	@Modifying
	@Transactional
	@Query("UPDATE Indexes u SET u.volume = :volume")
	void updateVolume(@Param("volume") String volume);

	List<Indexes> findByNameIn(List<String> names);

	List<Indexes> findByExchangeInAndVolume(List<String> exchange, String volume);

	List<Indexes> findByExchangeIn(List<String> exchange);

	@Query(value = """
		    SELECT * FROM (
		        SELECT *, 
		               ROW_NUMBER() OVER (PARTITION BY NAME ORDER BY 
		                   CASE WHEN EXCHANGE = 'NSE' THEN 1
		                        WHEN EXCHANGE = 'BSE' THEN 2
		                        ELSE 3
		                   END
		               ) AS rn
		        FROM Indexes
		        WHERE NAME NOT REGEXP '.*[0-9].*'
		          AND EXCHANGE IN (:exchange)
		    ) ranked
		    WHERE rn = 1
		    """, nativeQuery = true)
	List<Indexes> findAllStocks(@Param("exchange") List<String> exchange);
	
	Indexes findBySymbol(String symbol);
}
