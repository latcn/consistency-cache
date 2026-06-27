package io.github.latcn.cache.example.controller;

import cn.hutool.core.date.DateUtil;
import io.github.latcn.cache.example.dto.TestDTO;
import io.github.latcn.cache.spring.annotation.HccCacheEvict;
import io.github.latcn.cache.spring.annotation.HccCacheable;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/hcc")
public class TestController {

	@GetMapping("/{itemId}")
	@HccCacheable(key = "#itemId", expireTime = 100, fallbackExecActual = true)
	public TestDTO getItem(@PathVariable Long itemId) {
		TestDTO testDTO = new TestDTO();
		testDTO.setId(itemId);
		testDTO.setContent(UUID.randomUUID().toString());
		testDTO.setDesc(DateUtil.format(new Date(), "YYYY-MM-DD HH:MM:ss"));
		return testDTO;
	}

	@DeleteMapping("/del/{itemId}")
	@HccCacheEvict(key = "#itemId", transactionEnabled = true, fallbackExecActual = true)
	public boolean delItem(@PathVariable Long itemId) {
		return true;
	}

	public static void main(String[] args) {
		log.info(DateUtil.format(new Date(), "YYYY-MM-DD HH:MM:ss"));
	}

}