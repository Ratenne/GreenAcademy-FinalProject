package com.board.util;

import java.io.IOException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;
	private final UserDetailsService userDetailsService;
	private final OAuth2UserService oAuth2UserService;
	private final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	// Constructor
	public JwtAuthenticationFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService,  OAuth2UserService oAuth2UserService) {
		this.jwtUtil = jwtUtil;
		this.userDetailsService = userDetailsService;
		this.oAuth2UserService = oAuth2UserService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		final String authorizationHeader = request.getHeader("Authorization");
		String jwt = null;

		// 1. Authorization 헤더가 없으면 쿠키에서 JWT 확인
		if (authorizationHeader == null) {
			Cookie[] cookies = request.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if (cookie.getName().equals("token")) {
						jwt = cookie.getValue();
						break;
					}
				}
			}
		} else if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {

			jwt = authorizationHeader.substring(7);


			String username = jwtUtil.extractUsername(jwt);
			String userType = jwtUtil.extractUserType(jwt); // JWT에서 사용자 유형 추출
			try {
				if (request.getRequestURI().startsWith("/oauth2/callback")) {
					processOAuth2Authentication(request, jwt);
				} else {
					processJwtAuthentication(request, jwt);
				}

			} catch (ExpiredJwtException e) {
				logger.warn("JWT 만료됨: {}", e.getMessage());
				redirectToLoginPage(request, response, "expired");
				return;
			} catch (SignatureException e) {
				logger.error("JWT 서명 검증 실패: {}", e.getMessage());
				redirectToLoginPage(request, response, "invalid");
				return;
			} catch (Exception e) {
				logger.error("JWT 인증 처리 중 오류 발생: {}", e.getMessage());
				redirectToLoginPage(request, response, "authFailed");
				return;
			}
		}

		// 2. JWT 검증
		/*
		 * if (jwt == null || !jwtUtil.validatableToken(jwt)) {
		 * response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // HTTP 401 상태 설정
		 * response.getWriter().write("Invalid Token");
		 * }
		 */
		if (SecurityContextHolder.getContext().getAuthentication() != null) {
			System.out.println("현재 SecurityContext 인증 정보: " + SecurityContextHolder.getContext().getAuthentication());
			System.out.println("Session ID: " + request.getSession(false));
			System.out.println("JWT 필터 경로: " + request.getServletPath());
			System.out.println("요청 헤더: " + Collections.list(request.getHeaderNames()));
			System.out.println("요청 파라미터: " + request.getParameterMap());
		} else {
		}

		filterChain.doFilter(request, response);
	}

	private boolean isMfaAuthenticated(HttpServletRequest request) {
		// 사용자가 MFA 인증을 완료했는지 확인하는 로직
		// 예를 들어, 세션이나 쿠키에서 MFA 완료 여부를 확인
		HttpSession session = request.getSession(false);
		if (session != null) {
			Boolean mfaAuthenticated = (Boolean) session.getAttribute("mfaAuthenticated");
			return mfaAuthenticated != null && mfaAuthenticated;
		}
		return false;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
		String path = request.getServletPath();
		boolean shouldExclude = path.equals("/error") || path.startsWith("/static/") || path.startsWith("/oauth2/authorization")
				|| path.startsWith("/Users/LoginForm") || path.startsWith("/Users/SignupForm")
				|| path.startsWith("/Users/Signup") || path.startsWith("/Users/CheckDuplication")
				|| path.startsWith("/CompanyAuth/Signup") || path.startsWith("/CompanyAuth/SignupForm")
				|| path.startsWith("/CompanyAuth/CheckDuplication") || path.startsWith("/Users/2fa") || path.startsWith("/WEB-INF/views/")
				|| path.startsWith("/Users/Admin/otp") || path.startsWith("/image") || path.startsWith("/js")
				|| path.startsWith("/Mobile/Users/LoginForm") || path.startsWith("/Mobile/Users/SignupForm");
		// System.out.println("필터 제외 여부: " + shouldExclude + ", 경로: " + path);
		logger.debug("필터 제외 여부: {}, 경로: {}", shouldExclude, path);
		return shouldExclude;
	}

	private String determineUserType(OAuth2User oauthUser) {
		// 예시: OAuth2 사용자 속성에서 역할을 기반으로 사용자 유형 결정
		String role = (String) oauthUser.getAttributes().get("role"); // 사용자 속성에서 역할을 추출

		if ("ROLE_ADMIN".equals(role)) {
			return "admin"; // 관리자
		} else if ("ROLE_COMPANY".equals(role)) {
			return "company"; // 회사 사용자
		} else {
			return "user"; // 기본값: 일반 사용자
		}
	}

	private void processOAuth2Authentication(HttpServletRequest request, String jwt) {
		logger.info("jwt",jwt);
		try {
			HttpSession session = request.getSession(false);
			if (session != null) {
				ClientRegistration clientRegistration = (ClientRegistration) session.getAttribute("clientRegistration");
				OAuth2AccessToken accessToken = (OAuth2AccessToken) session.getAttribute("accessToken");

				if (clientRegistration != null && accessToken != null) {
					OAuth2User oauth2User = oAuth2UserService.loadUser(new OAuth2UserRequest(clientRegistration, accessToken));
					if (jwtUtil.validateToken(jwt, oauth2User)) {
						UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
								oauth2User, null, oauth2User.getAuthorities());
						SecurityContextHolder.getContext().setAuthentication(authToken);
						authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
						session.removeAttribute("clientRegistration");
						session.removeAttribute("accessToken");
					} else {
						throw new SignatureException("Invalid OAuth2 JWT");
					}
				}
			}
		} catch (Exception e) {
			logger.error("OAuth2 인증 처리 중 오류 발생: {}", e.getMessage());
		}
	}

	private void processJwtAuthentication(HttpServletRequest request, String jwt) {
		try {
			String username = jwtUtil.extractUsername(jwt);
			if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
				UserDetails userDetails = userDetailsService.loadUserByUsername(username);
				logger.debug("연결된 사용자: {}", userDetails);
				if (jwtUtil.validateToken(jwt, userDetails)) {
					UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
							userDetails, null, userDetails.getAuthorities());
					SecurityContextHolder.getContext().setAuthentication(authToken);
					authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				} else {
					throw new SignatureException("Invalid JWT");
				}
			}
		} catch (Exception e) {
			logger.error("JWT 인증 처리 중 오류 발생: {}", e.getMessage());
		}
	}

	private void redirectToLoginPage(HttpServletRequest request, HttpServletResponse response, String error) throws IOException {
		String userAgent = request.getHeader("User-Agent");
		String redirectUrl;

		try {
			// JWT에서 userType 추출
			String jwt = null;
			Cookie[] cookies = request.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if (cookie.getName().equals("token")) {
						jwt = cookie.getValue();
						break;
					}
				}
			}

			String userType = (jwt != null) ? jwtUtil.extractUserType(jwt) : null;

			// 사용자 유형에 따라 리다이렉트 경로 설정
			if ("COMPANY".equalsIgnoreCase(userType)) {
				redirectUrl = "/CompanyAuth/LoginForm?error=" + error;
			} else if (userAgent != null && userAgent.contains("wv")) { // WebView에서 접속한 경우
				redirectUrl = "/Mobile/Users/LoginForm?error=true";
			} else {
				redirectUrl = "/Users/LoginForm?error=true";
			}
		} catch (Exception e) {
			logger.error("리다이렉트 URL 결정 중 오류 발생: {}", e.getMessage());
			redirectUrl = "/Users/LoginForm?error=true"; // 기본값
		}

		response.sendRedirect(redirectUrl);
	}
}
