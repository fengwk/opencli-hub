package fun.fengwk.openclihub.core.plugin.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.openclihub.core.plugin.repo.impl.model.HubPluginSourceDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * SQL mapper for configured OpenCLI plugin sources.
 *
 * @author fengwk
 */
public interface HubPluginSourceMapper extends BaseMapper {

    @Select("""
        select id, name, source, desired_plugins_json as desiredPluginsJson,
               enabled, auto_update as autoUpdate, last_status as lastStatus,
               last_error as lastError, last_synced_at as lastSyncedAt,
               last_result_json as lastResultJson,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_plugin_source
        order by gmt_create asc, id asc
        """)
    List<HubPluginSourceDO> listAll();

    @Select("""
        select id, name, source, desired_plugins_json as desiredPluginsJson,
               enabled, auto_update as autoUpdate, last_status as lastStatus,
               last_error as lastError, last_synced_at as lastSyncedAt,
               last_result_json as lastResultJson,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_plugin_source
        where id = #{id}
        """)
    HubPluginSourceDO findById(String id);

    @Select("""
        select id, name, source, desired_plugins_json as desiredPluginsJson,
               enabled, auto_update as autoUpdate, last_status as lastStatus,
               last_error as lastError, last_synced_at as lastSyncedAt,
               last_result_json as lastResultJson,
               gmt_create as createTime, gmt_modified as modifiedTime, version
        from hub_plugin_source
        where name = #{name}
        """)
    HubPluginSourceDO findByName(String name);

    @Insert("""
        insert into hub_plugin_source
        (id, name, source, desired_plugins_json, enabled, auto_update, last_status,
         last_error, last_synced_at, last_result_json, gmt_create, gmt_modified, version)
        values
        (#{id}, #{name}, #{source}, #{desiredPluginsJson}, #{enabled}, #{autoUpdate}, #{lastStatus},
         #{lastError}, #{lastSyncedAt}, #{lastResultJson}, #{createTime}, #{modifiedTime}, #{version})
        """)
    int insert(HubPluginSourceDO source);

    @Update("""
        update hub_plugin_source
        set name = #{name}, source = #{source}, desired_plugins_json = #{desiredPluginsJson},
            enabled = #{enabled}, auto_update = #{autoUpdate}, last_status = #{lastStatus},
            last_error = #{lastError}, last_synced_at = #{lastSyncedAt},
            last_result_json = #{lastResultJson}, gmt_modified = #{modifiedTime},
            version = version + 1
        where id = #{id} and version = #{version}
        """)
    int updateById(HubPluginSourceDO source);

    @Delete("delete from hub_plugin_source where id = #{id}")
    int deleteById(String id);

}
