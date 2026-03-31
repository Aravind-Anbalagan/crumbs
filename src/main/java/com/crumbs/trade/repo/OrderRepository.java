package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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
}
