package com.suntek.efacecloud.dao;

import com.suntek.eap.EAP;
import com.suntek.eap.index.Query;
import com.suntek.eap.index.SearchEngineException;
import com.suntek.eap.log.ServiceLog;
import com.suntek.eap.util.EsUtil;
import com.suntek.eap.util.SqlUtil;
import com.suntek.eap.util.StringUtil;
import com.suntek.efacecloud.util.Constants;
import com.suntek.tactics.util.CommonUtil;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wudapei
 */
public class FaceCaptureStatisticDao {


    private JdbcTemplate jdbc = EAP.jdbc.getTemplate(Constants.APP_NAME);

    /**
     * 获取派出所设备数量
     *
     * @return List<Map < String, Object>>
     */
    public List<Map<String, Object>> getDeviceNumByOrgCode(String orgName) {

        String sql = "select t.NAME ORG_NAME, t1.* from SYS_STRUCTURE_INFO t  join ("
                + " select ORG_CODE, COUNT(ORG_CODE) DEVICE_COUNT from VPLUS_VIDEO_CAMERA "
                + " where ORG_CODE is not null"
                + " group by ORG_CODE)t1"
                + " on t.ORG_CODE = t1.ORG_CODE"
                + " where t.ORG_CODE != ''";

        if (!StringUtil.isEmpty(orgName)) {
            sql += " and NAME like ?";
            return jdbc.queryForList(sql, "%" + orgName + "%");
        }

        return jdbc.queryForList(sql);
    }


    /**
     * 根据行政区划编码获取设备列表
     *
     * @param orgCodeList
     * @return
     */
    public List<Map<String, Object>> getDeviceListByOrgCodeList(List<String> orgCodeList) {

        String sql = "select ORG_CODE, DEVICE_ID from VPLUS_VIDEO_CAMERA where SPECIAL_PURPOSE = 1 and ORG_CODE in "
                + SqlUtil.getSqlInParams(orgCodeList.toArray());

        return jdbc.queryForList(sql, orgCodeList.toArray());
    }

    /**
     * 获取人脸抓拍设备ID
     *
     * @return
     */
    public List<Map<String, Object>> getFaceDeviceIds() {
        String sql = "select DEVICE_ID from VPLUS_VIDEO_CAMERA where SPECIAL_PURPOSE = 1";
        return this.jdbc.queryForList(sql);
    }

    /**
     * 获取人脸抓拍设备数量
     *
     * @return
     */
    public int getFaceDeviceCount() {
        String sql = "select count(1) from VPLUS_VIDEO_CAMERA where SPECIAL_PURPOSE = 1";
        return jdbc.queryForObject(sql, Integer.class);
    }


    /**
     * 根据行政区划编码获取设备列表
     *
     * @param orgCodeList
     * @return
     */
    public List<Map<String, Object>> getDeviceListByOrgCode(String orgCode, String keyWord) {

        String sql = "select DEVICE_ID, NAME as DEVICE_NAME, IP_ADDR as DEVICE_IP_ADDR, "
                + " INSTALL_ADDR as DEVICE_INSTALL_ADDR, MANUFACTURER_NAME as DEVICE_MANUFACTURER_NAME"
                + " from VPLUS_VIDEO_CAMERA where ORG_CODE = ? and SPECIAL_PURPOSE = 1";

        if (!StringUtil.isEmpty(keyWord)) {
            sql += " and (DEVICE_ID like ? or NAME like ?)";
            return jdbc.queryForList(sql, orgCode, "%" + keyWord + "%", "%" + keyWord + "%");
        }

        return jdbc.queryForList(sql, orgCode);
    }

    /**
     * 获取一批设备的状态
     *
     * @param deviceList
     * @return Map<String, Object>，eg：44000000021312000001  设备故障 44000000021312000002    正常
     */
    public List<Map<String, Object>> getDeviceStatus(List<String> deviceList) {

        String sql = "select "
                + " DEVICE_ID, STATUS as DEVICE_STATUS FROM"
                + " ("
                + " select DEVICE_ID, STATUS from"
                + " FACE_DEVICE_INFO where DEVICE_ID in" + SqlUtil.getSqlInParams(deviceList.toArray())
                + " order by END_TIME DESC"
                + " limit ?"
                + " )t";

        List<Object> paramList = new ArrayList<Object>();
        paramList.addAll(deviceList);

        paramList.add(deviceList.size());

        return jdbc.queryForList(sql, paramList.toArray());

    }

    /**
     * 获取一批设备的状态数量
     *
     * @param deviceList
     * @return Map<String, Object>
     */
    public Map<String, Object> getDeviceStatusNum(List<String> deviceList) {

        String sql = "select "
                + " sum(case when STATUS = '正常' then 1 else 0 end ) NORMAL_DEVICE,"
                + " sum(case when STATUS != '正常' then 1 else 0 end )  UNNORMAL_DEVICE FROM"
                + " ("
                + " select STATUS from"
                + " FACE_DEVICE_INFO where DEVICE_ID in" + SqlUtil.getSqlInParams(deviceList.toArray())
                + " order by END_TIME DESC"
                + " limit ?"
                + " )t";

        List<Object> paramList = new ArrayList<Object>();
        paramList.addAll(deviceList);

        paramList.add(deviceList.size());

        List<Map<String, Object>> retList = jdbc.queryForList(sql, paramList.toArray());

        if (null != retList && retList.size() > 0) {
            return retList.get(0);
        }

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("NORMAL_DEVICE", 0);
        map.put("UNNORMAL_DEVICE", 0);

        return map;
    }

    /**
     * 按照场景分组人脸设备ID集合
     *
     * @return
     */
    public List<Map<String, Object>> findFaceSceneDeviceIds() {
        String sql = "SELECT "
                + " i.NAME, "
                + " GROUP_CONCAT( d.DEVICE_ID ) AS DEVICE_IDS  "
                + "FROM "
                + " VPLUS_SCENE_DEVICE d "
                + " INNER JOIN VPLUS_VIDEO_CAMERA c ON c.DEVICE_ID = d.DEVICE_ID "
                + " INNER JOIN VPLUS_SCENE_INFO i ON d.SCENE_ID = i.SCENE_ID  "
                + "WHERE "
                + " i.`STATUS` = 1  "
                + " AND c.SPECIAL_PURPOSE = 1  "
                + "GROUP BY "
                + " i.NAME";
        return jdbc.queryForList(sql);
    }

    /**
     * 查询抓拍量
     *
     * @param deviceIds 设备id集合
     * @param beginTime 开始日期,日期格式yyyy-mm-dd hh24:mi:ss
     * @param endTime   结束日期,日期格式yyyy-mm-dd hh24:mi:ss
     * @return
     */
    public int findCaptureNum(Collection<String> deviceIds, String beginTime, String endTime) {
        long beginTimeL = CommonUtil.convertJGSKToLong(StringUtil.toString(beginTime));
        long endTimeL = CommonUtil.convertJGSKToLong(StringUtil.toString(endTime));
        Query query = new Query(1, 1);
        query.addRangeCriteria("JGSK", beginTimeL, endTimeL);
        query.addEqualCriteria("DEVICE_ID", deviceIds.toArray(new String[deviceIds.size()]));
        String[] indices = EsUtil.getIndexNameByTime(Constants.FACE_INDEX + "_", beginTime, endTime);
        try {
            Long totalSize = EAP.bigdata.query(indices, Constants.FACE_TABLE, query).getTotalSize();
            return totalSize.intValue();
        } catch (SearchEngineException e) {
            ServiceLog.error(e);
        }
        return 0;
    }

    /**
     * 通过时间区域列表来得到总的抓拍数
     * @param timeRegionList
     * @return
     */
    public int findCaptureNumByTimeRegionList(List<Map<String, Object>> timeRegionList) {
        int total = 0;
        for (Map<String, Object> row : timeRegionList) {
            total += this.findCaptureNum(Arrays.asList(row.get("cross").toString().split(",")),
                    row.get("beginTime").toString(),
                    row.get("endTime").toString());
        }
        return total;
    }


}

