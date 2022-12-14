package com.ssafy.prosn.service;

import com.ssafy.prosn.config.SecurityUtil;
import com.ssafy.prosn.domain.post.Post;
import com.ssafy.prosn.domain.profile.scrap.PostList;
import com.ssafy.prosn.domain.user.LocalUser;
import com.ssafy.prosn.domain.user.User;
import com.ssafy.prosn.dto.*;
import com.ssafy.prosn.exception.BadRequestException;
import com.ssafy.prosn.exception.DuplicateException;
import com.ssafy.prosn.repository.post.PostRepository;
import com.ssafy.prosn.repository.profiile.scrap.PostListRepository;
import com.ssafy.prosn.repository.profiile.status.SolvingRepository;
import com.ssafy.prosn.repository.user.FriendRepository;
import com.ssafy.prosn.repository.user.LocalUserRepository;
import com.ssafy.prosn.repository.user.UserRepository;
import com.ssafy.prosn.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * created by seongmin on 2022/07/22
 * updated by seongmin on 2022/08/11
 */
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final LocalUserRepository localUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManagerBuilder managerBuilder;
    private final JwtUtils jwtUtils;
    private final PostListRepository postListRepository;
    private final MailService mailService;
    private final FriendRepository friendRepository;
    private final PostRepository postRepository;
    private final SolvingRepository solvingRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    @Transactional
    public Long join(UserJoinRequestDto joinRequestDto) {
        validateDuplicate(joinRequestDto.getUserId(), joinRequestDto.getEmail());

        LocalUser user = userRepository.save(LocalUser.builder()
                .userId(joinRequestDto.getUserId())
                .name(joinRequestDto.getName())
                .email(joinRequestDto.getEmail())
                .password(passwordEncoder.encode(joinRequestDto.getPassword()))
                .build());

        // ???????????? ??? ????????? ?????? ?????? ??????
        postListRepository.save(PostList.builder()
                .user(user)
                .title("?????? ??????")
                .build());
        return user.getId();
    }

    @Override
    public TokenDto login(UserLoginRequestDto loginRequestDto) {
        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = loginRequestDto.toAuthentication();
        Authentication authentication = managerBuilder.getObject().authenticate(usernamePasswordAuthenticationToken);
        log.info("authentication = {}", authentication);
        log.info("authentication.getName = {}", authentication.getName());
        TokenDto tokenDto = jwtUtils.generateJwtToken(authentication);

        // RefreshToken ??????
        redisTemplate.opsForValue().set(
                authentication.getName(),
                tokenDto.getRefreshToken(),
                tokenDto.getRefreshTokenExpiresIn(),
                TimeUnit.MILLISECONDS
        );

        LocalUser loginUser = localUserRepository.findByUserId(loginRequestDto.getUserId()).get();
        tokenDto.setIdAndName(loginUser.getId(), loginUser.getName());

        return tokenDto;
    }

    @Override
    public TokenDto reissue(TokenRequestDto tokenRequestDto) {
        // Refresh Token ??????
        if (!jwtUtils.validateToken(tokenRequestDto.getRefreshToken())) {
            throw new IllegalArgumentException("Refresh Token ??? ???????????? ????????????.");
        }

        // Access Token ?????? user id ????????????
        Authentication authentication = jwtUtils.getAuthentication(tokenRequestDto.getAccessToken());
        // ??????????????? user id??? ???????????? Refresh Token ??? ????????????
        String refreshToken = redisTemplate.opsForValue().get(authentication.getName());

        // Refresh Token ??????????????? ??????
        if (refreshToken == null || !refreshToken.equals(tokenRequestDto.getRefreshToken())) {
            throw new IllegalArgumentException("????????? ?????? ????????? ???????????? ????????????.");
        }

        // ????????? ?????? ??????
        TokenDto tokenDto = jwtUtils.generateJwtToken(authentication);

        // Refresh Token ????????? ?????? ????????????
        redisTemplate.opsForValue().set(
                authentication.getName(),
                tokenDto.getRefreshToken(),
                tokenDto.getRefreshTokenExpiresIn(),
                TimeUnit.MILLISECONDS
        );

        return tokenDto;
    }

    @Override
    public void logout(Long id) {
        log.info("???????????? ?????? ?????? = {}", redisTemplate.opsForValue().get(String.valueOf(id)));
        if (redisTemplate.opsForValue().get(String.valueOf(id)) != null) {
            redisTemplate.delete(String.valueOf(id));
        }
    }


    @Override
    public UserResponseDto getMyInfoBySecret() {
        return userRepository.findById(SecurityUtil.getCurrentMemberId())
                .map(UserResponseDto::of)
                .orElseThrow(() -> new UsernameNotFoundException("????????? ?????? ????????? ????????????."));
    }

    @Override
    public List<UserRankingResponseDto> ranking() {
        List<User> ranking = userRepository.findTop5ByOrderByPointDesc();
        List<UserRankingResponseDto> result = new ArrayList<>();
        for (User user : ranking) {
            result.add(UserRankingResponseDto.of(user));
        }
        return result;
    }

    @Override
    public void duplicateUserId(String userId) {
        if (localUserRepository.existsByUserId(userId)) {
            throw new DuplicateException("?????? ?????? ??????????????????.");
        }
    }

    @Override
    public UserInfoDto getUserInfo(Long uid) {
        User user = userRepository.findById(uid).orElseThrow(() -> new BadRequestException("???????????? ?????? ??????????????????."));
        Long followingCount = friendRepository.countByFollower(user);
        Long followerCount = friendRepository.countByFollowing(user);
        log.info("user.getPosts.size = {}", user.getPosts().size());
        Long problemSolvingCount = solvingRepository.countByUser(user);
        Long problemCorrectCount = solvingRepository.countByUserAndFirstIsRight(user, true);
        double correctRate = Math.round((problemCorrectCount * 1.0 / problemSolvingCount) * 100) / 100.0 * 100;
        Long writePostCount = postRepository.countByUser(user);
        return UserInfoDto.of(user, followerCount, followingCount, correctRate, problemSolvingCount, writePostCount);
    }

    @Override
    public PostDto getUserPost(Long uid, Pageable pageable) {
        User user = userRepository.findById(uid).orElseThrow(() -> new BadRequestException("???????????? ?????? ??????????????????."));
        Page<Post> posts = postRepository.findByIsDeletedAndUser(false, user, pageable);
        return PostDto.of(posts.getContent(), posts.getTotalPages(), posts.getTotalElements());
    }

    private void validateDuplicate(String userId, String email) {
        if (localUserRepository.existsByUserId(userId)) {
            throw new DuplicateException("?????? ?????? ??????????????????.");
        }
        if (localUserRepository.existsByEmail(email)) {
            throw new DuplicateException("?????? ?????? ??????????????????.");
        }
    }

    @Transactional
    public LocalUser resetPwd(LocalUser user) {
        String encodedResetPassword = mailService.sendMail(user.getEmail());
        return user.updatePassword(encodedResetPassword);
    }


}
