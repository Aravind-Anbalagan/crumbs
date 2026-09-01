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

    /**
     * 1. Used by getEnrichedLivePositions() to map broker trades to database tokens.
     */
    @Query("SELECT i FROM Indexes i WHERE i.name = :underlying " +
            "AND i.symbol LIKE %:strikeSuffix " +
            "AND (i.expiry = :expiryShort OR i.expiry = :expiryLong)")
    Optional<Indexes> findOptionToken(@Param("underlying") String underlying,
                                      @Param("strikeSuffix") String strikeSuffix,
                                      @Param("expiryShort") String expiryShort,
                                      @Param("expiryLong") String expiryLong);

    /**
     * 2. Used by getOptionChainForSimulation() to fetch all available CE/PE strikes
     * for a given index and expiry date, ordered cleanly by strike price.
     */
    @Query("SELECT i FROM Indexes i WHERE i.name = :underlying " +
            "AND (i.expiry = :expiryShort OR i.expiry = :expiryLong) " +
            "ORDER BY i.strike ASC")
    List<Indexes> findActiveContractsByExpiry(@Param("underlying") String underlying,
                                              @Param("expiryShort") String expiryShort,
                                              @Param("expiryLong") String expiryLong);

    // Add this to IndexesRepo.java if it isn't there already:
    @Query("SELECT i.token FROM Indexes i WHERE i.name = :name AND i.expiry = :expiry AND i.symbol LIKE :suffix")
    String findTokenByNameAndExpiryAndSymbolLike(
            @Param("name") String name,
            @Param("expiry") String expiry,
            @Param("suffix") String suffix
    );

    // ──────────────────────────────────────────────────────────
    //  🆕 NEW: Option-strategy support (straddle/strangle lookup)
    // ──────────────────────────────────────────────────────────

    /**
     * 🆕 Returns every distinct expiry string stored for a given underlying,
     * across the given exchange list (currently just NFO — kept as a list
     * parameter so scope can be widened later without another repo change).
     */
    @Query("SELECT DISTINCT i.expiry FROM Indexes i WHERE i.name = :name AND i.exchange IN :exchanges AND i.expiry IS NOT NULL")
    List<String> findDistinctExpiriesByNameAndExchangeIn(@Param("name") String name, @Param("exchanges") List<String> exchanges);

    /**
     * 🆕 Returns every CE/PE contract for a given underlying + exact expiry
     * string, across the given exchange list (currently just NFO), ordered
     * by strike.
     */
    List<Indexes> findByNameAndExchangeInAndExpiryOrderByStrikeAsc(String name, List<String> exchanges, String expiry);
    // ADD THIS NEW METHOD:
    @Query("SELECT i FROM Indexes i WHERE i.name IN :names AND i.exchange = :exchange")
    List<Indexes> findByNamesAndExchange(@Param("names") List<String> names,
                                         @Param("exchange") String exchange);

    /**
     * Fetches all active F&O contracts for a given symbol (Index or Stock).
     */
    @Query("SELECT i FROM Indexes i WHERE UPPER(TRIM(i.name)) = UPPER(TRIM(:name)) " +
            "AND UPPER(TRIM(i.exchange)) = 'NFO' " +
            "AND i.expiry IS NOT NULL " +
            "AND i.strike IS NOT NULL")
    List<Indexes> findNfoContractsByName(@Param("name") String name);


}