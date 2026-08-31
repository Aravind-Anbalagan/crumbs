package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.Orders;

public interface OrderRepository extends JpaRepository<Orders, Long> {
	Orders findByNameAndActive(String name, int active);
	Optional<Orders> findByNameAndActive(String name, Integer active);
	@Modifying
	@Query("delete from Orders o")
	void deleteAll();
	
	Optional<Orders> findTopByNameAndActiveOrderByIdDesc(String name, int active);
	Optional<Orders> findTopByNameOrderByClosedOnDesc(String name);
	List<Orders> findByName(String name);
	
	Orders findByTokenAndActive(String token, int active);
	
	// Open position check — cycle-free, just latest open for this symbol
	Optional<Orders> findTopByNameAndSymbolAndStatusOrderByCreatedOnDesc(
	        String name, String symbol, String status);

	// Daily cap
	long countByNameAndSymbolAndCreatedOnAfter(
	        String name, String symbol, LocalDateTime since);
	
	@Query("""
		    SELECT o FROM Orders o
		    WHERE o.symbol = :symbol
		    AND o.status = 'OPEN'
		    AND o.name = :strategy
		""")
		List<Orders> findOpenOrders(@Param("symbol") String symbol,
		                            @Param("strategy") String strategy);
	@Query("""
		    SELECT COUNT(o) > 0 FROM Orders o
		    WHERE o.symbol = :symbol
		    AND o.strike = :strike
		    AND o.status = 'OPEN'
		    AND o.name = :strategy
		""")
		boolean existsOpenBySymbolAndStrike(@Param("symbol") String symbol,
		                                    @Param("strike") BigDecimal strike,
		                                    @Param("strategy") String strategy);
	
	boolean existsBySymbolAndNameAndStatus(String symbol, String name, String status);
	// Finds active orders for this specific strategy and symbol
    List<Orders> findByNameAndSignalAndActive(String name, String signal, int active);

    List<Orders> findAllByName(String name);
    
 // Partial match (Contains) - This is what you need for "CPR"
    List<Orders> findByNameContainingIgnoreCase(String namePart);
    
 // 🔥 ADD THIS METHOD TO FIX THE ERROR
    @Query("SELECT COUNT(o) FROM Orders o WHERE o.name = :name " +
    	       "AND o.signal = :signal AND o.createdOn >= :startOfDay")
    	long countLegsToday(@Param("name") String name, 
    	                    @Param("signal") String signal, 
    	                    @Param("startOfDay") LocalDateTime startOfDay);
    Optional<Orders> findByNameAndTokenAndActive(String name, String token, Integer active);
    List<Orders> findAllByNameAndActive(String name, int active);
    
 // Add this line to fix the error:
    List<Orders> findByActive(int active);
    List<Orders> findByStatus(String status);

 // Add this method:
    List<Orders> findByStatusAndActive(String status, int active);
    
    // Also, if you need the one for specific names, add this too:
    Orders findByNameAndStatusAndActive(String name, String status, int active);
    
 // 🔥 TRUE DATABASE LOCK: Forces other threads to wait!
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Orders o WHERE o.name = :name AND o.active = 1 AND o.tradePhase != 'EXIT_IN_PROGRESS'")
    List<Orders> lockAndFetchActiveTrades(@Param("name") String name);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Orders o WHERE o.name = :name AND o.status = 'OPEN'")
    List<Orders> findByNameAndStatusForUpdate(@Param("name") String name);

    @Query("SELECT o FROM Orders o WHERE o.signal = :signal AND o.status = :status AND o.active = :active")
    List<Orders> findBySignalAndStatusAndActive(
            @Param("signal") String signal,
            @Param("status") String status,
            @Param("active") Integer active
    );

    @Query("SELECT COUNT(o) FROM Orders o WHERE o.name = :stockName AND o.active = 1")
    long countActiveTradesToday(@Param("stockName") String stockName);

    @Query("SELECT o FROM Orders o WHERE o.name = :stockName AND o.active = 1")
    List<Orders> findActiveTradesToday(@Param("stockName") String stockName);

}
