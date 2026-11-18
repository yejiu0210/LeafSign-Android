package cn.onetap.checkin.sched;

interface IShizukuDaemon {
    String runKeepAlive(int commandId) = 0;
    void setSchedule(boolean enabled, int hour, int minute) = 1;
    boolean isScheduled() = 2;
    void destroy() = 16777114;
}
