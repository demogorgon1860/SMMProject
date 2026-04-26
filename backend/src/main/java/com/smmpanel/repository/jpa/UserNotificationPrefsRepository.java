package com.smmpanel.repository.jpa;

import com.smmpanel.entity.UserNotificationPrefs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserNotificationPrefsRepository extends JpaRepository<UserNotificationPrefs, Long> {}
