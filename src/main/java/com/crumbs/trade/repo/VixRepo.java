package com.crumbs.trade.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Vix;



@Repository
public interface VixRepo extends JpaRepository<Vix, Long> {
	 @Modifying
     @Query("delete from Vix p" )
     void deleteAll();
	 
	 public Optional<Vix> findById(Long id);
	 
	 public List<Vix> findByName(String name);
	 
	 public List<Vix> findAllByOrderByIdAsc();   
	 
	 public List<Vix> findAllByOrderByIdDesc();
	 
	 public List<Vix> findAllByNameContainingOrderByIdDesc(String name);
	 
	 public Vix findByActiveAndName(String active,String name);
}