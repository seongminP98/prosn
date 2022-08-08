package com.ssafy.prosn.repository.user;

import com.ssafy.prosn.domain.user.Friend;
import com.ssafy.prosn.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * created by seongmin on 2022/08/08
 */
public interface FriendRepository extends JpaRepository<Friend, Long> {
    Page<Friend> findByFollower(User user, Pageable pageable); // 팔로잉 조회

    Page<Friend> findByFollowing(User user, Pageable pageable); // 팔로워 조회
}
