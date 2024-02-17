package com.dailymate.domain.user.service;

import com.dailymate.domain.user.dao.RefreshTokenRedisRepository;
import com.dailymate.domain.user.dao.UserRepository;
import com.dailymate.domain.user.domain.RefreshToken;
import com.dailymate.domain.user.domain.Users;
import com.dailymate.domain.user.dto.request.*;
import com.dailymate.domain.user.dto.response.LogInResDto;
import com.dailymate.domain.user.dto.response.MyInfoDto;
import com.dailymate.domain.user.dto.response.UserInfoDto;
import com.dailymate.domain.user.exception.UserBadRequestException;
import com.dailymate.domain.user.exception.UserExceptionMessage;
import com.dailymate.domain.user.exception.UserNotFoundException;
import com.dailymate.global.common.jwt.JwtTokenDto;
import com.dailymate.global.common.jwt.JwtTokenProvider;
import com.dailymate.global.common.jwt.constant.JwtTokenExpiration;
import com.dailymate.global.exception.exception.NotFoundException;
import com.dailymate.global.exception.exception.TokenException;
import com.dailymate.global.exception.exception.TokenExceptionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final JwtTokenProvider jwtTokenProvider;

    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Transactional
    @Override
    public void signUp(SignUpReqDto reqDto) {
        String email = reqDto.getEmail();
        log.info("[회원가입] 회원가입 요청 email : {}", email);

        // 이메일 중복 검사 -> 메서드 따로 뺼거임
        // 닉네임 중복 확인 -> 메서드 따로 뺄거임

        // 회원가입 정보 유효성 체크(다 입력했는가)
        if(!checkSignupInfo(reqDto)) {
            log.error("[회원가입] 회원가입 정보 유효성 검사 FALSE");
            throw new UserBadRequestException(UserExceptionMessage.SIGN_UP_BAD_REQUEST.getMsg());
        }

        // 비밀번호 정규화 체크(프론트에서 처리하지만, 만약을 대비해서 에러를 날리기로 합의함)
        if(!checkPasswordRegex(reqDto.getPassword())) {
            log.error("[회원가입] 비밀번호는 8~16자 이내 영문, 숫자, 특수문자를 포함해야합니다.");
            throw new UserBadRequestException(UserExceptionMessage.PASSWORD_NOT_MATCH_REGEX.getMsg());
        }

        // 비밀번호 암호화
        reqDto.setPassword(passwordEncoder.encode(reqDto.getPassword()));

        // 엔티티 저장
        userRepository.save(reqDto.dtoToEntity());
        log.info("[회원가입] 회원가입이 완료되었습니다 !!!");
    }

    /**
     * 회원가입 전 이메일 중복 검사
     * 중복 O - true / 중복 X - false
     */
    @Override
    public Boolean checkEmail(String email) {
        log.info("[이메일 중복 검사] email : {}", email);
        return userRepository.existsByEmail(email);
    }

    /**
     * 회원가입 전 닉네임 중복 검사
     * 중복 O - true / 중복 X - false
     */
    @Override
    public Boolean checkNickname(String nickname) {
        log.info("[닉네임 중복 검사] nickname : {}", nickname);
        return userRepository.existsByNickname(nickname);
    }

    @Transactional
    @Override
    public LogInResDto logIn(LogInReqDto reqDto) {
        String email = reqDto.getEmail();
        log.info("[로그인] 로그인 요청 email : {}", email);

        // 존재하는 회원 체크
        Users user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.error("[로그인] 존재하지 않는 사용자입니다.");
                    return new UserNotFoundException(UserExceptionMessage.USER_NOT_FOUND.getMsg());
                });

        // 1. email + password 기반으로 Authentication 객체 생성
        // 이때 authentication은 인증 여부를 확인하는 authenticated값이 false
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(email, reqDto.getPassword());

        // 2. 실제 검증(비밀번호 체크)
        // authenticate 메서드가 실행될 때 UserDetailsServiceImpl에서 만든 loadUserByUserName() 실행
        Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);

        // 3. 인증 정보를 기반으로 JWT 토큰 생성
        JwtTokenDto jwtToken = jwtTokenProvider.generateToken(authentication);

        // 4. Redis에 refreshToken 저장
        RefreshToken refreshToken = RefreshToken.builder()
                .email(email)
                .refreshToken(jwtToken.getRefreshToken())
                .expiration(JwtTokenExpiration.REFRESH_TOKEN_EXPIRATION_TIME.getTime() / 1000) // 나누기 1000왜하는거징
                .build();

        refreshTokenRedisRepository.save(refreshToken);

        return LogInResDto.builder()
                .accessToken(jwtToken.getAccessToken())
                .refreshToken(jwtToken.getRefreshToken())
                .email(email)
                .nickName(user.getNickname())
                .image(user.getImage())
                .profile(user.getProfile())
                .type(user.getType().getRole())
                .build();
    }

    @Override
    public JwtTokenDto reissueToken(String accessToken, String refreshToken) {
        // 1. refresh Token 검증
        if(!jwtTokenProvider.validateToken(refreshToken)) {
            log.error("[토큰 재발급] 리프레시 토큰이 유효하지 않습니다.");
            throw new TokenException(TokenExceptionMessage.TOKEN_EXPIRED_ERROR.getValue());
        }

        log.info("[토큰 재발급] accessToken : {}", accessToken);

        // 2. authentication 가져오기
        Authentication authentication = jwtTokenProvider.getAuthentication(accessToken);

        // 3. 저장소에서 email을 기반으로 refreshToken 가져오기
        RefreshToken originalRefreshToken = refreshTokenRedisRepository.findById(authentication.getName())
                .orElseThrow(() -> {
                    log.error("[토큰 재발급] 로그아웃 된 사용자입니다.");
                    return new NotFoundException(TokenExceptionMessage.TOKEN_NOT_FOUND.getValue());
                });
        log.info("[토큰 재발급] 리프레시 토큰 가져오기 성공 ! : {}", originalRefreshToken.getRefreshToken());

        // 4. refresh token 일치하는지 검사
        if(!refreshToken.equals(originalRefreshToken.getRefreshToken())) {
            log.error("[토큰 재발급] 토큰 불일치로 재발급이 불가합니다.");
            throw new TokenException(TokenExceptionMessage.TOKEN_NOT_EQUAL.getValue());
        }

        log.info("[토큰 재발급] 토큰 재발급 가능!");
        // 5. 토큰 재발급
        JwtTokenDto tokenDto = jwtTokenProvider.generateToken(authentication);

        // 6. 기존에 Redis에 저장된 토큰 업데이트
        originalRefreshToken.updateRefreshToken(tokenDto.getRefreshToken());
        refreshTokenRedisRepository.save(originalRefreshToken);

        return tokenDto;
    }

    @Override
    public MyInfoDto findMyInfo(String token) {
        Long userId = getLoginUserId(token);
        log.info("[내 정보 조회] 조회 요청 {}", userId);

        Users loginUser = getLoginUser(userId);

        return MyInfoDto.builder()
                .email(loginUser.getEmail())
                .nickname(loginUser.getNickname())
                .image(loginUser.getImage())
                .profile(loginUser.getProfile())
                .build();
    }

    @Override
    public void updateUser(String token, UpdateUserReqDto reqDto) {
        Long userId = getLoginUserId(token);
        log.info("[내 정보 수정] 수정 요청 : {}", userId);

        Users loginUser = getLoginUser(userId);

        // 수정 전 비밀번호 체크

        // 수정한 닉네임 중복 검사
        if(checkNickname(reqDto.getNickname())) {
            log.error("[내 정보 수정] 이미 사용중인 닉네임입니다. 다른 닉네임을 입력하세요.");
            throw new UserBadRequestException(UserExceptionMessage.NICKNAME_DUPLICATED.getMsg());
        }

        loginUser.updateUser(reqDto.getNickname(), reqDto.getProfile());
        userRepository.save(loginUser);

        log.info("[내 정보 수정] 정보 수정 완료. -----------------------------");
    }

    @Override
    public void updatePassword(String token, UpdatePasswordReqDto reqDto) {
        log.info("[패스워드 변경] 패드워드 변경 요청. ");


    }

    @Override
    public void withdraw(String token) {

    }

    @Override
    public Boolean checkPassword(String token, PasswordDto passwordDto) {
        return null;
    }

    @Override
    public void logout(String token) {

    }

    @Override
    public List<UserInfoDto> findUserList(String token) {
        return null;
    }

    @Override
    public UserInfoDto findUser(String token, Long userId) {
        return null;
    }

    @Override
    public UserInfoDto findUserByUserId(String token, Long userId) {
        return null;
    }

    @Override
    public List<MyInfoDto> findUserByNickname(String token, String nickname) {
        return null;
    }

    /**
     * 회원가입 정보 유효성 검사
     */
    private boolean checkSignupInfo(SignUpReqDto reqDto) {
        // StringUtils.hasText() : 값이 있을 경우 true, 공백이나 NULL일 경우 false
        if(!StringUtils.hasText(reqDto.getEmail()) ||
                !StringUtils.hasText(reqDto.getPassword()) ||
                !StringUtils.hasText(reqDto.getNickname()))
            return false;

        return true;
    }

    /**
     * 비밀번호 정규식 검사
     * 8 ~ 16자 이내 영문, 숫자, 특수문자 포함(대소문자 구분 X)
     * 포함 : true / 불포함 : false
     */
    private boolean checkPasswordRegex(String password) {
        Pattern regexPattern = Pattern.compile("^(?=.*[a-zA-Z])(?=.*\\d)(?=.*\\W).{8,16}$");
        Matcher matcher = regexPattern.matcher(password);

        return matcher.find();
    }

    /**
     * accessToken을 이용하여 로그인 사용자의 userId를 추출
     */
    private Long getLoginUserId(String token) {
        return jwtTokenProvider.getUserId(token);
    }

    /**
     * accessToken을 이용하여 로그인 사용자의 email 추출
     */
    private String getLoginUserEmail(String token) {
        return jwtTokenProvider.getUserEmail(token);
    }

    private Users getLoginUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.error("[유저 서비스] 사용자가 존재하지 않습니다.");
                    return new UserNotFoundException(UserExceptionMessage.USER_NOT_FOUND.getMsg());
                });
    }
}
