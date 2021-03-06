package com.suntek.efacecloud.listener;

import com.suntek.eap.EAP;
import com.suntek.eap.core.app.AppHandle;
import com.suntek.eap.log.LogFactory;
import com.suntek.eap.metadata.Table;
import com.suntek.eap.util.StringUtil;
import com.suntek.efacecloud.dao.FaceCommonDao;
import com.suntek.efacecloud.job.FaceNvNGetResultJob;
import com.suntek.efacecloud.job.FaceNvNTaskExecuteJob;
import com.suntek.efacecloud.service.redlist.FaceRedListDelegate;
import com.suntek.efacecloud.util.AlluxioClientUtil;
import com.suntek.efacecloud.util.ConfigUtil;
import com.suntek.efacecloud.util.Constants;
import com.suntek.feature.client.AlgorithmType;
import com.suntek.feature.client.DSSClient;
import com.suntek.tactics.api.dss.service.DssService;
import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.quartz.SchedulerException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块初始化监听类
 *
 * @author lx
 * @version 2017-06-29
 * @since 1.0.0
 */
public class SystemInitListener implements ServletContextListener {
	public static Logger log = LogFactory.getServiceLog(Constants.APP_NAME);

    @Override
    public void contextDestroyed(ServletContextEvent arg0) {
        try {
            EAP.schedule.delJob("faceCompareJob", "faceTriggerGroup");
        } catch (SchedulerException e) {
            log.error("调用人脸识别提取特征APP删除任务异常", e);
        }
        this.stopFaceNvn();
    }

    private void stopFaceNvn() {
        if (!ConfigUtil.getIsNvnAsync()) {
            return;
        }
        try {
            EAP.schedule.delJob("FaceNvNTaskExecuteJob", "FaceNvNTaskExecuteJobGroup");
        } catch (SchedulerException e) {
            log.error("nvn任务执行定时器删除任务异常" + e.getMessage(), e);
        }

        try {
            EAP.schedule.delJob("FaceNvNGetResultJob", "FaceNvNGetResultJobGroup");
        } catch (SchedulerException e) {
            log.error("nvn结果查询定时器删除任务异常" + e.getMessage(), e);
        }
    }

	@Override
	public void contextInitialized(ServletContextEvent context) {

        /**
         * 常住、户籍地址数据初始化
         */
        initAddressData();

        /**
         * 初始化加载schema.xml，仅限es
         */
		loadSchema(context);

        /**
         * Alluxio客户端初始化
         */
		AlluxioClientUtil.init();

        /**
         * 红名单库
         */
		initStaticLib();

        /**
         * 佳都人脸特征检索服务
         */
        initPciFaceFeatureServiceLink();

        initFaceNVN();
    }

    /**
     * 华为NVN定时器初始化
     */
    private void initFaceNVN() {
        try {
            String nvnTaskExpression = StringUtil
                    .toString(AppHandle.getHandle(Constants.APP_NAME).getProperty("FACE_NVN_EXCUTE_JOB_EXPRESSION"));
            if (!StringUtil.isNull(nvnTaskExpression)) {
                EAP.schedule.addCronTrigger(FaceNvNTaskExecuteJob.class, nvnTaskExpression, "FaceNvNTaskExecuteJob",
                        "FaceNvNTaskExecuteJobGroup", new HashMap<String, Object>());
            }
        } catch (SchedulerException e) {
            log.error("添加nvn任务执行定时器，发生异常" + e.getMessage(), e);
        }

        try {

            String nvnResultExpression = StringUtil
                    .toString(AppHandle.getHandle(Constants.APP_NAME).getProperty("FACE_NVN_RESULT_JOB_EXPRESSION"));
            if (!StringUtil.isNull(nvnResultExpression)) {
                EAP.schedule.addCronTrigger(FaceNvNGetResultJob.class, nvnResultExpression, "FaceNvNGetResultJob",
                        "FaceNvNGetResultJobGroup", new HashMap<String, Object>());
            }
        } catch (SchedulerException e) {
            log.error("获取nvn结果执行定时器，发生异常" + e.getMessage(), e);
        }

    }

    /**
     *  初始化
     *      人脸 1:N DSS客户端 连接
     *      人脸 N:N 服务
     */
    private void initPciFaceFeatureServiceLink(){
        try {

            String zkAddr = ConfigUtil.getOne2NConfig();
            String n2nAddr = ConfigUtil.getN2NConfig();

            log.debug("初始化   1:N/N:N DSS集群 begin...>>>"
                    + " zkAddr = " + zkAddr + "，n2nAddr = " + n2nAddr);

            if(!StringUtil.isNull(zkAddr)){
                DSSClient.addClient(zkAddr, AlgorithmType.FACE_ALGORITHM, ConfigUtil.getAlgoTypes());
                log.debug("人脸 DSS客户端 初始化成功！！！");
            }

            if(!StringUtil.isNull(n2nAddr)){

                DssService.init(n2nAddr);
                log.debug("人脸 N:N服务 初始化成功！！！");
            }

            log.debug("初始化   1:N/N:N DSS集群success!");


        } catch (Exception e) {
            log.error("人脸索引集群客户端初始化异常", e);
        }
    }

    private void initStaticLib() {
        FaceRedListDelegate faceRedListDelegate = new FaceRedListDelegate();
        faceRedListDelegate.initRedListLib();
    }

	private void initAddressData() {
		List<Map<Object, Object>> addressInfo = EAP.metadata.getDictList(Constants.DICT_KIND_PERSON_ADDRESS);
		if (addressInfo.size() == 0) {
			long time = System.currentTimeMillis();
			FaceCommonDao commonDao = new FaceCommonDao();
			List<Map<String, Object>> addressList = commonDao.getAddressInfo();
			addressList.stream().forEach(map -> {
				EAP.metadata.setDict(Constants.DICT_KIND_PERSON_ADDRESS, map.get("CODE"), map.get("NAME"));
			});
			log.debug("加载地址缓存--耗时:" + (System.currentTimeMillis() - time) + "ms");
		}
	}

	/**
	 * 加载大数据schema
	 *
	 * @param context
	 */
	@SuppressWarnings("unchecked")
	private void loadSchema(ServletContextEvent context) {

        String searchFun = AppHandle.getHandle("console").getProperty("BIGDATA_SEARCH_FUN", "0");
        String bigdataSearchByEs = "0";
        if(!bigdataSearchByEs.equals(searchFun)){
            return;
        }

		InputStream in = context.getServletContext().getResourceAsStream("/META-INF/schema.xml");
		if (in == null) {
			log.debug("Schema.xml not exits in Module efacecloud");
			return;
		}

		try {
			SAXReader reader = new SAXReader();
			Document doc = reader.read(in);
			List<Element> tables = doc.getRootElement().elements();
			for (Element element : tables) {
				Table table = new Table(element);
				if (!(table.getIndice().equals(""))) {
					EAP.bigdata.loadSchema(table.getIndice(), table.getName(), table);
				}
			}
		} catch (Exception e) {
			log.debug("Fail to read schema.xml in Module efacecloud " + e);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {
				}
			}
		}
	}
}
