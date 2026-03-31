package com.consist.cache.example.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class TestDTO implements Serializable {

    private Long id;

    private String content;

    private String desc;

}
