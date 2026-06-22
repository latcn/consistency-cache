package io.github.latcn.cache.example.dto;

import java.io.Serializable;
import lombok.Data;

@Data
public class TestDTO implements Serializable {

	private Long id;

	private String content;

	private String desc;

}
