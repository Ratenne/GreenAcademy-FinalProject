package com.board.users.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor


public class UsersReviewListDto {
	
	private String title;
	private int store_idx;
	private int bookmark_idx;
	private Long user_idx;
	private String start_date;
	private String end_date;
	private int review_idx;
	private int score;
	private int like;
	private int hit;
	private String cdate;
	private String image_path;
	private int is_idx;
	


}