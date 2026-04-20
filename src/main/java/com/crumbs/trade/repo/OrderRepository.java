package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.Orders;

public interface OrderRepository extends JpaRepository<Orders, Long> {
	Orders findByNameAndActive(String name, int active);

	@Modifying
	@Query("delete from Orders o")
	void deleteAll();
	
	Optional<Orders> findTopByNameAndActiveOrderByIdDesc(String name, int active);

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
}
