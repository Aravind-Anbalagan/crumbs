package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.entity.Result;


@Repository
public interface ResultRepo  extends JpaRepository<Result, Long> {

	Result findByName(String name);
	
	public List<Result> findAllByOrderByIdAsc();

	public List<Result> findAllByOrderByIdDesc();
	
	Result findByActiveAndName(String active,String name);
	
	@Modifying
	@Query("delete from Result r")
	void deleteAll();
	
	@Modifying
	@Transactional
	@Query("UPDATE Result r SET r.active = :active WHERE r.id = :id")
	int updateActiveById(Long id, String active);
	
	@Query("SELECT r FROM Result r WHERE r.entryTime BETWEEN :start AND :end ORDER BY FUNCTION('PARSEDATETIME', r.entryTime, 'yyyy-MM-dd HH:mm:ss') DESC")
	List<Result> findByEntryTimeBetweenOrderByEntryTimeDesc(@Param("start") String start, @Param("end") String end);
	
	@Query("SELECT r FROM Result r " + "WHERE r.name = :name " + "AND SUBSTRING(r.entryTime, 1, 7) = :month "
			+ "ORDER BY r.entryTime DESC")
	Optional<Result> findTopByNameAndMonth(@Param("name") String name, @Param("month") String month);
}
