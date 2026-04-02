package com.consist.cache.example.controller;


import cn.hutool.core.date.DateUtil;
import com.consist.cache.example.dto.TestDTO;
import com.consist.cache.spring.annotation.HccCacheEvict;
import com.consist.cache.spring.annotation.HccCacheable;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.UUID;

@RestController
@RequestMapping("/api/hcc")
public class TestController {

    @GetMapping("/{itemId}")
    @HccCacheable(key="#itemId", expireTime = 100)
    public TestDTO getItem(@PathVariable Long itemId) {
        TestDTO testDTO = new TestDTO();
        testDTO.setId(itemId);
        testDTO.setContent(UUID.randomUUID().toString());
        testDTO.setDesc(DateUtil.format(new Date(), "YYYY-MM-DD HH:MM:ss"));
        return testDTO;
    }

    @DeleteMapping("/del/{itemId}")
    @HccCacheEvict(key="#itemId")
    public boolean delItem(@PathVariable Long itemId) {
        return true;
    }

    public static void main(String[] args) {
        System.out.println(DateUtil.format(new Date(), "YYYY-MM-DD HH:MM:ss"));
    }

}