package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.crumbs.trade.entity.Orders;

public interface OrderRepository extends JpaRepository<Orders, Long> {
	List<Orders> findByNameAndActive(String name, int active);

	@Modifying
	@Query("delete from Orders o")
	void deleteAll();
}
