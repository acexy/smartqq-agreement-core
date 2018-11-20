package com.thankjava.wqq.core.event;

import com.thankjava.toolkit.core.reflect.ReflectUtil;
import com.thankjava.toolkit.core.thread.ThreadPool;
import com.thankjava.toolkit3d.bean.http.AsyncResponse;
import com.thankjava.wqq.WQQClient;
import com.thankjava.wqq.consts.ConfigParams;
import com.thankjava.wqq.core.action.LoginAction;
import com.thankjava.wqq.core.request.RequestBuilder;
import com.thankjava.wqq.core.request.api.Poll2;
import com.thankjava.wqq.entity.Session;
import com.thankjava.wqq.entity.enums.PullMsgStatus;
import com.thankjava.wqq.entity.msg.PollMsg;
import com.thankjava.wqq.entity.sys.MonitoringData;
import com.thankjava.wqq.extend.ActionListener;
import com.thankjava.wqq.extend.CallBackListener;
import com.thankjava.wqq.factory.ActionFactory;
import com.thankjava.wqq.factory.RequestFactory;
import com.thankjava.wqq.util.JSON2Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 消息事件处理器
 */
public class MsgPollEvent {

    private static final Logger logger = LoggerFactory.getLogger(MsgPollEvent.class);
    private static Session session = Session.getSession();
    private ThreadPool threadPool = new ThreadPool(10, 20, 60 * 1000, 20);
    private RequestBuilder poll2 = RequestFactory.getInstance(Poll2.class);
    private LoginAction loginAction = ActionFactory.getInstance(LoginAction.class);

    public void poll() {

        poll2.doRequest(new CallBackListener() {

            @Override
            public void onListener(ActionListener actionListener) {

                PullMsgStatus pullMsgStatus;

                if (actionListener.getData() != null) {

                    AsyncResponse response = (AsyncResponse) actionListener.getData();
                    if (response.getException() != null) {

                        logger.error("MsgPollEvent Error", response.getException());
                        pullMsgStatus = PullMsgStatus.http_exception;

                    } else {

                        if (response.getHttpCode() == 200) {

                            PollMsg pollMsg = JSON2Entity.pollMsg(response.getDataString());

                            if (pollMsg != null) {

                                notifyMsgEvent(pollMsg);
                                pullMsgStatus = PullMsgStatus.normal;

                            } else {
                                // 未能解析响应数据
                                pullMsgStatus = PullMsgStatus.http_response_error;
                            }

                        } else {
                            pullMsgStatus = PullMsgStatus.http_status_error;
                        }
                    }

                } else {
                    logger.error("MsgPollEvent error");
                    pullMsgStatus = PullMsgStatus.http_exception;
                }

                if (doExceptionCheck(pullMsgStatus)) {
                    poll();
                }

            }
        });
    }

    private void notifyMsgEvent(final PollMsg pollMsg) {

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                WQQClient.getNotifyListener().handler(WQQClient.getInstance(), pollMsg);
            }
        });
    }

    // 用来监控&计算当前SmartQQClient的健康状态
    private boolean doExceptionCheck(PullMsgStatus pullMsgStatus) {

        MonitoringData monitoringData = session.getMonitoringData(pullMsgStatus);
        monitoringData.addData();
        double avgValue = monitoringData.getAverageValueOfOneSecond();

        if (avgValue >= 1) {

            logger.debug("MsgStatus: " + pullMsgStatus + " 类型平均值超出，可能处于登录异常，执行掉线重连");

            // 重置监控数据
            session.resetMonitoringData();
            try {
                Method method = ReflectUtil.getMethod(LoginAction.class, "beginLogin");
                int retryTimes = ConfigParams.AUTO_RE_LOGIN_RETRY_MAX_TIME;
                while (retryTimes > 0) {
                    boolean flag = (boolean) ReflectUtil.invokeMethod(loginAction, method);
                    if (flag) {
                        logger.debug("系统执行自动重连完成");
                        break;
                    }
                    retryTimes--;
                }
                if (retryTimes == 0) {
                    // 计算是否处于连续重连失败的情况
                    logger.debug("系统执行自动重连失败已达到上限，已放弃尝试");
                    CallBackListener callBackListener = WQQClient.getOfflineListener();
                    if (callBackListener != null) {
                        callBackListener.onListener(new ActionListener(WQQClient.getInstance()));
                    }
                }
            } catch (Exception e) {
                // TODO:
            }
            return false;
        }
        return true;
    }


}
