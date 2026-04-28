package com.smmpanel.repository.jpa;

import com.smmpanel.entity.AppSetting;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    /**
     * List all settings with {@code updatedBy} eager-fetched. Used by the admin list endpoint to
     * avoid an N+1 over the seven-row settings table; cheap as one left join.
     */
    @Query("SELECT s FROM AppSetting s LEFT JOIN FETCH s.updatedBy ORDER BY s.key")
    List<AppSetting> findAllForListing();
}
