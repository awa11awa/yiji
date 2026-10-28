package cn.yiji.app.ui

/**
 * 首页的采集配置引导。
 *
 * 为什么需要：采集默认关闭，而且**必须选来源、必须有系统通知使用权**才生效。
 * 卸载重装会同时清空这三样，界面却一片空白——用户唯一能感知到的就是「什么消息都不进来」。
 *
 * 判定做成纯函数，四种状态可以精确测试。
 */
enum class SetupHint {
    /** 都配置好了，不打扰 */
    NONE,
    /** 还没有系统「通知使用权」 */
    NEEDS_PERMISSION,
    /** 有权但采集开关关着 */
    CAPTURE_OFF,
    /** 开关开着但一个来源都没选 */
    NO_SOURCES,
    /** 配置齐了但监听没连上 */
    NOT_CONNECTED,
}

fun setupHint(authorized: Boolean, collecting: Boolean, sourceCount: Int, connected: Boolean): SetupHint = when {
    !authorized -> SetupHint.NEEDS_PERMISSION
    !collecting -> SetupHint.CAPTURE_OFF
    sourceCount <= 0 -> SetupHint.NO_SOURCES
    !connected -> SetupHint.NOT_CONNECTED
    else -> SetupHint.NONE
}

fun setupHintTitle(hint: SetupHint): String = when (hint) {
    SetupHint.NEEDS_PERMISSION -> "第一条：授权读取通知"
    SetupHint.CAPTURE_OFF -> "第二条：打开采集"
    SetupHint.NO_SOURCES -> "第三条：选择来源应用"
    SetupHint.NOT_CONNECTED -> "通知监听未连接"
    SetupHint.NONE -> ""
}

fun setupHintBody(hint: SetupHint): String = when (hint) {
    SetupHint.NEEDS_PERMISSION ->
        "忆迹需要系统的「通知使用权」才能读到通知。卸载重装后这个授权会被收回，需要重新开一次。"
    SetupHint.CAPTURE_OFF ->
        "采集开关现在是关闭的，所有通知都会被跳过。"
    SetupHint.NO_SOURCES ->
        "还没有选择来源应用，所以任何通知都不会进来。去设置里用「全选」一次勾完即可。"
    SetupHint.NOT_CONNECTED ->
        "配置已经齐了，但监听服务没有连上。可以在设置里点「重新连接并补采通知栏」。"
    SetupHint.NONE -> ""
}
