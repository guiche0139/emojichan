package com.google.android.accessibility.selecttospeak

import com.aris.emojichan.sender.AutoSendService

/**
 * v0.2.212：一个只为了「名字」存在的空壳子类 —— 一个被验证过的绕过办法。
 *
 * 由来：微信 8.0.78 把控件树只交给「读屏」，任何别的无障碍服务拿到的都是
 * class=null、0 子节点的空壳根（用户 m15572 的日志：同一次读树里 QQ 184 个节点、
 * 小米系统界面 2 个子节点，只有微信是空壳；droidrun/mobilerun-portal#142 里只装普通服务时
 * uiautomator dump 也是空壳，打开 TalkBack 立刻拿到完整 62KB 树）。
 *
 * 做法来自一篇实测文章（博客园 Vennnnn 2025-03-26，针对微信 8.0.52+ 的节点混淆）：
 * 「创建一个注册一个与系统内置的相同包名类名的无障碍服务」，作者验证有效 ——
 * 先照着 TalkBack 的名字注册（com.google.android.marvin.talkback.TalkBackService）时
 * 小米手机屏幕上会一直显示两行读屏提示文字，于是换成 SelectToSpeak 的名字。
 * 这里照抄后者：包名 + 类名都是系统读屏「选择朗读」的，本类不写任何逻辑，
 * 全部行为从 [AutoSendService] 继承（注册进 AndroidManifest 的就是这个名字）。
 *
 * 注意：这不是伪装成系统应用（也没有那本事），只是让服务组件在系统里的「名字」
 * 落在读屏那一类里 —— 微信只要按名字放行，控件树就会回来；如果它按签名 / 系统标志放行，
 * 这条就无效，代价只是白测一次。
 */
class SelectToSpeakService : AutoSendService()
