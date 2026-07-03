package com.crumbs.trade.repo;
import java.util.List;
import java.util.Optional;

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
    Indexes findByNameAndSymbolAndExchange(String name, String symbol,String exchange);

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
                       ROW_NUMBER() OVER (
                           PARTITION BY NAME 
                           ORDER BY CASE 
                                        WHEN EXCHANGE = 'NSE' THEN 1
                                        WHEN EXCHANGE = 'BSE' THEN 2
                                        ELSE 3
                                    END
                       ) AS rn
                FROM Indexes
                WHERE NAME NOT REGEXP '.*[0-9].*'
                  AND SYMBOL LIKE '%-EQ'
                  AND EXCHANGE IN (:exchange)
            ) ranked
            WHERE rn = 1
            """, nativeQuery = true)
    List<Indexes> findAllStocks(@Param("exchange") List<String> exchange);

    Indexes findBySymbol(String symbol);

    List<Indexes> findBySymbolIn(List<String> names);

    List<Indexes> findByNameAndExpiry(String name, String expiry);

    /**
     * ✅ Find equity stock (symbol ending with -EQ)
     * This ensures we get cash market prices, not derivative prices
     */
    @Query("SELECT i FROM Indexes i WHERE i.name = :name AND i.exchange = :exchange AND i.symbol LIKE '%-EQ' ORDER BY i.id ASC")
    Indexes findByNameAndExchange(@Param("name") String name, @Param("exchange") String exchange);

    List<Indexes> findByNameInAndExchange(List<String> names,String exchange);

    Indexes findByToken(String token);
    
 // Finds records by Name (e.g. "NIFTY") where Symbol contains a specific word (e.g. "FUT")
    List<Indexes> findByNameAndSymbolContaining(String name, String symbolPart);
    
    List<Indexes> findByName(String name);
    
    @Query("SELECT i FROM Indexes i WHERE UPPER(i.name) = UPPER(:name) " +
            "AND UPPER(i.symbol) LIKE CONCAT('%', UPPER(:strikeSuffix)) " +
            "AND (UPPER(i.expiry) = :expiryShort OR UPPER(i.expiry) = :expiryLong)")
     Optional<Indexes> findOptionToken(
         @Param("name") String name,
         @Param("strikeSuffix") String strikeSuffix,
         @Param("expiryShort") String expiryShort,
         @Param("expiryLong") String expiryLong
     );
}