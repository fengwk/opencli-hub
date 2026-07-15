package fun.fengwk.openclihub.core.settings.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.settings.repo.impl.model.HubSystemSettingsDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * SQL mapper for the id=1 Hub settings singleton.
 *
 * @author fengwk
 */
public interface HubSystemSettingsMapper extends BaseMapper {

    @Select("""
        select id, proxy_mode as proxyMode, proxy_server as proxyServer,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_system_settings where id = 1
        """)
    HubSystemSettingsDO find();

    @Insert("""
        insert into hub_system_settings
        (id, proxy_mode, proxy_server, gmt_create, gmt_modified, version)
        values (#{id}, #{proxyMode}, #{proxyServer}, #{createTime}, #{modifiedTime}, #{version})
        """)
    int insert(HubSystemSettingsDO settings);

    @Update("""
        update hub_system_settings
        set proxy_mode = #{settings.proxyMode}, proxy_server = #{settings.proxyServer},
            gmt_modified = #{settings.modifiedTime}, version = version + 1
        where id = 1 and version = #{expectedVersion}
        """)
    int update(@org.apache.ibatis.annotations.Param("settings") HubSystemSettingsDO settings,
               @org.apache.ibatis.annotations.Param("expectedVersion") long expectedVersion);

}
