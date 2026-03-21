package com.crumbs.trade.repo;

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
}
